"""F19 guards mock PG/JAR/JVM/sockets."""
from contextlib import ExitStack, contextmanager, redirect_stdout
import base64
import inspect
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

import f19_ci as f19
from test_f17_ci_guards import HOSTED


def token(identity, expiry, role):
    payload = base64.urlsafe_b64encode(json.dumps({'userId': identity, 'role': role, 'exp': expiry}).encode()).decode().rstrip('=')
    return 'synthetic.' + payload + '.signature'


def identity_rows(owner='123-1'):
    return '\n'.join(str(stage) + ':' + marker + ':' + owner for stage, marker in
                     ((3, f19.f3.MARKER), (16, f19.f18.f17.f16.MARKER), (17, f19.f18.f17.MARKER)))


@contextmanager
def packaged_harness(directory, failure=None, mutation=False, cache='mock-observed-security-header', cookie=False,
                     storage_mutation=False, startup_mutation=False, log=None):
    root = Path(directory)
    evidence = root / 'evidence'
    jar = root / 'boot/build/libs/pawbridge-backend-app.jar'
    jar.parent.mkdir(parents=True)
    jar.write_bytes(b'mocked-jar')
    requests = []
    started = False
    tokens = {'F19_ADMIN': 'private-admin-token', 'F19_USER': 'private-user-token'}
    statuses = {2: 403, 3: 500, 5: 401, 6: 403}
    messages = {2: f19.ADMIN_ONLY, 3: f19.SERVER_ERROR, 5: '인증 토큰이 필요합니다.', 6: '접근할 수 없습니다.'}

    def request(base, path, **kwargs):
        requests.append((path, kwargs))
        number = len(requests)
        if number == failure:
            raise AssertionError('mocked request failed')
        status = statuses.get(number, 200)
        response = {'code': status, 'message': messages.get(number, f19.SUCCESS), 'data': 1 if status == 200 else None}
        headers = {'Cache-Control': cache}
        if cookie:
            headers['Set-Cookie'] = 'forbidden'
        return status, json.dumps(response).encode(), headers

    def storage(*args, **kwargs):
        changed = storage_mutation and bool(requests)
        return {'counts': {'PUT': 1 if changed else 0, 'DELETE': 0}, 'objects': [], 'requests': []}

    def start(*args):
        nonlocal started
        started = True
        return object(), object(), 'http://app', 'http://stub', 1, 0

    env = dict(HOSTED, F2_TEST_DB_PASSWORD='private-reader', F3_TEST_WRITER_PASSWORD='private-writer', F3_TEST_JWT_SECRET='private-jwt')
    with ExitStack() as stack:
        for item in (patch.dict(os.environ, env, clear=True), patch.object(f19, 'ROOT', root), patch.object(f19, 'EVIDENCE', evidence),
                     patch.object(f19, 'CACHE_CONTROL', 'mock-observed-security-header'),
                     patch.object(f19, 'require_fixture', return_value='123-1'), patch.object(f19, 'require_identity', return_value='123-1'),
                     patch.object(f19, 'load_tokens', return_value=tokens),
                     patch.object(f19, 'database_snapshot', side_effect=lambda: {'users': [{}], 'state':
                         'changed' if (mutation and requests) or (startup_mutation and started) else 'same'}),
                     patch.object(f19.f3, 'request', side_effect=request), patch.object(f19.f3, 'expect', side_effect=storage),
                     patch.object(f19.f4, 'fresh_log', return_value=log if log is not None else
                         'Shutdown completed. Shutdown completed. Graceful shutdown complete')):
            stack.enter_context(item)
        sql = stack.enter_context(patch.object(f19.f3, 'sql', return_value='0'))
        seed = stack.enter_context(patch.object(f19, 'prepare_business_rows', return_value=f19.OWNED))
        start_mock = stack.enter_context(patch.object(f19.f4, 'start_ready_children', side_effect=start))
        stop = stack.enter_context(patch.object(f19.f3, 'stop_owned_child', return_value=0))
        cleanup = stack.enter_context(patch.object(f19, 'cleanup_business_rows'))
        yield evidence, requests, sql, start_mock, stop, cleanup, seed


class F19GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_actions__then_refuse_before_resources(self):
        for action in (f19.prepare, f19.require_fixture, f19.require_owner, f19.require_identity, f19.prepare_business_rows,
                       lambda: f19.cleanup_business_rows(f19.OWNED), f19.smoke, f19.load_tokens):
            with self.subTest(action=action), patch.dict(os.environ, {}, clear=True), patch.object(f19.f3, 'sql') as sql, \
                    patch.object(f19.f2, 'command') as command, patch.object(subprocess, 'check_output') as git, \
                    patch.object(f19.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'):
                    action()
                sql.assert_not_called(); command.assert_not_called(); git.assert_not_called(); start.assert_not_called()

    def test_given_wrong_target_or_reader__when_preparing__then_no_command_or_evidence(self):
        for changed in ({'F2_TEST_JDBC_URL': 'jdbc:postgresql://production:5432/pawbridge_f2_ci'}, {'F2_TEST_DB_USER': 'postgres'}):
            with patch.dict(os.environ, dict(HOSTED, **changed), clear=True), patch.object(f19.f2, 'command') as command, patch.object(Path, 'write_text') as write:
                with self.assertRaises(SystemExit):
                    f19.prepare()
                command.assert_not_called(); write.assert_not_called()

    def test_given_existing_fixture_or_invalid_chain__when_preparing__then_reuse_only_and_preserve_conflicting_evidence(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f19, 'EVIDENCE', Path(directory).resolve()), \
                patch.object(f19, 'require_fixture', return_value='123-1') as fixture, patch.object(f19, 'database_snapshot', return_value={'unchanged': []}), \
                patch.object(f19.f3, 'sql') as sql, patch.object(f19.f2, 'export_environment') as export:
            f19.prepare(); fixture.assert_called_once_with(); sql.assert_not_called(); export.assert_not_called()
            target = Path(directory) / 'f19-fixture-reuse.txt'
            self.assertIn('owner=123-1', target.read_text()); self.assertIn('new_acl=0', target.read_text())
            target.write_text('preserve-other-proof')
            with self.assertRaisesRegex(SystemExit, 'differing or redirected F19'):
                f19.prepare()
            self.assertEqual('preserve-other-proof', target.read_text())
            fixture.side_effect = SystemExit('F17 mismatch')
            with patch.object(Path, 'write_text') as write, self.assertRaisesRegex(SystemExit, 'F17 mismatch'):
                f19.prepare()
            write.assert_not_called()

    def test_given_f19_helpers__when_reusing_f18__then_same_fixture_fullsnapshot_and_no_new_ddl_acl_marker(self):
        self.assertIs(f19.require_fixture, f19.f18.require_fixture)
        self.assertIs(f19.require_owner, f19.f18.require_owner)
        self.assertIs(f19.database_snapshot, f19.f18.database_snapshot)
        self.assertFalse((f19.ROOT / 'scripts/f19_fixture.sql').exists())
        source = inspect.getsource(f19)
        for forbidden in ('CREATE TABLE', 'CREATE SCHEMA', 'CREATE INDEX', 'CREATE SEQUENCE', 'F19_CI_OWNER',
                          'export_environment', 'f18.prepare(', 'f18.smoke('):
            self.assertNotIn(forbidden, source)

    def test_given_fresh_principals__when_seeding__then_only_user_token_id_gets_one_admin_row(self):
        with patch.object(f19, 'require_fixture', return_value='123-1'), patch.object(f19.f3, 'sql', side_effect=['0', str(f19.USER)]) as sql:
            self.assertEqual(f19.OWNED, f19.prepare_business_rows())
        preflight, setup = [c.args[0] for c in sql.call_args_list]
        self.assertIn(str(f19.ADMIN), preflight); self.assertIn(str(f19.USER), preflight)
        self.assertEqual(1, setup.count('INSERT INTO pawbridge_user.users'))
        self.assertIn("'ROLE_ADMIN'", setup); self.assertIn('RETURNING user_id) SELECT user_id FROM inserted', setup)
        self.assertNotIn(str(f19.ADMIN), setup)
        self.assertNotRegex(setup, r'\b42\b|UPDATE |ON CONFLICT|TRUNCATE|DROP |contact_deletions|private_note_')

    def test_given_any_existing_principal__when_seeding__then_refuse_before_insert(self):
        with patch.object(f19, 'require_fixture', return_value='123-1'), patch.object(f19.f3, 'sql', return_value='1') as sql:
            with self.assertRaisesRegex(SystemExit, 'pre-existing User'):
                f19.prepare_business_rows()
        self.assertEqual(1, sql.call_count); self.assertTrue(sql.call_args.args[0].startswith('SELECT '))

    def test_given_successfully_inserted_user__when_cleaning__then_delete_exact_key_and_email_without_claiming_old_rows(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f19, 'require_identity', return_value='123-1') as owner, patch.object(f19.f3, 'sql') as sql:
            f19.cleanup_business_rows(f19.OWNED)
        owner.assert_called_once_with()
        sql.assert_called_once_with('DELETE FROM pawbridge_user.users WHERE user_id=' + str(f19.USER) + " AND email='" + f19.EMAIL + "'")
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f19, 'require_identity') as owner, patch.object(f19.f3, 'sql') as sql:
            with self.assertRaises(ValueError):
                f19.cleanup_business_rows((42,))
            owner.assert_not_called(); sql.assert_not_called()

    def test_given_identity_only_check__when_owner_or_marker_differs__then_refuse_without_full_acl_or_mutation(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f19, 'require_fixture') as full, patch.object(f19.f3, 'sql', return_value=identity_rows()) as sql:
            self.assertEqual('123-1', f19.require_identity('123-1')); full.assert_not_called()
            self.assertIn('ORDER BY stage', sql.call_args.args[0]); self.assertNotIn('has_column_privilege', sql.call_args.args[0])
            sql.return_value = identity_rows('foreign')
            with self.assertRaisesRegex(SystemExit, 'marker identity'):
                f19.require_identity('123-1')
        with patch.dict(os.environ, dict(HOSTED, F17_CI_OWNER='foreign'), clear=True), patch.object(f19.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'owner identity'):
                f19.require_identity('123-1')
            sql.assert_not_called()

    def test_given_revoked_user_id_select__when_body_fails__then_identity_only_restore_precedes_full_acl_recheck(self):
        events = []
        revoked = False
        def sql(query):
            nonlocal revoked
            events.append(query)
            if query.startswith('REVOKE '): revoked = True
            if query.startswith('GRANT '): revoked = False
            return identity_rows() if query.startswith('SELECT stage') else ''
        def full():
            self.assertFalse(revoked, 'strict chain must not run while user_id is revoked')
            events.append('full')
            return '123-1'
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f19, 'require_fixture', side_effect=full), patch.object(f19.f3, 'sql', side_effect=sql):
            with self.assertRaisesRegex(AssertionError, 'owned body failed'):
                with f19.denied_user_id_select():
                    raise AssertionError('owned body failed')
        self.assertFalse(revoked)
        self.assertEqual(['full', 'REVOKE', 'SELECT', 'GRANT', 'full'], [e.split()[0] for e in events])

    def test_given_wrong_owner_during_revocation__when_unwinding__then_stop_before_grant_or_full_acl(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f19, 'require_fixture', return_value='123-1') as full, \
                patch.object(f19.f3, 'sql', return_value='') as sql:
            with self.assertRaisesRegex(SystemExit, 'owner identity'):
                with f19.denied_user_id_select():
                    os.environ['F17_CI_OWNER'] = 'foreign'
        self.assertEqual(1, full.call_count)
        self.assertEqual(['REVOKE'], [c.args[0].split()[0] for c in sql.call_args_list])

    def test_given_owned_sql_failure__when_reporting__then_hide_command_stdout_stderr_and_details(self):
        failure = subprocess.CalledProcessError(1, ['private command'], output='private body', stderr='private SQL')
        with patch.object(f19.f3, 'sql', side_effect=failure):
            with self.assertRaises(SystemExit) as result:
                f19.owned_sql('private SQL')
        message = str(result.exception)
        for secret in ('private command', 'private body', 'private SQL'):
            self.assertNotIn(secret, message)

    def test_given_token_path_symlink_or_mode_mismatch__when_loading__then_refuse(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / 'f3-tokens-123-1.json'; path.write_text('{}'); path.chmod(0o600)
            env = dict(HOSTED, RUNNER_TEMP=str(root), F3_TEST_TOKENS_PATH=str(path))
            alias = root / 'alias.json'; alias.symlink_to(path)
            for changed in ({'F3_TEST_TOKENS_PATH': str(root / 'foreign.json')}, {'F3_TEST_TOKENS_PATH': str(alias)}):
                with patch.dict(os.environ, dict(env, **changed), clear=True), self.assertRaises(SystemExit):
                    f19.load_tokens()
            path.chmod(0o644)
            with patch.dict(os.environ, env, clear=True), self.assertRaisesRegex(SystemExit, '0600'):
                f19.load_tokens()

    def test_given_tokens__when_loading__then_only_used_keys_need_fresh_correct_identity_role_and_old_bytes_stay_unchanged(self):
        now = time.time()
        valid = {'F19_ADMIN': token(f19.ADMIN, now + 600, 'ROLE_ADMIN'), 'F19_USER': token(f19.USER, now + 600, 'ROLE_USER'),
                 'F18_CALLER': 'unused-stale', 'ROLE_USER': 'unused-expired', 'expired': 'preserved', 'F14_OTHER': 'preserved'}
        invalid = ({}, dict(valid, F19_ADMIN=token(f19.ADMIN, now + 30, 'ROLE_ADMIN')),
                   dict(valid, F19_ADMIN=token(f19.USER, now + 600, 'ROLE_ADMIN')),
                   dict(valid, F19_USER=token(f19.USER, now + 600, 'ROLE_ADMIN')),
                   dict(valid, F19_ADMIN=token(float(f19.ADMIN), now + 600, 'ROLE_ADMIN')),
                   dict(valid, F19_USER=token(f19.USER, float('nan'), 'ROLE_USER')), dict(valid, F19_ADMIN=7))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / 'f3-tokens-123-1.json'
            with patch.dict(os.environ, dict(HOSTED, RUNNER_TEMP=str(root), F3_TEST_TOKENS_PATH=str(path)), clear=True):
                for content in invalid:
                    path.write_text(json.dumps(content)); path.chmod(0o600)
                    with self.assertRaises(SystemExit):
                        f19.load_tokens()
                path.write_text(json.dumps(valid)); path.chmod(0o600); before = path.read_bytes()
                self.assertEqual({key: valid[key] for key in ('F19_ADMIN', 'F19_USER')}, f19.load_tokens())
                self.assertEqual(before, path.read_bytes())

    def test_given_unobserved_cache_contract__when_smoking__then_refuse_before_starting_or_seeding(self):
        with patch.object(f19, 'require_fixture', return_value='123-1'), patch.object(f19, 'CACHE_CONTROL', None), \
                patch.object(f19.f4, 'start_ready_children') as start, patch.object(f19, 'prepare_business_rows') as seed:
            with self.assertRaisesRegex(SystemExit, 'actual Boot Security observation'):
                f19.smoke()
            start.assert_not_called(); seed.assert_not_called()

    def test_given_mocked_packaged_flow__when_observing__then_six_requests_exact_roles_safe_failure_recovery_and_two_pools(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            refreshed_at = []
            current_tokens = f19.load_tokens
            def refreshed_tokens():
                refreshed_at.append(len(requests))
                return current_tokens()
            with patch.object(f19, 'load_tokens', side_effect=refreshed_tokens):
                f19.smoke()
            self.assertEqual([0, 0, 1, 2, 3, 5], refreshed_at)
            result = json.loads((evidence / 'f19-packaged-result.txt').read_text())
            self.assertEqual('passed', result['status']); self.assertEqual(6, len(requests))
            self.assertEqual(5, result['total_user_count_get_checks']); self.assertEqual(2, result['successful_checks']); self.assertEqual(3, result['rejected_checks'])
            for key in ('administrator_denied_checks', 'sql_failure_checks', 'authentication_checks', 'denied_route_checks'):
                self.assertEqual(1, result[key])
            self.assertEqual(1, start.call_count); self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f19.OWNED)
            self.assertEqual(1, result['shutdown_pools']); self.assertEqual(0, result['remaining_application_connections'])
            self.assertEqual('Bearer private-user-token', requests[1][1]['headers']['Authorization'])
            self.assertEqual('ROLE_ADMIN', requests[1][1]['headers']['X-User-Role'])
            self.assertNotIn('Authorization', requests[4][1]['headers']); self.assertEqual('/api/v1/admin/users/stats/daily-signups/extra', requests[5][0])
            self.assertEqual('4', start.call_args.args[1]['PAWBRIDGE_DB_POOL_MAX_SIZE'])
            self.assertNotIn('DAILY_VISITORS_ENABLED', start.call_args.args[1])
            self.assertNotIn('expected_count', result); self.assertNotIn('data', result)

    def test_given_tokens_become_stale_during_before_snapshot__when_it_completes__then_recheck_before_request_and_cleanup_incomplete(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            events = []
            request_snapshot_started = request_snapshot_complete = False
            current_storage, current_database, current_tokens = f19.f3.expect, f19.database_snapshot, f19.load_tokens
            def storage_snapshot(*args, **kwargs):
                nonlocal request_snapshot_started
                value = current_storage(*args, **kwargs)
                if seed.called:
                    request_snapshot_started = True
                return value
            def database_snapshot():
                nonlocal request_snapshot_complete
                value = current_database()
                if request_snapshot_started:
                    request_snapshot_complete = True
                    events.append('before snapshot completed')
                return value
            def fresh_tokens():
                if request_snapshot_complete:
                    events.append('stale gate')
                    raise SystemExit('stale after snapshot')
                events.append('fresh load')
                return current_tokens()
            with patch.object(f19.f3, 'expect', side_effect=storage_snapshot), \
                    patch.object(f19, 'database_snapshot', side_effect=database_snapshot), \
                    patch.object(f19, 'load_tokens', side_effect=fresh_tokens), \
                    self.assertRaisesRegex(SystemExit, 'stale after snapshot'):
                f19.smoke()
            self.assertEqual(['fresh load', 'before snapshot completed', 'stale gate'], events)
            self.assertEqual([], requests)
            self.assertEqual(1, start.call_count); seed.assert_called_once_with()
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f19.OWNED)
            self.assertEqual('incomplete', json.loads((evidence / 'f19-packaged-result.txt').read_text())['status'])

    def test_given_failure_while_revoked__when_unwinding__then_restore_stop_both_cleanup_and_keep_incomplete_receipt(self):
        for number in (2, 3):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, failure=number) as (evidence, requests, sql, start, stop, cleanup, seed):
                with self.assertRaisesRegex(AssertionError, 'mocked request failed'):
                    f19.smoke()
                self.assertIn('GRANT SELECT(user_id)', '\n'.join(c.args[0] for c in sql.call_args_list))
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f19.OWNED)
                self.assertEqual('incomplete', json.loads((evidence / 'f19-packaged-result.txt').read_text())['status'])

    def test_given_header_cookie_db_or_s3_mutation__when_observing__then_fail_and_cleanup(self):
        for fault in ({'cache': 'public'}, {'cookie': True}, {'mutation': True}, {'storage_mutation': True}):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **fault) as (_, _, _, _, stop, cleanup, seed):
                with self.assertRaises(AssertionError):
                    f19.smoke()
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f19.OWNED)

    def test_given_startup_mutation__when_starting__then_fail_before_seed_and_claim_no_owned_keys(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, startup_mutation=True) as (_, requests, _, _, stop, cleanup, seed):
            with self.assertRaisesRegex(AssertionError, 'startup changed'):
                f19.smoke()
            seed.assert_not_called(); cleanup.assert_not_called(); self.assertEqual([], requests); self.assertEqual(2, stop.call_count)

    def test_given_secret_or_missing_shutdown_evidence__when_finishing__then_do_not_claim_pass(self):
        for text in ('Graceful shutdown complete',
                     'private-admin-token Shutdown completed. Shutdown completed. Graceful shutdown complete'):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, log=text) as (evidence, _, _, _, _, _, _):
                with self.assertRaises(AssertionError):
                    f19.smoke()
                self.assertEqual('incomplete', json.loads((evidence / 'f19-packaged-result.txt').read_text())['status'])

    def test_given_workflow__when_adding_f19__then_append_after_f18_and_keep_always_cleanup(self):
        workflow = (f19.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('- name: Verify F19 unchanged fixture')
        self.assertLess(workflow.index('python3 scripts/f18_ci.py smoke'), boundary)
        stage = workflow[boundary:]
        for first, second in (('test_f19_ci_guards.py', 'python3 scripts/f19_ci.py prepare'),
                              ('python3 scripts/f19_ci.py prepare', 'python3 scripts/f19_ci.py smoke')):
            self.assertLess(stage.index(first), stage.index(second))
        self.assertNotIn('totalUserCountCompatibility', workflow)
        self.assertNotIn('python3 scripts/f19_ci.py results', workflow)
        cleanup = stage.index('- name: Remove only the owned disposable fixture')
        self.assertIn('if: always()', stage[cleanup:cleanup + 180]); self.assertIn('python3 scripts/f3_ci.py cleanup', stage[cleanup:cleanup + 180])
        self.assertNotRegex(stage[:cleanup], r'python3 scripts/f(?:[2-9]|1[0-8])_ci\.py (?:prepare|smoke)')
        self.assertNotIn('f19_fixture', workflow)
        for required in ('f19-fixture-reuse.txt', 'f19-helper-refusal.txt'):
            self.assertIn(required, workflow)

    def test_given_f19_test_artifacts__when_applying_jar_filter__then_reject_without_widening_validator_exception(self):
        text = (f19.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        denied = next(line for line in text.splitlines() if "if grep -E '" in line).split("if grep -E '", 1)[1].split("' build/ci/", 1)[0]
        allowed = next(line for line in text.splitlines() if "| grep -Ev '" in line).split("| grep -Ev '", 1)[1].split("'; then", 1)[0]
        for path in ('BOOT-INF/classes/com/pawbridge/backend/compatibility/F19Input.class', 'BOOT-INF/classes/com/pawbridge/userservice/controller/F19LegacyTotalUserCountController.class',
                     'test/compatibilityTest/hibernate-validator-8.0.3.Final.jar'):
            self.assertIsNotNone(re.search(denied, path)); self.assertIsNone(re.search(allowed, path))
        self.assertIsNotNone(re.search(allowed, 'BOOT-INF/lib/hibernate-validator-8.0.3.Final.jar'))

if __name__ == '__main__':
    print('F19 guards: mocked PostgreSQL/JAR/JVM/sockets; fixed inputs use read-only git show, no Git mutations.', flush=True)
    with redirect_stdout(io.StringIO()):
        unittest.main()
