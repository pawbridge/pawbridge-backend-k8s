"""F23 guard drafts use resources mocked by the tests, never actual PG/JAR evidence."""
from contextlib import ExitStack, contextmanager, redirect_stdout
import base64
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

import f23_ci as f23
from test_f17_ci_guards import HOSTED

MOCK_COUNTS = dict(zip(f23.COUNTERS, (9, 6, 3, 1, 1, 1, 1, 1, 1)))


def token(identity, expiry, role='ROLE_USER'):
    claims = base64.urlsafe_b64encode(json.dumps({'userId': identity, 'role': role, 'exp': expiry}).encode()).decode().rstrip('=')
    return 'synthetic.' + claims + '.signature'


@contextmanager
def packaged_harness(directory, failure=None, mutation=False, storage_mutation=False, startup_mutation=False,
                     cache=None, cookie=False, bad_payload=False, shutdown=None, remaining='0'):
    root = Path(directory).resolve(); evidence = root / 'evidence'
    jar = root / 'boot/build/libs/pawbridge-backend-app.jar'
    jar.parent.mkdir(parents=True); jar.write_bytes(b'mock-only-jar')
    requests, started = [], False
    statuses = {6: 400, 7: 503, 9: 401, 10: 403, 11: 403}
    messages = {6: '페이지 범위를 확인해 주세요.', 7: f23.STORAGE_UNAVAILABLE,
                9: '인증 토큰이 필요합니다.', 10: '접근할 수 없습니다.', 11: '접근할 수 없습니다.'}

    def request(base, path, **kwargs):
        requests.append((path, kwargs)); number = len(requests)
        if number == failure: raise AssertionError('mocked request failed')
        status = statuses.get(number, 200)
        data = (f23.expected_detail(f23.INBOX[-1]) if number == 12 else f23.expected_page(
                box='SENT' if number == 3 else 'INBOX', favorite=number == 4,
                page=1 if number == 2 else 10000 if number == 5 else 0))
        body = ({'code': 200, 'message': f23.SUCCESS, 'data': data} if status == 200 else
                {'code': status, 'message': messages[number], 'data': None} if number in (9, 10, 11) else {'message': messages[number]})
        if bad_payload and status == 200: body['data']['size'] = '10'
        headers = {'Cache-Control': cache if cache is not None else f23.f19.CACHE_CONTROL if number in (9, 10, 11) else 'no-store'}
        if cookie: headers['Set-Cookie'] = 'forbidden'
        return status, json.dumps(body).encode(), headers

    def storage(*args, **kwargs):
        return {'counts': {'PUT': 1 if storage_mutation and requests else 0, 'DELETE': 0}, 'objects': [], 'requests': []}

    def start(*args):
        nonlocal started
        started = True
        return object(), object(), 'http://app', 'http://stub', 1, 0

    env = dict(HOSTED, F2_TEST_DB_PASSWORD='private-reader', F3_TEST_WRITER_PASSWORD='private-writer', F3_TEST_JWT_SECRET='private-jwt')
    with ExitStack() as stack:
        for item in (patch.dict(os.environ, env, clear=True), patch.object(f23, 'ROOT', root), patch.object(f23, 'EVIDENCE', evidence),
                     patch.object(f23, 'EXPECTED_JAR_COUNTS', MOCK_COUNTS), patch.object(f23, 'require_fixture', return_value='123-1'),
                     patch.object(f23, 'require_owner', return_value='123-1'), patch.object(f23.f21, 'check_receipt'),
                     patch.object(f23.f21, 'require_f21_receipt'), patch.object(f23.f19, 'require_identity', return_value='123-1'),
                     patch.object(f23, 'load_tokens', return_value={'F23_CALLER': 'private-used-token'}),
                     patch.object(f23, 'database_snapshot', side_effect=lambda: {'state': 'changed' if
                         (mutation and requests) or (startup_mutation and started) else 'same'}),
                     patch.object(f23.f3, 'request', side_effect=request), patch.object(f23.f3, 'expect', side_effect=storage),
                     patch.object(f23.f4, 'fresh_log', return_value=shutdown if shutdown is not None else
                                  'Shutdown completed. Shutdown completed. Graceful shutdown complete')):
            stack.enter_context(item)
        sql = stack.enter_context(patch.object(f23.f3, 'sql', side_effect=lambda query: remaining if query.endswith("'pawbridge_f3_writer')") else '0'))
        seed = stack.enter_context(patch.object(f23, 'prepare_business_rows', return_value=f23.OWNED))
        start_mock = stack.enter_context(patch.object(f23.f4, 'start_ready_children', side_effect=start))
        stop = stack.enter_context(patch.object(f23.f3, 'stop_owned_child', return_value=0))
        cleanup = stack.enter_context(patch.object(f23, 'cleanup_business_rows'))
        yield evidence, requests, sql, start_mock, stop, cleanup, seed


class F23GuardTest(unittest.TestCase):
    def test_given_delegated_f22_fixture__when_checking__then_use_exact_current_contract_without_grants_or_old_checker(self):
        with patch.object(f23.f22, 'require_fixture', return_value='123-1') as fixture, patch.object(f23, 'owned_sql') as sql:
            self.assertEqual('123-1', f23.require_fixture()); fixture.assert_called_once(); sql.assert_not_called()
        with patch.object(f23.f22, 'require_owner', return_value='123-1') as owner:
            self.assertEqual('123-1', f23.require_owner()); owner.assert_called_once()

    def test_given_current_fixture_and_stable_snapshots__when_preparing__then_metadata_receipt_only_with_zero_sql_mutation(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f23, 'EVIDENCE', Path(directory).resolve()), patch.object(f23, 'require_fixture', return_value='123-1') as strict, \
                patch.object(f23, 'database_snapshot', return_value={str(i): [] for i in range(16)}), patch.object(f23, 'owned_sql') as sql:
            f23.prepare(); self.assertEqual(2, strict.call_count); sql.assert_not_called()
            receipt = (f23.EVIDENCE / 'f23-fixture-reuse.txt').read_text()
            self.assertIn('added_reader_columns=0', receipt); self.assertIn('source_sha=' + f23.SOURCE, receipt)

    def test_given_missing_or_foreign_receipt_or_changing_fixture__when_preparing__then_no_sql_and_no_success_receipt_write(self):
        for phase in ('initial', 'receipt', 'owner', 'snapshot'):
            with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f23, 'EVIDENCE', Path(directory).resolve()), \
                    patch.object(f23, 'require_fixture', side_effect=SystemExit('initial') if phase == 'initial' else ['123-1', 'foreign'] if phase == 'owner' else ['123-1','123-1']), \
                    patch.object(f23.f21, 'check_receipt', side_effect=SystemExit('receipt') if phase == 'receipt' else None), \
                    patch.object(f23, 'database_snapshot', side_effect=[{}, {'changed': True}] if phase == 'snapshot' else [{},{}]), \
                    patch.object(f23, 'owned_sql') as sql, patch.object(Path, 'write_text') as write:
                with self.assertRaises(SystemExit): f23.prepare()
                sql.assert_not_called(); write.assert_not_called()

    def test_given_non_hosted_invocation__when_calling_actions__then_refuse_before_sql_or_children(self):
        actions = (f23.prepare, f23.require_fixture, f23.require_owner, f23.prepare_business_rows,
                   lambda: f23.cleanup_business_rows(f23.OWNED), f23.load_tokens, f23.smoke)
        for action in actions:
            with patch.dict(os.environ, {}, clear=True), patch.object(f23.f3, 'sql') as sql, \
                    patch.object(f23.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'): action()
                sql.assert_not_called(); start.assert_not_called()

    def test_given_unmeasured_counts__when_smoke_requested__then_stop_before_resources(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f23, 'EXPECTED_JAR_COUNTS', None), \
                patch.object(f23, 'require_fixture') as fixture, patch.object(f23.f4, 'start_ready_children') as start:
            with self.assertRaisesRegex(SystemExit, 'unmeasured'): f23.smoke()
            fixture.assert_not_called(); start.assert_not_called()


    def test_given_incomplete_snapshot__when_comparing__then_refuse_instead_of_omitting_existing_tables(self):
        with patch.object(f23.f21, 'database_snapshot', return_value={str(i): [] for i in range(15)}):
            with self.assertRaisesRegex(SystemExit, 'sixteen'): f23.database_snapshot()

    def test_given_mailbox_time_denial_and_request_failure__when_unwinding__then_identity_and_all_receipts_precede_grant_and_strict_after_restore(self):
        events = []
        with patch.object(f23, 'require_fixture', side_effect=lambda: events.append('strict') or '123-1'), \
                patch.object(f23, 'owned_sql', side_effect=lambda query: events.append(query.split()[0])), \
                patch.object(f23.f19, 'require_identity', side_effect=lambda owner: events.append('identity')), \
                patch.object(f23.f21, 'check_receipt', side_effect=lambda path, proof: events.append(path.name)), \
                patch.object(f23.f21, 'require_f21_receipt', side_effect=lambda owner: events.append('f21')):
            with self.assertRaisesRegex(AssertionError, 'request'):
                with f23.denied_mailbox_time(): raise AssertionError('request')
        self.assertEqual(['strict', 'REVOKE', 'identity', 'f20-fixture-reuse.txt', 'f21', 'f22-fixture-reuse.txt', 'GRANT', 'strict'], events)

    def test_given_changed_owner_under_revocation__when_unwinding__then_no_restore_sql_or_strict_check(self):
        with patch.object(f23, 'require_fixture', return_value='123-1') as strict, \
                patch.object(f23.f19, 'require_identity', side_effect=SystemExit('foreign owner')), patch.object(f23, 'owned_sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'foreign owner'):
                with f23.denied_mailbox_time(): pass
            self.assertEqual(1, sql.call_count); self.assertTrue(sql.call_args.args[0].startswith('REVOKE ')); strict.assert_called_once()

    def test_given_synthetic_key_collision__when_seeding__then_no_insert_or_owned_success(self):
        with patch.object(f23, 'require_fixture'), patch.object(f23, 'owned_sql', return_value='1') as sql:
            with self.assertRaisesRegex(SystemExit, 'must all be absent'): f23.prepare_business_rows()
            self.assertEqual(1, sql.call_count); self.assertTrue(sql.call_args.args[0].startswith('SELECT '))

    def test_given_absent_keys__when_seeding__then_one_atomic_owned_users_members_notes_mailboxes_without_blocks_or_requests(self):
        with patch.object(f23, 'require_fixture'), patch.object(f23, 'owned_sql', side_effect=['0', '']) as sql:
            self.assertEqual(f23.OWNED, f23.prepare_business_rows())
        statement = sql.call_args.args[0]
        self.assertTrue(statement.startswith('BEGIN;')); self.assertTrue(statement.endswith('COMMIT;'))
        self.assertEqual(4, statement.count('INSERT INTO ')); self.assertIn(f23.BODY.replace("'", "''"), statement)
        for forbidden in ('UPDATE ', 'private_note_blocks', 'private_note_requests', 'contact_deletions', 'SEQUENCE', 'CREATE '): self.assertNotIn(forbidden, statement)

    def test_given_successful_owned_keys__when_cleaning__then_fk_order_and_identity_before_each_exact_key_delete(self):
        events = []
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f23, 'require_owner', side_effect=lambda: events.append('owner')), \
                patch.object(f23, 'owned_sql', side_effect=lambda query: events.append(query)):
            f23.cleanup_business_rows(f23.OWNED)
        self.assertEqual(['owner'] * 4, events[::2])
        for statement, table in zip(events[1::2], ('private_note_mailboxes', 'private_notes', 'private_note_members', 'users')):
            self.assertIn('.' + table + ' WHERE ', statement); self.assertNotIn('TRUNCATE', statement)
        self.assertIn('body=', events[3]); self.assertIn('(user_id,email)', events[-1])

    def test_given_unowned_cleanup_tuple__when_cleaning__then_no_sql(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f23, 'owned_sql') as sql:
            with self.assertRaises(ValueError): f23.cleanup_business_rows(())
            sql.assert_not_called()

    def test_given_used_token_with_old_keys__when_loading__then_regular_0600_owner_identity_role_ttl_only_without_rewrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / 'f3-tokens-123-1.json'
            env = dict(HOSTED, RUNNER_TEMP=str(path.parent), F3_TEST_TOKENS_PATH=str(path)); now = time.time()
            valid = {'F23_CALLER': token(f23.CALLER, now + 600), 'F21_CALLER': 'old-preserved'}
            with patch.dict(os.environ, env, clear=True):
                path.write_text(json.dumps(valid)); path.chmod(0o600); original = path.read_bytes()
                self.assertEqual({'F23_CALLER': valid['F23_CALLER']}, f23.load_tokens()); self.assertEqual(original, path.read_bytes())
                for value in ({}, dict(valid, F23_CALLER=token(f23.CALLER, now + 30)), dict(valid, F23_CALLER=token(f23.OTHER, now + 600)),
                              dict(valid, F23_CALLER=token(f23.CALLER, now + 600, 'ROLE_ADMIN')), dict(valid, F23_CALLER=token(float(f23.CALLER), now + 600))):
                    path.write_text(json.dumps(value))
                    with self.assertRaises(SystemExit): f23.load_tokens()
                path.write_text(json.dumps(valid)); path.chmod(0o644)
                with self.assertRaises(SystemExit): f23.load_tokens()
                path.chmod(0o600); other = path.parent / 'other'; path.rename(other); path.symlink_to(other)
                with self.assertRaises(SystemExit): f23.load_tokens()

    def test_given_mocked_twelve_request_journey__when_observing__then_exact_payload_recovery_after_snapshot_ttl_and_cleanup(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            calls = []; current = f23.load_tokens
            def fresh(): calls.append(len(requests)); return current()
            with patch.object(f23, 'load_tokens', side_effect=fresh): f23.smoke()
            result = json.loads((evidence / 'f23-packaged-result.txt').read_text())
            self.assertEqual('passed', result['status']); self.assertEqual(12, len(requests))
            self.assertEqual(MOCK_COUNTS, {key: result[key] for key in f23.COUNTERS})
            self.assertEqual([0, 0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11], calls)
            self.assertNotIn('Authorization', requests[8][1]['headers']); self.assertEqual('/api/v1/notes/stream/extra', requests[9][0])
            self.assertEqual('POST', requests[10][1]['method']); self.assertEqual('/api/v1/notes/unexposed/send', requests[10][0])
            self.assertEqual('/api/v1/notes/' + f23.INBOX[-1], requests[11][0])
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f23.OWNED)
            self.assertEqual('4', start.call_args.args[1]['PAWBRIDGE_DB_POOL_MAX_SIZE']); self.assertNotIn('DAILY_VISITORS_ENABLED', start.call_args.args[1])

    def test_given_stale_token_after_snapshot__when_request_due__then_zero_requests_two_children_stop_owned_cleanup_incomplete(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            state = {'before': False}; events = []; database, tokens = f23.database_snapshot, f23.load_tokens
            def snapshot():
                value = database()
                if seed.called: state['before'] = True; events.append('snapshot')
                return value
            def fresh():
                if state['before']: events.append('stale'); raise SystemExit('stale after snapshot')
                events.append('mask'); return tokens()
            with patch.object(f23, 'database_snapshot', side_effect=snapshot), patch.object(f23, 'load_tokens', side_effect=fresh):
                with self.assertRaisesRegex(SystemExit, 'stale after snapshot'): f23.smoke()
            self.assertEqual(['mask', 'snapshot', 'stale'], events); self.assertEqual([], requests)
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f23.OWNED)
            self.assertEqual('incomplete', json.loads((evidence / 'f23-packaged-result.txt').read_text())['status'])

    def test_given_startup_database_or_request_database_or_s3_mutation__when_observing__then_fail_and_stop_children(self):
        for options in ({'startup_mutation': True}, {'mutation': True}, {'storage_mutation': True}):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **options) as (evidence, requests, sql, start, stop, cleanup, seed):
                with self.assertRaises(AssertionError): f23.smoke()
                self.assertEqual(2, stop.call_count)
                if seed.called: cleanup.assert_called_once_with(f23.OWNED)
                else: cleanup.assert_not_called()
                self.assertEqual('incomplete', json.loads((evidence / 'f23-packaged-result.txt').read_text())['status'])

    def test_given_request_failure_under_mailbox_time_revocation__when_unwinding__then_restore_grant_stop_children_cleanup_and_never_pass(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, failure=7) as (evidence, requests, sql, start, stop, cleanup, seed):
            with self.assertRaises(AssertionError): f23.smoke()
            statements = [call.args[0] for call in sql.call_args_list]
            self.assertTrue(any(query.startswith('REVOKE SELECT(created_at)') for query in statements))
            self.assertTrue(any(query.startswith('GRANT SELECT(created_at)') for query in statements))
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f23.OWNED)
            self.assertEqual('incomplete', json.loads((evidence / 'f23-packaged-result.txt').read_text())['status'])

    def test_given_restore_failure__when_unwinding__then_no_passed_result_but_both_children_and_owned_cleanup(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            original = f23.owned_sql
            def failing(query):
                if query.startswith('GRANT SELECT(created_at)'): raise SystemExit('restore failed')
                return original(query)
            with patch.object(f23, 'owned_sql', side_effect=failing):
                with self.assertRaisesRegex(SystemExit, 'restore failed'): f23.smoke()
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f23.OWNED)
            self.assertEqual('incomplete', json.loads((evidence / 'f23-packaged-result.txt').read_text())['status'])

    def test_given_failed_seed__when_unwinding__then_stop_children_without_deleting_unclaimed_keys(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            seed.side_effect = SystemExit('insert failed')
            with self.assertRaisesRegex(SystemExit, 'insert failed'): f23.smoke()
            self.assertEqual(2, stop.call_count); cleanup.assert_not_called(); self.assertEqual([], requests)

    def test_given_wrong_cache_cookie_or_payload__when_observing__then_fail_exact_wire_contract(self):
        for options in ({'cache': 'public'}, {'cookie': True}, {'bad_payload': True}):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **options) as (evidence, requests, sql, start, stop, cleanup, seed):
                with self.assertRaises(AssertionError): f23.smoke()
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f23.OWNED)

    def test_given_missing_shutdown_remaining_connections_or_private_log__when_finishing__then_never_mark_passed(self):
        for options in ({'shutdown': 'Graceful shutdown complete'}, {'remaining': '1'}, {'shutdown': 'Shutdown completed. Shutdown completed. Graceful shutdown complete private-reader'}):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **options) as (evidence, requests, sql, start, stop, cleanup, seed):
                with self.assertRaises(AssertionError): f23.smoke()
                self.assertEqual('incomplete', json.loads((evidence / 'f23-packaged-result.txt').read_text())['status'])

    def test_given_sparse_checkout__when_reading_pinned_inputs__then_the_fixture_source_object_exists(self):
        subprocess.check_output(['git', 'cat-file', '-e', f23.SOURCE + '^{commit}'], cwd=f23.ROOT.parent)

    def test_given_workflow__when_integrating_f23__then_f22_finishes_first_prepare_before_smoke_always_cleanup_and_pinned_source_mask(self):
        workflow = (f23.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('- name: Verify F23 unchanged fixture')
        self.assertLess(workflow.index('python3 scripts/f22_ci.py smoke'), boundary); stage = workflow[boundary:]
        for first, second in (('test_f23_ci_guards.py', 'python3 scripts/f23_ci.py prepare'),
                              ('python3 scripts/f23_ci.py prepare', 'python3 scripts/f23_ci.py smoke')):
            self.assertLess(stage.index(first), stage.index(second))
        self.assertNotIn('privateNoteListCompatibility', workflow)
        self.assertNotIn('python3 scripts/f23_ci.py results', workflow)
        cleanup = stage.index('- name: Remove only the owned disposable fixture'); self.assertIn('if: always()', stage[cleanup:cleanup + 180])
        self.assertNotRegex(stage[:cleanup], r'python3 scripts/f(?:[2-9]|1[0-9]|2[0-2])_ci\.py (?:prepare|smoke)')
        source_start = workflow.index('- name: Require the immutable F23 original-source Git object')
        source_end = workflow.index('\n      - name:', source_start)
        source_step = workflow[source_start:source_end]
        for value in (f23.SOURCE, 'git cat-file -e', 'git fetch --no-tags --no-recurse-submodules --depth=1 origin',
                      'GIT_CONFIG_COUNT=1', 'F23_SOURCE_READ_TOKEN: ${{ github.token }}', 'printf \'::add-mask::%s\\n\' "$source_auth"'):
            self.assertIn(value, source_step)
        self.assertNotIn('--force', source_step); self.assertNotIn('git config ', source_step); self.assertNotIn('export GIT_CONFIG', source_step)
        self.assertNotRegex(source_step, r'https://[^\s]*\$(?:F23_SOURCE_READ_TOKEN|source_auth)')
        self.assertLess(source_step.index('::add-mask::'), source_step.index('GIT_CONFIG_COUNT=1'))
        self.assertEqual(1, source_step.count('GIT_CONFIG_COUNT=1'))
        self.assertIn("grep -F 'restricted to this repository on GitHub-hosted Linux' build/ci/f23-helper-refusal.txt", workflow)
        self.assertIn('sparse-checkout:', workflow); self.assertNotIn('            community-service\n', workflow)
        denied = next(line for line in workflow.splitlines() if "if grep -E '" in line).split("if grep -E '", 1)[1].split("' build/ci/", 1)[0]
        self.assertIsNotNone(re.search(denied, 'BOOT-INF/classes/com/pawbridge/backend/compatibility/F23Input.class'))
        self.assertIsNotNone(re.search(denied, 'BOOT-INF/classes/com/pawbridge/communityservice/contact/F23LegacyPrivateNoteListService.class'))


if __name__ == '__main__':
    print('F23 private guard drafts: resources mocked, pinned Git reads only. No actual PG/JAR evidence.', flush=True)
    with redirect_stdout(io.StringIO()): unittest.main()
