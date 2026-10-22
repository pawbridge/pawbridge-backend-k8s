"""F17 guards: mocked resources; sparse inputs are immutable hash-pinned Git objects."""
from contextlib import ExitStack, contextmanager, redirect_stdout
import base64
import hashlib
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

import f17_ci as f17
import prepare_f17_legacy_oracle as oracle
from test_f16_ci_guards import HOSTED as F16_HOSTED

HOSTED = dict(F16_HOSTED, F17_CI_OWNER='123-1')


def pinned_source(path, repository=None):
    expected = f17.DDL_HASHES.get(path) or oracle.EXPECTED.get(path)
    if expected is None: raise ValueError('F17 guard input must have an explicit immutable hash')
    sha = f17.SOURCE if path in f17.DDL_HASHES else oracle.SOURCE
    content = subprocess.check_output(['git', 'show', sha + ':' + path], cwd=repository or f17.ROOT.parent)
    if hashlib.sha256(content).hexdigest() != expected:
        raise AssertionError('Fixed F17 guard input checksum mismatch: ' + path)
    return content


def oracle_paths(directory):
    root = Path(directory).resolve()
    return root, root / 'backend-app/boot/build/generated/sources/f17LegacyOracle/java', root / 'backend-app/build/ci/f17-oracle-sha256.txt'


def shared_inputs(root, sources):
    for source in oracle.SHARED:
        target = root / 'backend-app/boot/build/generated/sources/f2LegacyOracle/java' / source[len(oracle.COMMUNITY):]
        target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(sources[source])


def token(identity, expiry, role='ROLE_USER'):
    payload = base64.urlsafe_b64encode(json.dumps({'userId': identity, 'role': role, 'exp': expiry}).encode()).decode().rstrip('=')
    return 'synthetic.' + payload + '.signature'


@contextmanager
def packaged_harness(directory, failure=None, mutation=False, cache='no-store', cookie=False, storage_mutation=False):
    root = Path(directory); evidence = root / 'evidence'
    jar = root / 'boot/build/libs/pawbridge-backend-app.jar'; jar.parent.mkdir(parents=True); jar.write_bytes(b'mocked-jar')
    requests = []
    tokens = {'F17_CALLER': 'private-caller-token', 'F17_OTHER': 'private-other-token', 'expired': 'private-expired-token'}
    statuses = {3: 403, 4: 403, 5: 403, 6: 403, 7: 401, 8: 401, 9: 503, 11: 503, 13: 401, 14: 401, 15: 401, 16: 400, 17: 403, 18: 403}
    messages = {3: f17.BLOCKED, 4: f17.BLOCKED, 5: f17.BLOCKED, 6: f17.INACTIVE, 7: f17.LOGIN, 8: f17.LOGIN,
                9: f17.MEMBER_UNAVAILABLE, 11: f17.STORAGE_UNAVAILABLE, 13: '인증 토큰이 필요합니다.',
                14: '유효하지 않거나 만료된 토큰입니다.', 15: '유효하지 않거나 만료된 토큰입니다.',
                16: f17.INVALID, 17: '접근할 수 없습니다.', 18: '접근할 수 없습니다.'}
    def request(base, path, **kwargs):
        requests.append((path, kwargs)); number = len(requests)
        if number == failure: raise AssertionError('mocked request failed')
        status = statuses.get(number, 200); message = messages.get(number, f17.SUCCESS)
        response = {'code': status, 'message': message, 'data': None} if status == 200 or number in (13, 14, 15, 17, 18) else {'message': message}
        if status == 200:
            identity = f17.CALLER if number == 2 else f17.OTHER
            response['data'] = {'userId': identity, 'nickname': f17.NICKNAMES[identity], 'active': True, 'deletionPending': False}
        headers = {'Cache-Control': cache}
        if cookie: headers['Set-Cookie'] = 'forbidden'
        return status, json.dumps(response).encode(), headers
    def storage(*args, **kwargs):
        changed = storage_mutation and bool(requests)
        return {'counts': {'PUT': 1 if changed else 0, 'DELETE': 0}, 'objects': [], 'requests': []}
    env = dict(HOSTED, F2_TEST_DB_PASSWORD='private-reader', F3_TEST_WRITER_PASSWORD='private-writer', F3_TEST_JWT_SECRET='private-jwt')
    with ExitStack() as stack:
        for item in (patch.dict(os.environ, env, clear=True), patch.object(f17, 'ROOT', root), patch.object(f17, 'EVIDENCE', evidence),
                     patch.object(f17, 'require_fixture', return_value='123-1'), patch.object(f17, 'require_owner', return_value='123-1'),
                     patch.object(f17, 'load_tokens', return_value=tokens), patch.object(f17, 'prepare_business_rows', return_value=f17.IDS),
                     patch.object(f17, 'database_snapshot', side_effect=lambda: {'state': 'changed' if mutation and requests else 'same'}),
                     patch.object(f17.f3, 'request', side_effect=request), patch.object(f17.f3, 'expect', side_effect=storage),
                     patch.object(f17.f4, 'fresh_log', return_value='Shutdown completed. Shutdown completed. Graceful shutdown complete')):
            stack.enter_context(item)
        sql = stack.enter_context(patch.object(f17.f3, 'sql', return_value='0'))
        start = stack.enter_context(patch.object(f17.f4, 'start_ready_children', return_value=(object(), object(), 'http://app', 'http://stub', 1, 0)))
        stop = stack.enter_context(patch.object(f17.f3, 'stop_owned_child', return_value=0))
        cleanup = stack.enter_context(patch.object(f17, 'cleanup_business_rows'))
        yield evidence, requests, sql, start, stop, cleanup


class F17GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_actions__then_refuse_before_resources(self):
        for action in (f17.prepare, f17.require_owner, f17.require_fixture, f17.prepare_business_rows,
                       lambda: f17.cleanup_business_rows(f17.IDS), f17.smoke, f17.verify_results, f17.verified_ddl, f17.load_tokens):
            with self.subTest(action=action), patch.dict(os.environ, {}, clear=True), patch.object(f17.f3, 'sql') as sql, \
                    patch.object(f17.f2, 'command') as command, patch.object(f17.subprocess, 'check_output') as git, \
                    patch.object(f17.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'): action()
                sql.assert_not_called(); command.assert_not_called(); git.assert_not_called(); start.assert_not_called()

    def test_given_wrong_target_or_reader__when_preparing__then_no_command_or_source_access(self):
        for changed in ({'F2_TEST_JDBC_URL': 'jdbc:postgresql://production:5432/pawbridge_f2_ci'}, {'F2_TEST_DB_USER': 'postgres'}):
            with patch.dict(os.environ, dict(HOSTED, **changed), clear=True), patch.object(f17.f2, 'command') as command, \
                    patch.object(f17, 'verified_ddl') as source:
                with self.assertRaises(SystemExit): f17.prepare()
                command.assert_not_called(); source.assert_not_called()

    def test_given_invalid_f16_chain__when_preparing__then_no_source_export_or_mutation(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f17.f16, 'require_fixture', side_effect=SystemExit('F16 mismatch')), \
                patch.object(f17, 'verified_ddl') as source, patch.object(f17.f3, 'sql') as sql, patch.object(f17.f2, 'export_environment') as export:
            with self.assertRaisesRegex(SystemExit, 'F16 mismatch'): f17.prepare()
            source.assert_not_called(); sql.assert_not_called(); export.assert_not_called()

    def test_given_owned_fixture__when_extending__then_preserve_every_prior_row_before_success(self):
        events = []
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f17, 'EVIDENCE', Path(directory)), \
                patch.object(f17.f16, 'require_fixture', side_effect=lambda: events.append('F16') or '123-1'), \
                patch.object(f17, 'verified_ddl', side_effect=lambda: events.append('source') or ('DDL', 'hashes')), \
                patch.object(f17.f2, 'export_environment', side_effect=lambda values: events.append('owner')) as export, \
                patch.object(f17.f16, 'database_snapshot', side_effect=lambda: events.append('snapshot') or {'old': []}), \
                patch.object(f17.f3, 'sql', side_effect=lambda **kwargs: events.append('DDL')), \
                patch.object(f17, 'require_fixture', side_effect=lambda: events.append('F17') or '123-1'):
            f17.prepare()
            self.assertEqual(['F16', 'source', 'owner', 'snapshot', 'DDL', 'snapshot', 'F17'], events)
            export.assert_called_once_with({'F17_CI_OWNER': '123-1'})
            self.assertEqual('hashes', (Path(directory) / 'f17-ddl-sha256.txt').read_text())

    def test_given_prior_snapshot_change__when_extending__then_do_not_report_success(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f17.f16, 'require_fixture', return_value='123-1'), \
                patch.object(f17, 'verified_ddl', return_value=('DDL', 'hashes')), patch.object(f17.f2, 'export_environment'), \
                patch.object(f17.f16, 'database_snapshot', side_effect=[{'old': []}, {'old': ['changed']}]), \
                patch.object(f17.f3, 'sql'), patch.object(f17, 'require_fixture') as success:
            with self.assertRaisesRegex(SystemExit, 'changed existing business rows'): f17.prepare()
            success.assert_not_called()

    def test_given_actual_pinned_migrations__when_verifying__then_three_original_tables_one_index_and_minimal_select(self):
        sources = {source: pinned_source(source) for source in f17.DDL_HASHES}
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f17.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]):
            ddl, evidence = f17.verified_ddl()
        self.assertEqual(4, ddl.count('CREATE TABLE ')); self.assertEqual(1, ddl.count('CREATE INDEX '))
        self.assertEqual(2, ddl.count('GRANT SELECT(')); self.assertIn('source_sha=' + f17.SOURCE, evidence)
        for source, digest in f17.DDL_HASHES.items(): self.assertIn(digest + '  ' + source, evidence)
        for text in ('REFERENCES pawbridge_user.users(user_id) ON DELETE CASCADE', 'DEFAULT CURRENT_TIMESTAMP',
                     'PRIMARY KEY (member_id, blocked_id)', 'CHECK (member_id <> blocked_id)', 'CHECK (member_id > 0)',
                     'GRANT SELECT(user_id) ON pawbridge_user.contact_deletions',
                     'GRANT SELECT(member_id,blocked_id) ON pawbridge_community.private_note_blocks'):
            self.assertIn(text, ddl)
        for text in ('private_notes (', 'private_note_mailboxes', 'private_note_requests', 'idx_private_note_reply_to',
                     'pawbridge_f3_writer', 'CREATE SEQUENCE', 'GRANT SELECT ON', 'UPDATE ', 'DELETE FROM', 'TRUNCATE', 'DROP '):
            self.assertNotIn(text, ddl)

    def test_given_tampered_source_or_fragment__when_verifying__then_refuse_before_mutation(self):
        sources = {source: pinned_source(source) for source in f17.DDL_HASHES}
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f17.subprocess, 'check_output', return_value=b'tampered'), \
                patch.object(Path, 'read_text') as read:
            with self.assertRaisesRegex(SystemExit, 'checksum mismatch'): f17.verified_ddl()
            read.assert_not_called()
        raw = (f17.ROOT / 'scripts/f17_fixture.sql').read_text()
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True):
            root = Path(directory); (root / 'scripts').mkdir(); (root / 'scripts/f17_fixture.sql').write_text(raw.replace('ON DELETE CASCADE', 'ON DELETE RESTRICT', 1))
            with patch.object(f17, 'ROOT', root), patch.object(f17.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]):
                with self.assertRaisesRegex(SystemExit, 'differs from pinned'): f17.verified_ddl()

    def test_given_sparse_checkout_without_legacy_dirs__when_reading_inputs__then_use_hash_pinned_git_objects(self):
        marker = f17.ROOT.parent / '.git'
        git_dir = Path(marker.read_text().removeprefix('gitdir:').strip()) if marker.is_file() else marker
        if not git_dir.is_absolute(): git_dir = (marker.parent / git_dir).resolve()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / '.git').write_text('gitdir: ' + str(git_dir.resolve()) + '\n')
            (root / 'backend-app').mkdir()
            for source in (*f17.DDL_HASHES, *oracle.EXPECTED): self.assertTrue(pinned_source(source, root))
            self.assertFalse((root / 'user-service').exists()); self.assertFalse((root / 'community-service').exists())
            with patch.object(subprocess, 'check_output', return_value=b'changed'):
                with self.assertRaisesRegex(AssertionError, 'checksum mismatch'): pinned_source(next(iter(f17.DDL_HASHES)), root)

    def test_given_wrong_owner_marker_shape_acl_or_constraints__when_checking__then_refuse_with_select_only(self):
        with patch.dict(os.environ, dict(HOSTED, F17_CI_OWNER='foreign'), clear=True), \
                patch.object(f17.f16, 'require_fixture', return_value='123-1'), patch.object(f17.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'owner mismatch'): f17.require_fixture()
            sql.assert_not_called()
        valid = [f17.MARKER + ':123-1'] + ['t'] * 9
        for index in range(len(valid)):
            values = valid.copy(); values[index] = 'foreign' if index == 0 else 'f'
            with self.subTest(index=index), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f17.f16, 'require_fixture', return_value='123-1'), patch.object(f17.f3, 'sql', side_effect=values) as sql:
                with self.assertRaises(SystemExit): f17.require_fixture()
                self.assertTrue(all(c.args[0].startswith('SELECT ') for c in sql.call_args_list))

    def test_given_exact_catalog__when_checking__then_enforce_six_columns_minimal_acl_and_real_constraints(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f17.f16, 'require_fixture', return_value='123-1') as parent, \
                patch.object(f17.f3, 'sql', side_effect=[f17.MARKER + ':123-1'] + ['t'] * 9) as sql:
            self.assertEqual('123-1', f17.require_fixture()); parent.assert_called_once_with()
        queries = [c.args[0] for c in sql.call_args_list]
        self.assertIn('CURRENT_TIMESTAMP', queries[1]); self.assertIn('is_generated', queries[1])
        for query in (queries[1], queries[4], queries[7]): self.assertIn("pg_get_userbyid(relowner)='postgres'", query)
        self.assertIn("column_name IN ('user_id')", queries[2]); self.assertIn("=(false)", queries[5])
        self.assertIn("column_name IN ('member_id','blocked_id')", queries[8])
        for query in (queries[2], queries[5], queries[8]):
            self.assertIn('TRUNCATE,REFERENCES,TRIGGER', query); self.assertIn('pg_get_serial_sequence', query)
            self.assertNotIn('has_sequence_privilege', query)
        constraints = '\n'.join((queries[3], queries[6], queries[9]))
        for text in ('convalidated', "confdeltype='c'", 'NOT condeferrable', 'NOT condeferred', 'conkey=ARRAY',
                     'confkey=ARRAY', 'member_id>0', 'member_id<>blocked_id', 'idx_private_note_blocked_member',
                     'i.indisvalid', 'i.indisready', "a.amname='btree'"):
            self.assertIn(text, constraints)

    def test_given_catalog_failure__when_diagnosing__then_bound_mask_and_omit_command_stdout_detail_and_sql(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(f17, 'EVIDENCE', Path(directory)), \
                patch.dict(os.environ, {'F3_TEST_JWT_SECRET': 'hidden-secret'}, clear=True), \
                patch.object(f17.f3, 'sql', side_effect=subprocess.CalledProcessError(1, ['private-command'], output='private-stdout', stderr='ERROR: hidden-secret ' + 'x' * 800 + '\nDETAIL: private-detail\nSTATEMENT: private-sql')):
            with self.assertRaisesRegex(SystemExit, 'bounded'): f17.fixture_catalog('SELECT true', 'acl')
            text = (Path(directory) / 'f17-fixture-catalog-error.txt').read_text()
            self.assertLess(len(text), 600); self.assertIn('stage=f17-recipient-acl', text); self.assertIn('[redacted]', text)
            for secret in ('hidden-secret', 'private-command', 'private-stdout', 'private-detail', 'private-sql'): self.assertNotIn(secret, text)
        for query, stage in (('DELETE FROM x', 'acl'), ('SELECT true', 'mutation')):
            with self.assertRaises(ValueError): f17.fixture_catalog(query, stage)

    def test_given_prior_snapshot__when_extending_snapshot__then_preserve_all_old_tables_and_add_all_three(self):
        with patch.object(f17.f16, 'database_snapshot', return_value={'daily_visit_count': ['old'], 'favorites': ['old']}), \
                patch.object(f17.f3, 'sql', side_effect=['[1]', '[2]', '[3]']) as sql:
            result = f17.database_snapshot()
        self.assertEqual(['old'], result['daily_visit_count']); self.assertEqual(['old'], result['favorites'])
        self.assertEqual([[1], [2], [3]], [result[name] for _, name in f17.TABLES])
        self.assertTrue(all('SELECT COALESCE(json_agg' in c.args[0] for c in sql.call_args_list))

    def test_given_owned_principals__when_seeding_and_cleaning__then_atomic_insert_and_narrow_fk_order_without_user42(self):
        with patch.object(f17, 'require_fixture', return_value='123-1'), patch.object(f17, 'require_owner', return_value='123-1') as owner, \
                patch.object(f17.f3, 'sql') as sql:
            owned = f17.prepare_business_rows(); f17.cleanup_business_rows(owned)
        queries = [c.args[0] for c in sql.call_args_list]
        self.assertEqual(f17.IDS, owned); self.assertEqual(4, owner.call_count)
        self.assertIn('BEGIN;', queries[0]); self.assertIn('COMMIT;', queries[0]); self.assertNotIn('ON CONFLICT', queries[0])
        self.assertEqual(['private_note_blocks', 'contact_deletions', 'private_note_members', 'users'], [q.split('DELETE FROM ', 1)[1].split()[0].split('.')[1] for q in queries[1:]])
        self.assertIn(' AND blocked_id IN ', queries[1]); self.assertIn(' AND email=', queries[4])
        self.assertNotRegex('\n'.join(queries), r'\b42\b|UPDATE |TRUNCATE|DROP ')
        with patch.object(f17, 'require_owner') as owner, patch.object(f17.f3, 'sql') as sql:
            with self.assertRaises(ValueError): f17.cleanup_business_rows((42, f17.OTHER))
            owner.assert_not_called(); sql.assert_not_called()

    def test_given_token_path_symlink_or_mode_mismatch__when_loading__then_refuse_before_read(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / 'f3-tokens-123-1.json'; path.write_text('{}'); path.chmod(0o600)
            env = dict(HOSTED, RUNNER_TEMP=str(root), F3_TEST_TOKENS_PATH=str(path))
            alias = root / 'alias.json'; alias.symlink_to(path)
            for changed in ({'F3_TEST_TOKENS_PATH': str(root / 'foreign.json')}, {'F3_TEST_TOKENS_PATH': str(alias)}):
                with patch.dict(os.environ, dict(env, **changed), clear=True), self.assertRaises(SystemExit): f17.load_tokens()
            path.chmod(0o644)
            with patch.dict(os.environ, env, clear=True), self.assertRaisesRegex(SystemExit, '0600'): f17.load_tokens()

    def test_given_final_token_map__when_loading__then_validate_only_two_used_principals_and_expired_without_rewriting_old_keys(self):
        now = time.time()
        valid = {'F17_CALLER': token(f17.CALLER, now + 600), 'F17_OTHER': token(f17.OTHER, now + 600),
                 'expired': token(f17.CALLER, now - 60), 'ROLE_USER': token(42, now - 9999), 'F14_OTHER': 'unused-and-stale'}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / 'f3-tokens-123-1.json'
            with patch.dict(os.environ, dict(HOSTED, RUNNER_TEMP=str(root), F3_TEST_TOKENS_PATH=str(path)), clear=True):
                for content in ({}, dict(valid, F17_CALLER=token(f17.CALLER, now + 30)), dict(valid, F17_OTHER=token(f17.CALLER, now + 600)),
                                dict(valid, F17_OTHER=token(f17.OTHER, now + 600, 'ROLE_ADMIN')), dict(valid, F17_CALLER=token(float(f17.CALLER), now + 600)),
                                dict(valid, expired=token(42, now - 60)), dict(valid, expired=token(f17.CALLER, now + 600)), dict(valid, F17_CALLER=7)):
                    path.write_text(json.dumps(content)); path.chmod(0o600)
                    with self.assertRaises(SystemExit): f17.load_tokens()
                path.write_text(json.dumps(valid)); path.chmod(0o600); before = path.read_bytes()
                self.assertEqual({key: valid[key] for key in ('F17_CALLER', 'F17_OTHER', 'expired')}, f17.load_tokens())
                self.assertEqual(before, path.read_bytes())

    def test_given_missing_empty_failed_skipped_or_inconsistent_xml__when_reporting__then_require_exact_two_executed_classes(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f17, 'ROOT', Path(directory)):
            root = Path(directory) / 'boot/build/test-results' / f17.TASK; root.mkdir(parents=True)
            def valid(name): return '<testsuite name="' + name + '" tests="1" failures="0" errors="0" skipped="0"><testcase classname="' + name + '" name="contract"/></testsuite>'
            with self.assertRaises(SystemExit): f17.verify_results()
            for name in f17.CLASSES: (root / ('TEST-' + name + '.xml')).write_text(valid(name))
            path = root / ('TEST-' + f17.CLASSES[0] + '.xml'); good = valid(f17.CLASSES[0])
            for text in ('broken', good.replace('tests="1"', 'tests="0"'), good.replace('errors="0"', 'errors="1"'),
                         good.replace('skipped="0"', 'skipped="1"'), good.replace('classname="com.', 'classname="wrong.'),
                         *(good.replace('/></testsuite>', '><' + tag + '/></testcase></testsuite>') for tag in ('failure', 'error', 'skipped'))):
                path.write_text(text)
                with self.assertRaises(SystemExit): f17.verify_results()
            path.write_text(good); extra = root / 'TEST-extra.xml'; extra.write_text(valid('extra'))
            with self.assertRaisesRegex(SystemExit, 'exact two-class'): f17.verify_results()
            extra.unlink(); self.assertEqual(2, len(f17.verify_results()))

    def test_given_mocked_recipient_journey__when_running__then_source_specific_errors_four_fields_snapshots_and_two_pool_receipt(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup):
            f17.smoke(); result = json.loads((evidence / 'f17-packaged-result.txt').read_text())
            self.assertEqual('passed', result['status']); self.assertEqual(16, result['recipient_get_checks'])
            self.assertEqual(4, result['successful_checks']); self.assertEqual(12, result['rejected_checks']); self.assertEqual(2, result['denied_route_checks'])
            self.assertEqual(18, len(requests)); self.assertEqual(1, start.call_count); self.assertEqual(2, stop.call_count)
            cleanup.assert_called_once_with(f17.IDS); self.assertEqual(2, result['shutdown_pools']); self.assertEqual(0, result['remaining_application_connections'])
            self.assertEqual('4', start.call_args.args[1]['F2_POOL_MAX_SIZE'])
            self.assertNotIn('Authorization', requests[12][1]['headers']); self.assertIn('not-a-number', requests[12][0])
            self.assertIn('/0?', requests[10][0]); self.assertEqual('POST', requests[17][1]['method'])
            self.assertEqual(f17.PATH + str(f17.OTHER) + '/extra', requests[16][0])
            self.assertEqual('GET', requests[16][1]['method'])
            self.assertNotIn('/api/v1/notes', [path for path, _ in requests])
            self.assertTrue(all('/api/v1/notes' in p for p, _ in requests))

    def test_given_request_failure_during_revoked_acl__when_unwinding__then_restore_grant_stop_both_and_cleanup_owned_keys(self):
        for number, column in ((9, 'user_id'), (11, 'member_id')):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, failure=number) as (evidence, requests, sql, start, stop, cleanup):
                with self.assertRaisesRegex(AssertionError, 'mocked request failed'): f17.smoke()
                queries = '\n'.join(c.args[0] for c in sql.call_args_list)
                self.assertIn('GRANT SELECT(' + column + ')', queries)
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f17.IDS)
                self.assertEqual('incomplete', json.loads((evidence / 'f17-packaged-result.txt').read_text())['status'])

    def test_given_wrong_cache_cookie_db_or_s3_mutation__when_observing_request__then_fail_and_cleanup(self):
        for fault in ({'cache': 'public'}, {'cookie': True}, {'mutation': True}, {'storage_mutation': True}):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **fault) as (_, _, _, _, stop, cleanup):
                with self.assertRaises(AssertionError): f17.smoke()
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f17.IDS)

    def test_given_workflow__when_extending_f17__then_preserve_f16_order_one_utc_task_and_always_cleanup(self):
        workflow = (f17.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('- name: Verify F17 staged ownership')
        self.assertLess(workflow.index('python3 scripts/f16_ci.py smoke'), boundary)
        stage = workflow[boundary:]
        for first, second in (('test_f17_ci_guards.py', 'python3 scripts/f17_ci.py prepare'),
                              ('python3 scripts/f17_ci.py prepare', ':boot:noteRecipientCompatibilityTest'),
                              (':boot:noteRecipientCompatibilityTest', 'python3 scripts/f17_ci.py results'),
                              ('python3 scripts/f17_ci.py results', 'python3 scripts/f17_ci.py smoke')):
            self.assertLess(stage.index(first), stage.index(second))
        self.assertEqual(1, stage.count(':boot:noteRecipientCompatibilityTest')); self.assertNotIn('noteRecipientCompatibilitySeoulTest', workflow)
        cleanup = stage.index('- name: Remove only the owned disposable fixture')
        self.assertIn('if: always()', stage[cleanup:cleanup + 180]); self.assertIn('python3 scripts/f3_ci.py cleanup', stage[cleanup:cleanup + 180])
        self.assertNotRegex(stage[:cleanup], r'python3 scripts/f(?:[2-9]|1[0-6])_ci\.py (?:prepare|smoke)')
        for required in ('f17-oracle-sha256.txt', 'f17-ddl-sha256.txt', 'f17-helper-refusal.txt'): self.assertIn(required, workflow)

    def test_given_f17_test_artifacts__when_applying_jar_filter__then_reject_without_changing_validator_exception(self):
        text = (f17.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        denied = next(line for line in text.splitlines() if "if grep -E '" in line).split("if grep -E '", 1)[1].split("' build/ci/", 1)[0]
        allowed = next(line for line in text.splitlines() if "| grep -Ev '" in line).split("| grep -Ev '", 1)[1].split("'; then", 1)[0]
        for path in ('BOOT-INF/classes/f17_fixture.sql', 'BOOT-INF/classes/f17LegacyOracle/Input.class',
                     'BOOT-INF/classes/com/pawbridge/communityservice/contact/PrivateNoteRepository.class', 'test/f17LegacyOracle/hibernate-validator-8.0.3.Final.jar'):
            self.assertIsNotNone(re.search(denied, path)); self.assertIsNone(re.search(allowed, path))
        self.assertIsNotNone(re.search(allowed, 'BOOT-INF/lib/hibernate-validator-8.0.3.Final.jar'))

    def test_given_wrong_oracle_paths__when_extracting__then_refuse_before_git_or_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory)
            for target, report in ((root / 'outside', evidence), (output, root / 'outside')):
                with patch.object(oracle.subprocess, 'check_output') as git, patch.object(Path, 'mkdir') as mkdir, patch.object(Path, 'write_bytes') as write:
                    with self.assertRaisesRegex(SystemExit, 'non-dedicated F17'): oracle.extract(root, target, report)
                    git.assert_not_called(); mkdir.assert_not_called(); write.assert_not_called()

    def test_given_source_or_method_hash_tamper__when_extracting__then_write_nothing(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        for mode in ('source', 'method'):
            with tempfile.TemporaryDirectory() as directory:
                root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
                changed = dict(sources)
                if mode == 'source': changed[oracle.COMMUNITY + 'com/pawbridge/communityservice/contact/PrivateNoteService.java'] = b'changed-service'
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: changed[args[-1].split(':', 1)[1]]), \
                        patch.dict(oracle.METHODS, {key: 'bad' for key in oracle.METHODS} if mode == 'method' else oracle.METHODS, clear=True), \
                        patch.object(Path, 'write_bytes') as write:
                    with self.assertRaises(SystemExit): oracle.extract(root, output, evidence)
                    write.assert_not_called(); self.assertFalse(evidence.exists())

    def test_given_shared_f2_bytes_tamper__when_extracting__then_preserve_shared_and_new_outputs(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
            source = oracle.SHARED[0]
            target = root / 'backend-app/boot/build/generated/sources/f2LegacyOracle/java' / source[len(oracle.COMMUNITY):]
            target.write_bytes(b'preserve-other-writer')
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]):
                with self.assertRaisesRegex(SystemExit, 'Shared F2'): oracle.extract(root, output, evidence)
            self.assertEqual(b'preserve-other-writer', target.read_bytes()); self.assertFalse(output.exists()); self.assertFalse(evidence.exists())

    def test_given_differing_late_output__when_extracting__then_preserve_every_existing_output(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
            target = output / 'com/pawbridge/communityservice/contact/F17LegacyRecipientService.java'
            target.parent.mkdir(parents=True); target.write_bytes(b'preserve-existing')
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]):
                with self.assertRaisesRegex(SystemExit, 'overwrite differing'): oracle.extract(root, output, evidence)
            self.assertEqual([target], list(output.rglob('*.java'))); self.assertEqual(b'preserve-existing', target.read_bytes()); self.assertFalse(evidence.exists())

    def test_given_nine_actual_sources__when_extracting__then_copy_three_and_six_verbatim_methods_without_duplicate_f2(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]) as git:
                oracle.extract(root, output, evidence)
            self.assertEqual(9, len(oracle.EXPECTED)); self.assertEqual(6, len(oracle.METHODS)); self.assertEqual(4, len(list(output.rglob('*.java'))))
            self.assertTrue(all(c.args[0][2].startswith(oracle.SOURCE + ':') for c in git.call_args_list))
            self.assertIn('source_sha=' + oracle.SOURCE, evidence.read_text())
            for source in oracle.COPIED:
                prefix = oracle.USER if source.startswith(oracle.USER) else oracle.COMMUNITY
                self.assertEqual(sources[source], (output / source[len(prefix):]).read_bytes())
            wrapper = (output / 'com/pawbridge/communityservice/contact/F17LegacyRecipientService.java').read_text()
            service = sources[oracle.COMMUNITY + 'com/pawbridge/communityservice/contact/PrivateNoteService.java'].decode()
            for signature, digest in oracle.METHODS.items(): self.assertIn(oracle.verified_method(service, signature, digest), wrapper)
            self.assertIn('catch (FeignException', wrapper); self.assertIn('failure.status() == 404', wrapper)
            for source in oracle.SHARED: self.assertFalse((output / source[len(oracle.COMMUNITY):]).exists())
            for name in ('PrivateNoteController.java', 'PrivateNoteExceptionAdvice.java', 'PrivateNoteHttpBoundary.java'):
                self.assertFalse((output / 'com/pawbridge/communityservice/contact' / name).exists())


if __name__ == '__main__':
    print('F17 guards: mocked PostgreSQL/JAR/JVM/sockets; fixed inputs use read-only git show, no Git mutations.', flush=True)
    with redirect_stdout(io.StringIO()): unittest.main()
