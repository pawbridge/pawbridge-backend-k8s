"""F22 guard drafts use resources mocked by the tests, never actual PG/JAR evidence."""
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

import f22_ci as f22
import prepare_f22_legacy_oracle as oracle
from test_f17_ci_guards import HOSTED

MOCK_COUNTS = dict(zip(f22.COUNTERS, (8, 4, 4, 1, 1, 1, 1, 1)))


def token(identity, expiry, role='ROLE_USER'):
    claims = base64.urlsafe_b64encode(json.dumps({'userId': identity, 'role': role, 'exp': expiry}).encode()).decode().rstrip('=')
    return 'synthetic.' + claims + '.signature'


def oracle_sources():
    values = {}
    for source, digest in oracle.EXPECTED.items():
        raw = subprocess.check_output(['git', 'show', oracle.SOURCE + ':' + source], cwd=f22.ROOT.parent)
        if hashlib.sha256(raw).hexdigest() != digest: raise AssertionError('F22 fixed oracle guard input hash differs')
        values[source] = raw
    return values


def shared_sources(root, sources):
    for source, relative in oracle.SHARED.items():
        target = root / relative; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(sources[source])
    text = sources[oracle.ADVICE].decode()
    annotations = oracle.checked_section(text, oracle.ADVICE_ANNOTATIONS)
    (root / oracle.F18_ADAPTED_PATH).write_bytes(text.replace(annotations, oracle.F18_ADAPTED_ANNOTATIONS, 1).encode())


@contextmanager
def packaged_harness(directory, failure=None, mutation=False, storage_mutation=False, startup_mutation=False,
                     cache=None, cookie=False, bad_payload=False, shutdown=None, remaining='0'):
    root = Path(directory).resolve(); evidence = root / 'evidence'
    jar = root / 'boot/build/libs/pawbridge-backend-app.jar'
    jar.parent.mkdir(parents=True); jar.write_bytes(b'mock-only-jar')
    requests, started = [], False
    statuses = {4: 404, 5: 503, 7: 401, 8: 403, 9: 403}
    messages = {4: '쪽지를 찾을 수 없습니다.', 5: f22.STORAGE_UNAVAILABLE,
                7: '인증 토큰이 필요합니다.', 8: '접근할 수 없습니다.', 9: '접근할 수 없습니다.'}

    def request(base, path, **kwargs):
        requests.append((path, kwargs)); number = len(requests)
        if number == failure: raise AssertionError('mocked request failed')
        status = statuses.get(number, 200)
        note = path.rsplit('/', 1)[-1]
        body = ({'code': 200, 'message': f22.SUCCESS, 'data': f22.expected_detail(note)} if status == 200 else
                {'code': status, 'message': messages[number], 'data': None} if number in (7, 8, 9) else {'message': messages[number]})
        if bad_payload and status == 200: body['data']['favorite'] = 'true'
        headers = {'Cache-Control': cache if cache is not None else f22.f19.CACHE_CONTROL if number in (7, 8, 9) else 'no-store'}
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
        for item in (patch.dict(os.environ, env, clear=True), patch.object(f22, 'ROOT', root), patch.object(f22, 'EVIDENCE', evidence),
                     patch.object(f22, 'EXPECTED_JAR_COUNTS', MOCK_COUNTS), patch.object(f22, 'require_fixture', return_value='123-1'),
                     patch.object(f22, 'require_owner', return_value='123-1'), patch.object(f22.f21, 'check_receipt'),
                     patch.object(f22.f21, 'require_f21_receipt'), patch.object(f22.f19, 'require_identity', return_value='123-1'),
                     patch.object(f22, 'load_tokens', return_value={'F22_CALLER': 'private-used-token'}),
                     patch.object(f22, 'database_snapshot', side_effect=lambda: {'state': 'changed' if
                         (mutation and requests) or (startup_mutation and started) else 'same'}),
                     patch.object(f22.f3, 'request', side_effect=request), patch.object(f22.f3, 'expect', side_effect=storage),
                     patch.object(f22.f4, 'fresh_log', return_value=shutdown if shutdown is not None else
                                  'Shutdown completed. Shutdown completed. Graceful shutdown complete')):
            stack.enter_context(item)
        sql = stack.enter_context(patch.object(f22.f3, 'sql', side_effect=lambda query: remaining if query.endswith("'pawbridge_f3_writer')") else '0'))
        seed = stack.enter_context(patch.object(f22, 'prepare_business_rows', return_value=f22.OWNED))
        start_mock = stack.enter_context(patch.object(f22.f4, 'start_ready_children', side_effect=start))
        stop = stack.enter_context(patch.object(f22.f3, 'stop_owned_child', return_value=0))
        cleanup = stack.enter_context(patch.object(f22, 'cleanup_business_rows'))
        yield evidence, requests, sql, start_mock, stop, cleanup, seed


class F22GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_actions__then_refuse_before_sql_or_children(self):
        actions = (f22.prepare, f22.require_fixture, f22.require_owner, f22.prepare_business_rows,
                   lambda: f22.cleanup_business_rows(f22.OWNED), f22.load_tokens, f22.verify_results, f22.smoke)
        for action in actions:
            with patch.dict(os.environ, {}, clear=True), patch.object(f22.f3, 'sql') as sql, \
                    patch.object(f22.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'): action()
                sql.assert_not_called(); start.assert_not_called()

    def test_given_unmeasured_counts__when_results_or_smoke_requested__then_stop_before_resources(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f22, 'EXPECTED_JAR_COUNTS', None), \
                patch.object(f22, 'REPORTS', {f22.TASK: {f22.PG_CLASS: 24, f22.MVC_CLASS: None}, f22.SEOUL_TASK: {f22.PG_CLASS: 1}}), \
                patch.object(f22, 'require_fixture') as fixture, patch.object(f22.f4, 'start_ready_children') as start:
            for action in (f22.verify_results, f22.smoke):
                with self.assertRaisesRegex(SystemExit, 'unmeasured'): action()
            fixture.assert_not_called(); start.assert_not_called()

    def test_given_missing_foreign_or_redirected_receipt__when_reading_owner__then_refuse_without_mutation(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(f22, 'EVIDENCE', Path(directory).resolve()), \
                patch.object(f22.f21, 'require_owner', return_value='123-1'), patch.object(f22.f21, 'require_f21_receipt'), patch.object(f22.f3, 'sql') as sql:
            target = f22.EVIDENCE / 'f22-fixture-reuse.txt'
            with self.assertRaises(SystemExit): f22.require_owner()
            target.write_text(f22.fixture_proof('foreign'))
            with self.assertRaises(SystemExit): f22.require_owner()
            target.unlink(); other = f22.EVIDENCE / 'other'; other.write_text('preserve'); target.symlink_to(other)
            with self.assertRaises(SystemExit): f22.require_owner()
            sql.assert_not_called()

    def test_given_old_fixture_receipt_or_snapshot_failure__when_preparing__then_no_grants_or_receipt_writes(self):
        for phase in ('old', 'receipt', 'snapshot'):
            with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f22, 'EVIDENCE', Path(directory).resolve()), \
                    patch.object(f22.f21, 'require_fixture', return_value='123-1', side_effect=SystemExit('old') if phase == 'old' else None), \
                    patch.object(f22.f21, 'check_receipt', side_effect=SystemExit('receipt') if phase == 'receipt' else None), \
                    patch.object(f22, 'database_snapshot', side_effect=SystemExit('snapshot') if phase == 'snapshot' else None), \
                    patch.object(f22, 'owned_sql') as sql, patch.object(Path, 'write_text') as write:
                with self.assertRaises(SystemExit): f22.prepare()
                sql.assert_not_called(); write.assert_not_called()

    def test_given_valid_f21_fixture__when_preparing__then_check_old_once_before_atomic_five_column_grant_without_ddl(self):
        events = []; before = {str(index): [] for index in range(16)}
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f22, 'EVIDENCE', Path(directory).resolve()), \
                patch.object(f22.f21, 'require_fixture', side_effect=lambda: events.append('old') or '123-1') as old, \
                patch.object(f22.f21, 'check_receipt', side_effect=lambda *a, **kw: events.append('receipt')), \
                patch.object(f22, 'database_snapshot', side_effect=lambda: events.append('snapshot') or before), \
                patch.object(f22, 'owned_sql', side_effect=lambda **kw: events.append('grant')) as sql, \
                patch.object(f22, 'require_fixture', side_effect=lambda: events.append('new') or '123-1'):
            f22.prepare()
        self.assertEqual(['old', 'receipt', 'snapshot', 'grant', 'new', 'snapshot'], events); old.assert_called_once()
        self.assertEqual('BEGIN;\nGRANT SELECT(recipient_id,body,context_type,context_id) ON pawbridge_community.private_notes TO pawbridge_f2_reader;\n'
                         'GRANT SELECT(favorite) ON pawbridge_community.private_note_mailboxes TO pawbridge_f2_reader;\nCOMMIT;\n', sql.call_args.kwargs['input_text'])

    def test_given_extended_fixture__when_checking__then_exact_original_shapes_acl_constraints_indexes_without_old_notes_acl(self):
        original = dict(f22.f21.READABLE)
        with patch.object(f22, 'require_owner', return_value='123-1'), patch.object(f22.f20, 'require_fixture', return_value='123-1'), \
                patch.object(f22.f21, 'require_fixture', side_effect=AssertionError('old strict must not run')), patch.object(f22, 'catalog', return_value='t') as catalog:
            self.assertEqual('123-1', f22.require_fixture())
        self.assertEqual(original, f22.f21.READABLE); self.assertEqual(6, catalog.call_count)
        queries = '\n'.join(call.args[0] for call in catalog.call_args_list)
        for value in ('count(*)=9', 'count(*)=6', "'reply_to'", "'favorite'", "'pawbridge_f3_writer'", 'pg_get_serial_sequence', 'pg_constraint', 'pg_index', 'postgres', 'indisvalid'):
            self.assertIn(value, queries)
        allowed = next(call.args[0] for call in catalog.call_args_list if call.args[1] == 'acl')
        selected = allowed.split('column_name IN (', 1)[1].split(')', 1)[0]
        self.assertIn("'body'", selected); self.assertNotIn("'reply_to'", selected)

    def test_given_any_shape_acl_constraint_or_index_mismatch__when_checking__then_fail_closed(self):
        for position in range(6):
            values = ['t'] * 6; values[position] = 'f'
            with patch.object(f22, 'require_owner', return_value='123-1'), patch.object(f22.f20, 'require_fixture', return_value='123-1'), patch.object(f22, 'catalog', side_effect=values):
                with self.assertRaises(SystemExit): f22.require_fixture()

    def test_given_inherited_owner_changes__when_checking__then_no_new_catalog_or_write(self):
        with patch.object(f22, 'require_owner', return_value='123-1'), patch.object(f22.f20, 'require_fixture', return_value='foreign'), patch.object(f22, 'catalog') as catalog:
            with self.assertRaisesRegex(SystemExit, 'owner changed'): f22.require_fixture()
            catalog.assert_not_called()

    def test_given_catalog_failure__when_diagnosing__then_bounded_first_primary_masks_secrets_without_sql_detail_or_trace(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(f22, 'EVIDENCE', Path(directory).resolve()), \
                patch.dict(os.environ, {'F22_PASSWORD': 'private-password', 'F22_TOKEN': 'private-token'}, clear=True), \
                patch.object(f22.f3, 'sql', side_effect=subprocess.CalledProcessError(1, ['do-not-log-command'], output='private stdout',
                    stderr='psql header\nERROR: private-password private-token ' + 'x' * 800 + '\nDETAIL: private body\nSQL: private query\nFATAL: second primary')):
            with self.assertRaisesRegex(SystemExit, 'bounded'): f22.catalog('SELECT metadata', 'acl')
            receipt = (f22.EVIDENCE / 'f22-fixture-catalog-error.txt').read_text()
        self.assertIn('stage=f22-private-note-acl', receipt); self.assertIn('[redacted]', receipt)
        self.assertLessEqual(len(receipt.split('stderr=', 1)[1].strip()), 512)
        for value in ('private-password', 'private-token', 'private body', 'private query', 'private stdout', 'do-not-log-command', 'second primary'): self.assertNotIn(value, receipt)

    def test_given_incomplete_snapshot__when_comparing__then_refuse_instead_of_omitting_existing_tables(self):
        with patch.object(f22.f21, 'database_snapshot', return_value={str(i): [] for i in range(15)}):
            with self.assertRaisesRegex(SystemExit, 'sixteen'): f22.database_snapshot()

    def test_given_body_denial_and_request_failure__when_unwinding__then_identity_and_all_receipts_precede_grant_and_strict_after_restore(self):
        events = []
        with patch.object(f22, 'require_fixture', side_effect=lambda: events.append('strict') or '123-1'), \
                patch.object(f22, 'owned_sql', side_effect=lambda query: events.append(query.split()[0])), \
                patch.object(f22.f19, 'require_identity', side_effect=lambda owner: events.append('identity')), \
                patch.object(f22.f21, 'check_receipt', side_effect=lambda path, proof: events.append(path.name)), \
                patch.object(f22.f21, 'require_f21_receipt', side_effect=lambda owner: events.append('f21')):
            with self.assertRaisesRegex(AssertionError, 'request'):
                with f22.denied_body(): raise AssertionError('request')
        self.assertEqual(['strict', 'REVOKE', 'identity', 'f20-fixture-reuse.txt', 'f21', 'f22-fixture-reuse.txt', 'GRANT', 'strict'], events)

    def test_given_changed_owner_under_revocation__when_unwinding__then_no_restore_sql_or_strict_check(self):
        with patch.object(f22, 'require_fixture', return_value='123-1') as strict, \
                patch.object(f22.f19, 'require_identity', side_effect=SystemExit('foreign owner')), patch.object(f22, 'owned_sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'foreign owner'):
                with f22.denied_body(): pass
            self.assertEqual(1, sql.call_count); self.assertTrue(sql.call_args.args[0].startswith('REVOKE ')); strict.assert_called_once()

    def test_given_synthetic_key_collision__when_seeding__then_no_insert_or_owned_success(self):
        with patch.object(f22, 'require_fixture'), patch.object(f22, 'owned_sql', return_value='1') as sql:
            with self.assertRaisesRegex(SystemExit, 'must all be absent'): f22.prepare_business_rows()
            self.assertEqual(1, sql.call_count); self.assertTrue(sql.call_args.args[0].startswith('SELECT '))

    def test_given_absent_keys__when_seeding__then_one_atomic_owned_users_members_notes_mailboxes_without_blocks_or_requests(self):
        with patch.object(f22, 'require_fixture'), patch.object(f22, 'owned_sql', side_effect=['0', '']) as sql:
            self.assertEqual(f22.OWNED, f22.prepare_business_rows())
        statement = sql.call_args.args[0]
        self.assertTrue(statement.startswith('BEGIN;')); self.assertTrue(statement.endswith('COMMIT;'))
        self.assertEqual(4, statement.count('INSERT INTO ')); self.assertIn(f22.BODY.replace("'", "''"), statement)
        for forbidden in ('UPDATE ', 'private_note_blocks', 'private_note_requests', 'contact_deletions', 'SEQUENCE', 'CREATE '): self.assertNotIn(forbidden, statement)

    def test_given_successful_owned_keys__when_cleaning__then_fk_order_and_identity_before_each_exact_key_delete(self):
        events = []
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f22, 'require_owner', side_effect=lambda: events.append('owner')), \
                patch.object(f22, 'owned_sql', side_effect=lambda query: events.append(query)):
            f22.cleanup_business_rows(f22.OWNED)
        self.assertEqual(['owner'] * 4, events[::2])
        for statement, table in zip(events[1::2], ('private_note_mailboxes', 'private_notes', 'private_note_members', 'users')):
            self.assertIn('.' + table + ' WHERE ', statement); self.assertNotIn('TRUNCATE', statement)
        self.assertIn('body=', events[3]); self.assertIn('(user_id,email)', events[-1])

    def test_given_unowned_cleanup_tuple__when_cleaning__then_no_sql(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f22, 'owned_sql') as sql:
            with self.assertRaises(ValueError): f22.cleanup_business_rows(())
            sql.assert_not_called()

    def test_given_used_token_with_old_keys__when_loading__then_regular_0600_owner_identity_role_ttl_only_without_rewrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / 'f3-tokens-123-1.json'
            env = dict(HOSTED, RUNNER_TEMP=str(path.parent), F3_TEST_TOKENS_PATH=str(path)); now = time.time()
            valid = {'F22_CALLER': token(f22.CALLER, now + 600), 'F21_CALLER': 'old-preserved'}
            with patch.dict(os.environ, env, clear=True):
                path.write_text(json.dumps(valid)); path.chmod(0o600); original = path.read_bytes()
                self.assertEqual({'F22_CALLER': valid['F22_CALLER']}, f22.load_tokens()); self.assertEqual(original, path.read_bytes())
                for value in ({}, dict(valid, F22_CALLER=token(f22.CALLER, now + 30)), dict(valid, F22_CALLER=token(f22.OTHER, now + 600)),
                              dict(valid, F22_CALLER=token(f22.CALLER, now + 600, 'ROLE_ADMIN')), dict(valid, F22_CALLER=token(float(f22.CALLER), now + 600))):
                    path.write_text(json.dumps(value))
                    with self.assertRaises(SystemExit): f22.load_tokens()
                path.write_text(json.dumps(valid)); path.chmod(0o644)
                with self.assertRaises(SystemExit): f22.load_tokens()
                path.chmod(0o600); other = path.parent / 'other'; path.rename(other); path.symlink_to(other)
                with self.assertRaises(SystemExit): f22.load_tokens()

    def test_given_xml__when_reporting__then_exact_classes_counts_named_seoul_case_and_no_hidden_child_failures(self):
        reports = {f22.TASK: {f22.PG_CLASS: 2, f22.MVC_CLASS: 2}, f22.SEOUL_TASK: {f22.PG_CLASS: 1}}
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f22, 'ROOT', Path(directory).resolve()), patch.object(f22, 'REPORTS', reports):
            with self.assertRaises(SystemExit): f22.verify_results()
            for task, classes in reports.items():
                root = f22.ROOT / 'boot/build/test-results' / task; root.mkdir(parents=True)
                for name, count in classes.items():
                    cases = ''.join('<testcase classname="' + name + '" name="' + (f22.SEOUL_METHOD + '()' if task == f22.SEOUL_TASK else 'case' + str(i)) + '"/>' for i in range(count))
                    (root / ('TEST-' + name + '.xml')).write_text('<testsuite name="' + name + '" tests="' + str(count) + '" failures="0" errors="0" skipped="0">' + cases + '</testsuite>')
            self.assertEqual(2, len(f22.verify_results()))
            target = f22.ROOT / 'boot/build/test-results' / f22.SEOUL_TASK / ('TEST-' + f22.PG_CLASS + '.xml'); good = target.read_text()
            for bad in (good.replace(f22.SEOUL_METHOD, 'wrong'), good.replace('tests="1"', 'tests="0"'), good.replace('errors="0"', 'errors="1"'),
                        good.replace('classname="com.', 'classname="foreign.'), *(good.replace('/></testsuite>', '><' + tag + '/></testcase></testsuite>') for tag in ('failure', 'error', 'skipped'))):
                target.write_text(bad)
                with self.assertRaises(SystemExit): f22.verify_results()
            target.write_text(good); (target.parent / 'TEST-extra.xml').write_text('unexpected')
            with self.assertRaisesRegex(SystemExit, 'exact UTC/Seoul'): f22.verify_results()

    def test_given_mocked_nine_request_journey__when_observing__then_exact_payload_recovery_after_snapshot_ttl_and_cleanup(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            calls = []; current = f22.load_tokens
            def fresh(): calls.append(len(requests)); return current()
            with patch.object(f22, 'load_tokens', side_effect=fresh): f22.smoke()
            result = json.loads((evidence / 'f22-packaged-result.txt').read_text())
            self.assertEqual('passed', result['status']); self.assertEqual(9, len(requests))
            self.assertEqual(MOCK_COUNTS, {key: result[key] for key in f22.COUNTERS})
            self.assertEqual([0, 0, 1, 2, 3, 4, 5, 7, 8], calls)
            self.assertNotIn('Authorization', requests[6][1]['headers']); self.assertEqual('/api/v1/notes/stream', requests[7][0])
            self.assertEqual('PUT', requests[8][1]['method']); self.assertTrue(requests[8][0].endswith('/read'))
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f22.OWNED)
            self.assertEqual('1', start.call_args.args[1]['F2_POOL_MAX_SIZE']); self.assertNotIn('DAILY_VISITORS_ENABLED', start.call_args.args[1])

    def test_given_stale_token_after_snapshot__when_request_due__then_zero_requests_two_children_stop_owned_cleanup_incomplete(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            state = {'before': False}; events = []; database, tokens = f22.database_snapshot, f22.load_tokens
            def snapshot():
                value = database()
                if seed.called: state['before'] = True; events.append('snapshot')
                return value
            def fresh():
                if state['before']: events.append('stale'); raise SystemExit('stale after snapshot')
                events.append('mask'); return tokens()
            with patch.object(f22, 'database_snapshot', side_effect=snapshot), patch.object(f22, 'load_tokens', side_effect=fresh):
                with self.assertRaisesRegex(SystemExit, 'stale after snapshot'): f22.smoke()
            self.assertEqual(['mask', 'snapshot', 'stale'], events); self.assertEqual([], requests)
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f22.OWNED)
            self.assertEqual('incomplete', json.loads((evidence / 'f22-packaged-result.txt').read_text())['status'])

    def test_given_startup_database_or_request_database_or_s3_mutation__when_observing__then_fail_and_stop_children(self):
        for options in ({'startup_mutation': True}, {'mutation': True}, {'storage_mutation': True}):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **options) as (evidence, requests, sql, start, stop, cleanup, seed):
                with self.assertRaises(AssertionError): f22.smoke()
                self.assertEqual(2, stop.call_count)
                if seed.called: cleanup.assert_called_once_with(f22.OWNED)
                else: cleanup.assert_not_called()
                self.assertEqual('incomplete', json.loads((evidence / 'f22-packaged-result.txt').read_text())['status'])

    def test_given_request_failure_under_body_revocation__when_unwinding__then_restore_grant_stop_children_cleanup_and_never_pass(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, failure=5) as (evidence, requests, sql, start, stop, cleanup, seed):
            with self.assertRaises(AssertionError): f22.smoke()
            statements = [call.args[0] for call in sql.call_args_list]
            self.assertTrue(any(query.startswith('REVOKE SELECT(body)') for query in statements))
            self.assertTrue(any(query.startswith('GRANT SELECT(body)') for query in statements))
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f22.OWNED)
            self.assertEqual('incomplete', json.loads((evidence / 'f22-packaged-result.txt').read_text())['status'])

    def test_given_restore_failure__when_unwinding__then_no_passed_result_but_both_children_and_owned_cleanup(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            original = f22.owned_sql
            def failing(query):
                if query.startswith('GRANT SELECT(body)'): raise SystemExit('restore failed')
                return original(query)
            with patch.object(f22, 'owned_sql', side_effect=failing):
                with self.assertRaisesRegex(SystemExit, 'restore failed'): f22.smoke()
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f22.OWNED)
            self.assertEqual('incomplete', json.loads((evidence / 'f22-packaged-result.txt').read_text())['status'])

    def test_given_failed_seed__when_unwinding__then_stop_children_without_deleting_unclaimed_keys(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            seed.side_effect = SystemExit('insert failed')
            with self.assertRaisesRegex(SystemExit, 'insert failed'): f22.smoke()
            self.assertEqual(2, stop.call_count); cleanup.assert_not_called(); self.assertEqual([], requests)

    def test_given_wrong_cache_cookie_or_payload__when_observing__then_fail_exact_wire_contract(self):
        for options in ({'cache': 'public'}, {'cookie': True}, {'bad_payload': True}):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **options) as (evidence, requests, sql, start, stop, cleanup, seed):
                with self.assertRaises(AssertionError): f22.smoke()
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f22.OWNED)

    def test_given_missing_shutdown_remaining_connections_or_private_log__when_finishing__then_never_mark_passed(self):
        for options in ({'shutdown': 'Graceful shutdown complete'}, {'remaining': '1'}, {'shutdown': 'Shutdown completed. Shutdown completed. Graceful shutdown complete private-reader'}):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **options) as (evidence, requests, sql, start, stop, cleanup, seed):
                with self.assertRaises(AssertionError): f22.smoke()
                self.assertEqual('incomplete', json.loads((evidence / 'f22-packaged-result.txt').read_text())['status'])

    def test_given_wrong_or_redirected_oracle_destination__when_extracting__then_refuse_before_git_or_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); output, evidence = root / oracle.OUTPUT_ROOT, root / oracle.EVIDENCE_PATH
            for target, receipt in ((root / 'wrong', evidence), (output, root / 'wrong')):
                with patch.object(oracle.subprocess, 'check_output') as git, patch.object(Path, 'write_bytes') as write:
                    with self.assertRaises(SystemExit): oracle.extract(root, target, receipt)
                    git.assert_not_called(); write.assert_not_called()
            other = root / 'other'; other.mkdir(); output.parent.mkdir(parents=True); output.symlink_to(other, target_is_directory=True)
            with patch.object(oracle.subprocess, 'check_output') as git, patch.object(Path, 'write_bytes') as write:
                with self.assertRaises(SystemExit): oracle.extract(root, output, evidence)
                git.assert_not_called(); write.assert_not_called()

    def test_given_last_source_tamper__when_extracting__then_all_nineteen_validate_before_any_output(self):
        sources = oracle_sources(); sources[next(reversed(sources))] = b'tampered'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kw: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                with self.assertRaisesRegex(SystemExit, 'checksum mismatch'): oracle.extract(root, root / oracle.OUTPUT_ROOT, root / oracle.EVIDENCE_PATH)
                write.assert_not_called()

    def test_given_raw_or_adapted_shared_tamper__when_extracting__then_preserve_thirteen_inputs_before_output(self):
        sources = oracle_sources()
        for relative in (*oracle.SHARED.values(), oracle.F18_ADAPTED_PATH):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve(); shared_sources(root, sources); (root / relative).write_bytes(b'preserve-tamper')
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kw: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaises(SystemExit): oracle.extract(root, root / oracle.OUTPUT_ROOT, root / oracle.EVIDENCE_PATH)
                    write.assert_not_called(); self.assertEqual(b'preserve-tamper', (root / relative).read_bytes())

    def test_given_selected_method_annotation_logger_or_source_unit_hash_mismatch__when_extracting__then_zero_writes(self):
        sources = oracle_sources()
        specifications = (*oracle.SERVICE_UNITS, oracle.CONTROLLER_UNIT, *oracle.ADVICE_UNITS,
                          oracle.CONTROLLER_ANNOTATIONS, oracle.LOGGER, oracle.SOURCE_EVIDENCE_UNITS[0], oracle.DDL_UNITS[0])
        for specification in specifications:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve(); shared_sources(root, sources)
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kw: sources[args[-1].split(':', 1)[1]]), \
                        patch.dict(specification, {'sha256': 'wrong'}), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaises(SystemExit): oracle.extract(root, root / oracle.OUTPUT_ROOT, root / oracle.EVIDENCE_PATH)
                    write.assert_not_called()

    def test_given_output_receipt_or_duplicate_fqn_conflict__when_extracting__then_no_partial_write_and_existing_bytes_preserved(self):
        sources = oracle_sources()
        for kind in ('output', 'receipt', 'duplicate'):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve(); shared_sources(root, sources)
                output, evidence = root / oracle.OUTPUT_ROOT, root / oracle.EVIDENCE_PATH
                target = output / oracle.OUTPUTS[-1] if kind == 'output' else evidence if kind == 'receipt' else root / 'backend-app/boot/src/test/java/other/F22LegacyPrivateNoteService.java'
                target.parent.mkdir(parents=True, exist_ok=True)
                original = b'preserve-conflict' if kind != 'duplicate' else b'package com.pawbridge.communityservice.contact; public class F22LegacyPrivateNoteService {}'
                target.write_bytes(original)
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kw: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaises(SystemExit): oracle.extract(root, output, evidence)
                    write.assert_not_called(); self.assertEqual(original, target.read_bytes())

    def test_given_all_fixed_inputs__when_extracting__then_three_unique_outputs_keep_twelve_methods_named_path_four_handlers_and_shared_logger(self):
        sources = oracle_sources()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); shared_sources(root, sources)
            output, evidence = root / oracle.OUTPUT_ROOT, root / oracle.EVIDENCE_PATH
            before = {relative: (root / relative).read_bytes() for relative in (*oracle.SHARED.values(), oracle.F18_ADAPTED_PATH)}
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kw: sources[args[-1].split(':', 1)[1]]): oracle.extract(root, output, evidence)
            self.assertEqual((19, 12, 3), (len(sources), len(oracle.SHARED), len(list(output.rglob('*.java')))))
            service, controller, advice = [(output / relative).read_text() for relative in oracle.OUTPUTS]
            self.assertEqual(12, len(oracle.SERVICE_UNITS))
            for specification in oracle.SERVICE_UNITS: self.assertIn(oracle.checked_method(sources[oracle.SERVICE].decode(), specification), service)
            self.assertIn(oracle.checked_method(sources[oracle.CONTROLLER].decode(), oracle.CONTROLLER_UNIT), controller)
            for specification in oracle.ADVICE_UNITS: self.assertIn(oracle.checked_method(sources[oracle.ADVICE].decode(), specification), advice)
            self.assertIn(oracle.LOGGER['verbatim_utf8'], advice); self.assertIn(oracle.F22_ADVICE_ANNOTATIONS, advice)
            self.assertEqual(before, {relative: (root / relative).read_bytes() for relative in before})
            self.assertEqual(17, evidence.read_text().count('  wrapper-method ')); self.assertIn('source_sha=' + f22.SOURCE, evidence.read_text())

    def test_given_sparse_checkout__when_reading_pinned_inputs__then_objects_exist_and_nineteen_hashes_need_no_legacy_working_tree(self):
        subprocess.check_output(['git', 'cat-file', '-e', f22.SOURCE + '^{commit}'], cwd=f22.ROOT.parent)
        self.assertEqual(19, len(oracle_sources()))

    def test_given_workflow__when_integrating_f22__then_f21_finishes_first_exact_tasks_before_smoke_always_cleanup_and_pinned_source_mask(self):
        workflow = (f22.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('- name: Verify F22 extended read permissions')
        self.assertLess(workflow.index('python3 scripts/f21_ci.py smoke'), boundary); stage = workflow[boundary:]
        for first, second in (('test_f22_ci_guards.py', 'python3 scripts/f22_ci.py prepare'), ('python3 scripts/f22_ci.py prepare', ':boot:' + f22.TASK),
                              (':boot:' + f22.SEOUL_TASK, 'python3 scripts/f22_ci.py results'), ('python3 scripts/f22_ci.py results', 'python3 scripts/f22_ci.py smoke')):
            self.assertLess(stage.index(first), stage.index(second))
        self.assertEqual(1, stage.count(':boot:' + f22.TASK + ' ')); self.assertEqual(1, stage.count(':boot:' + f22.SEOUL_TASK))
        cleanup = stage.index('- name: Remove only the owned disposable fixture'); self.assertIn('if: always()', stage[cleanup:cleanup + 180])
        self.assertNotRegex(stage[:cleanup], r'python3 scripts/f(?:[2-9]|1[0-9]|2[01])_ci\.py (?:prepare|smoke)')
        source_start = workflow.index('- name: Require the immutable F22 original-source Git object')
        source_end = workflow.index('\n      - name:', source_start)
        source_step = workflow[source_start:source_end]
        for value in (f22.SOURCE, 'git cat-file -e', 'git fetch --no-tags --no-recurse-submodules --depth=1 origin',
                      'GIT_CONFIG_COUNT=1', 'F22_SOURCE_READ_TOKEN: ${{ github.token }}', 'printf \'::add-mask::%s\\n\' "$source_auth"'):
            self.assertIn(value, source_step)
        self.assertNotIn('--force', source_step); self.assertNotIn('git config ', source_step); self.assertNotIn('export GIT_CONFIG', source_step)
        self.assertNotRegex(source_step, r'https://[^\s]*\$(?:F22_SOURCE_READ_TOKEN|source_auth)')
        self.assertLess(source_step.index('::add-mask::'), source_step.index('GIT_CONFIG_COUNT=1'))
        self.assertEqual(1, source_step.count('GIT_CONFIG_COUNT=1'))
        self.assertIn('sparse-checkout:', workflow); self.assertNotIn('            community-service\n', workflow)
        denied = next(line for line in workflow.splitlines() if "if grep -E '" in line).split("if grep -E '", 1)[1].split("' build/ci/", 1)[0]
        self.assertIsNotNone(re.search(denied, 'BOOT-INF/classes/f22LegacyOracle/Input.class'))
        self.assertIsNotNone(re.search(denied, 'BOOT-INF/classes/com/pawbridge/communityservice/contact/F22LegacyPrivateNoteService.class'))


if __name__ == '__main__':
    print('F22 private guard drafts: resources mocked, pinned Git reads only. No actual PG/JAR evidence.', flush=True)
    with redirect_stdout(io.StringIO()): unittest.main()
