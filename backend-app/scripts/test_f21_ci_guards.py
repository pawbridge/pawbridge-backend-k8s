"""F21 resource mocks only; real PG/MVC/JAR evidence is a separate future gate."""
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
import urllib.parse

import f21_ci as f21
import prepare_f21_legacy_oracle as oracle
from test_f17_ci_guards import HOSTED

# Synthetic counts for testing guard branches; not an assertion that real F21 cases ran.
MOCK_COUNTS = dict(zip(f21.COUNTERS, (8, 5, 3, 2, 1, 1, 1, 1, 1)))


def source_bytes(path):
    raw = subprocess.check_output(['git', 'show', f21.SOURCE + ':' + path], cwd=f21.ROOT.parent)
    if hashlib.sha256(raw).hexdigest() != f21.DDL_HASHES[path]:
        raise AssertionError('F21 immutable guard input hash differs')
    return raw


def token(identity, expiry, role='ROLE_USER'):
    claims = base64.urlsafe_b64encode(json.dumps({'userId': identity, 'role': role, 'exp': expiry}).encode()).decode().rstrip('=')
    return 'synthetic.' + claims + '.signature'


def oracle_sources():
    values = {}
    for source, digest in oracle.EXPECTED.items():
        raw = subprocess.check_output(['git', 'show', oracle.SOURCE + ':' + source], cwd=f21.ROOT.parent)
        if hashlib.sha256(raw).hexdigest() != digest: raise AssertionError('F21 fixed oracle guard input hash differs')
        values[source] = raw
    return values


def shared_sources(root, sources):
    for source, relative in oracle.SHARED.items():
        target = root / relative; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(sources[source])
    text = sources[oracle.ADVICE].decode()
    annotations = oracle.checked_section(text, oracle.ADVICE_ANNOTATIONS)
    (root / oracle.F18_ADAPTED_PATH).write_bytes(text.replace(annotations, oracle.F18_ADAPTED_ANNOTATIONS, 1).encode())


@contextmanager
def packaged_harness(directory, failure=None, mutation=False, storage_mutation=False, startup_mutation=False, cache=None, cookie=False):
    root = Path(directory).resolve()
    evidence = root / 'evidence'
    jar = root / 'boot/build/libs/pawbridge-backend-app.jar'
    jar.parent.mkdir(parents=True); jar.write_bytes(b'mock-only-jar')
    requests, started = [], False
    statuses = {4: 503, 6: 503, 8: 401, 9: 403}
    messages = {4: f21.STORAGE_UNAVAILABLE, 6: f21.MEMBER_UNAVAILABLE, 8: '인증 토큰이 필요합니다.', 9: '접근할 수 없습니다.'}

    def request(base, path, **kwargs):
        requests.append((path, kwargs)); number = len(requests)
        if number == failure: raise AssertionError('mocked request failed')
        status = statuses.get(number, 200)
        cursor = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query).get('cursor', [None])[0]
        body = ({'code': 200, 'message': f21.SUCCESS, 'data': f21.expected_notifications(cursor)} if status == 200 else
                {'code': status, 'message': messages[number], 'data': None} if number in (8, 9) else {'message': messages[number]})
        headers = {'Cache-Control': cache if cache is not None else f21.f19.CACHE_CONTROL if number in (8, 9) else 'no-store'}
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
        for item in (patch.dict(os.environ, env, clear=True), patch.object(f21, 'ROOT', root), patch.object(f21, 'EVIDENCE', evidence),
                     patch.object(f21, 'EXPECTED_JAR_COUNTS', MOCK_COUNTS), patch.object(f21, 'require_fixture', return_value='123-1'),
                     patch.object(f21, 'require_owner', return_value='123-1'), patch.object(f21, 'check_receipt'),
                     patch.object(f21, 'require_f21_receipt'), patch.object(f21.f19, 'require_identity', return_value='123-1'),
                     patch.object(f21, 'load_tokens', return_value={'F21_CALLER': 'private-used-token'}),
                     patch.object(f21, 'database_snapshot', side_effect=lambda: {'state': 'changed' if
                         (mutation and requests) or (startup_mutation and started) else 'same'}),
                     patch.object(f21.f3, 'request', side_effect=request), patch.object(f21.f3, 'expect', side_effect=storage),
                     patch.object(f21.f4, 'fresh_log', return_value='Shutdown completed. Shutdown completed. Graceful shutdown complete')):
            stack.enter_context(item)
        sql = stack.enter_context(patch.object(f21.f3, 'sql', return_value='0'))
        seed = stack.enter_context(patch.object(f21, 'prepare_business_rows', return_value=f21.OWNED))
        start_mock = stack.enter_context(patch.object(f21.f4, 'start_ready_children', side_effect=start))
        stop = stack.enter_context(patch.object(f21.f3, 'stop_owned_child', return_value=0))
        cleanup = stack.enter_context(patch.object(f21, 'cleanup_business_rows'))
        yield evidence, requests, sql, start_mock, stop, cleanup, seed


class F21GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_actions__then_refuse_before_git_sql_or_children(self):
        actions = (f21.prepare, f21.verified_ddl, f21.require_fixture, f21.require_owner, f21.prepare_business_rows,
                   lambda: f21.cleanup_business_rows(f21.OWNED), f21.load_tokens, f21.verify_results, f21.smoke)
        for action in actions:
            with patch.dict(os.environ, {}, clear=True), patch.object(f21.f3, 'sql') as sql, patch.object(f21.f2, 'command') as command, \
                    patch.object(subprocess, 'check_output') as git, patch.object(f21.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'): action()
                sql.assert_not_called(); command.assert_not_called(); git.assert_not_called(); start.assert_not_called()

    def test_given_unmeasured_real_counts__when_results_or_smoke_requested__then_stop_before_any_resource(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f21, 'EXPECTED_JAR_COUNTS', None), \
                patch.object(f21, 'REPORTS', {f21.TASK: {f21.PG_CLASS: None, f21.MVC_CLASS: None}, f21.SEOUL_TASK: {f21.PG_CLASS: None}}), \
                patch.object(f21, 'require_fixture') as fixture, patch.object(f21.f3, 'sql') as sql, patch.object(f21.f4, 'start_ready_children') as start:
            for action in (f21.verify_results, f21.smoke):
                with self.assertRaisesRegex(SystemExit, 'unmeasured'): action()
            fixture.assert_not_called(); sql.assert_not_called(); start.assert_not_called()

    def test_given_fixed_original_migrations__when_building_ddl__then_two_tables_eight_indexes_and_nine_reader_columns_only(self):
        sources = {path: source_bytes(path) for path in f21.DDL_HASHES}
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(subprocess, 'check_output', side_effect=lambda args, **kw: sources[args[-1].split(':', 1)[1]]) as git:
            ddl, proof = f21.verified_ddl()
        self.assertEqual(3, git.call_count); self.assertTrue(ddl.startswith('BEGIN;\n')); self.assertTrue(ddl.endswith('COMMIT;\n'))
        self.assertEqual(2, ddl.count('CREATE TABLE ')); self.assertEqual(8, ddl.count('CREATE INDEX ')); self.assertEqual(2, ddl.count('GRANT SELECT('))
        self.assertIn('REFERENCES pawbridge_community.private_notes(note_id) ON DELETE SET NULL', ddl)
        self.assertIn('REFERENCES pawbridge_community.private_note_members(member_id) ON DELETE CASCADE', ddl)
        self.assertIn('SELECT(note_id,sender_id,created_at,expires_at)', ddl)
        self.assertIn('SELECT(member_id,note_id,direction,read_at,created_at)', ddl)
        for forbidden in ('CREATE SCHEMA', 'CREATE TABLE pawbridge_community.private_note_members', 'private_note_requests', 'pawbridge_f3_writer', 'IF NOT EXISTS', 'SEQUENCE'):
            self.assertNotIn(forbidden, ddl)
        self.assertEqual(f21.source_proof(), proof)
        self.assertEqual(6, proof.count('  original-fragment-'))
        for digest in f21.DDL_HASHES.values(): self.assertIn(digest, proof)

    def test_given_late_source_or_fragment_tamper__when_preparing__then_validate_all_inputs_before_mutation(self):
        sources = {path: source_bytes(path) for path in f21.DDL_HASHES}
        last = next(reversed(sources))
        for tamper in ('source', 'fragment'):
            values = dict(sources)
            if tamper == 'source': values[last] = b'changed'
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(subprocess, 'check_output', side_effect=lambda args, **kw: values[args[-1].split(':', 1)[1]]), \
                    patch.object(f21, 'FRAGMENT_HASHES', ('wrong',) if tamper == 'fragment' else f21.FRAGMENT_HASHES), \
                    patch.object(f21.f3, 'sql') as sql, patch.object(Path, 'write_text') as write:
                with self.assertRaisesRegex(SystemExit, 'hash|checksum'): f21.verified_ddl()
                sql.assert_not_called(); write.assert_not_called()

    def test_given_shared_f17_pin_disagreement__when_building_ddl__then_stop_before_git_or_mutation(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f21.f17, 'DDL_HASHES', {}), patch.object(subprocess, 'check_output') as git:
            with self.assertRaisesRegex(SystemExit, 'existing F17 pins'): f21.verified_ddl()
            git.assert_not_called()

    def test_given_missing_foreign_or_redirected_receipt__when_reading_identity__then_refuse_without_sql(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(f21, 'EVIDENCE', Path(directory).resolve()), patch.object(f21.f3, 'sql') as sql:
            target = f21.EVIDENCE / 'f21-fixture-reuse.txt'
            with self.assertRaises(SystemExit): f21.require_f21_receipt('123-1')
            target.write_text(f21.fixture_proof('foreign', f21.source_proof()))
            with self.assertRaises(SystemExit): f21.require_f21_receipt('123-1')
            target.unlink(); other = f21.EVIDENCE / 'other'; other.write_text('preserve'); target.symlink_to(other)
            with self.assertRaises(SystemExit): f21.require_f21_receipt('123-1')
            sql.assert_not_called()

    def test_given_bad_source_receipt_old_snapshot_or_existing_table__when_preparing__then_zero_database_writes(self):
        for issue in ('source', 'receipt', 'snapshot', 'table'):
            with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f21, 'EVIDENCE', Path(directory).resolve()), patch.object(f21.f20, 'require_fixture', return_value='123-1'), \
                    patch.object(f21, 'check_receipt', side_effect=SystemExit('bad receipt') if issue == 'receipt' else None), \
                    patch.object(f21, 'verified_ddl', side_effect=SystemExit('bad source') if issue == 'source' else None, return_value=('DDL', 'proof')), \
                    patch.object(f21.f20, 'database_snapshot', return_value={} if issue == 'snapshot' else {str(i): [] for i in range(14)}), \
                    patch.object(f21, 'catalog', return_value='1' if issue == 'table' else '0'), patch.object(f21, 'owned_sql') as sql:
                with self.assertRaises(SystemExit): f21.prepare()
                sql.assert_not_called()

    def test_given_valid_old_fixture__when_extending__then_source_checks_precede_one_atomic_ddl_and_old_rows_stay_unchanged(self):
        events = []; before = {str(i): [] for i in range(14)}
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f21, 'EVIDENCE', Path(directory).resolve()), patch.object(f21.f20, 'require_fixture', side_effect=lambda: events.append('old') or '123-1'), \
                patch.object(f21, 'check_receipt', side_effect=lambda *a, **kw: events.append('receipt')), \
                patch.object(f21, 'verified_ddl', side_effect=lambda: events.append('source') or ('BEGIN; DDL; COMMIT;', 'proof')), \
                patch.object(f21.f20, 'database_snapshot', return_value=before), patch.object(f21, 'catalog', side_effect=lambda q, stage: events.append('absence') or '0'), \
                patch.object(f21, 'owned_sql', side_effect=lambda **kw: events.append('write')) as sql, patch.object(f21, 'require_fixture', return_value='123-1'), \
                patch.object(f21, 'database_snapshot', return_value=dict(before, private_notes=[], private_note_mailboxes=[])):
            f21.prepare()
            self.assertEqual(['old', 'receipt', 'source', 'receipt', 'absence', 'write'], events)
            sql.assert_called_once_with(input_text='BEGIN; DDL; COMMIT;')
            self.assertIn('new_business_tables=2', (f21.EVIDENCE / 'f21-fixture-reuse.txt').read_text())

    def test_given_extended_fixture__when_validating__then_keep_f20_three_column_chain_without_old_two_column_helpers(self):
        with patch.object(f21.f20, 'require_owner', return_value='123-1') as owner, patch.object(f21, 'check_receipt') as receipt, \
                patch.object(f21.f17, 'require_fixture') as old17, patch.object(f21.f19, 'require_fixture') as old19:
            self.assertEqual('123-1', f21.require_owner()); owner.assert_called_once_with()
            receipt.assert_called_once_with(f21.EVIDENCE / 'f20-fixture-reuse.txt', f21.f20_proof('123-1'))
            old17.assert_not_called(); old19.assert_not_called()

    def test_given_catalog__when_validating__then_exact_shapes_acls_constraints_and_five_indexes_per_table(self):
        with patch.object(f21, 'require_owner', return_value='123-1'), patch.object(f21.f20, 'require_fixture', return_value='123-1'), \
                patch.object(f21, 'require_f21_receipt'), patch.object(f21, 'catalog', return_value='t') as catalog:
            self.assertEqual('123-1', f21.require_fixture())
        self.assertEqual(6, catalog.call_count); text = '\n'.join(c.args[0] for c in catalog.call_args_list)
        self.assertTrue(all(c.args[0].startswith('SELECT ') for c in catalog.call_args_list))
        for value in ('count(*)=9', 'count(*)=6', 'count(*)=8', 'count(*)=4', 'count(*)=5', 'character_maximum_length',
                      'pg_get_serial_sequence', 'pawbridge_f3_writer', 'convalidated', "confdeltype='n'", "confdeltype='c'",
                      'char_lengthbody>=1ANDchar_lengthbody<=5000', 'idx_private_note_reply_to', 'idx_private_note_mailbox_note',
                      'idx_private_note_mailbox_unread', 'i.indoption[2]=3', "pg_get_userbyid(c.relowner)='postgres'"):
            self.assertIn(value, text)
        self.assertIn("column_name IN ('note_id','sender_id','created_at','expires_at')", text)
        self.assertNotIn("column_name IN ('body'", text)

    def test_given_any_catalog_mismatch__when_validating__then_stop_without_mutation(self):
        for failed in range(6):
            with patch.object(f21, 'require_owner', return_value='123-1'), patch.object(f21.f20, 'require_fixture', return_value='123-1'), patch.object(f21, 'require_f21_receipt'), \
                    patch.object(f21, 'catalog', side_effect=['t'] * failed + ['f']), patch.object(f21.f3, 'sql') as sql:
                with self.assertRaises(SystemExit): f21.require_fixture()
                sql.assert_not_called()

    def test_given_prior_fourteen_tables__when_snapshotting__then_add_full_note_and_mailbox_rows_and_refuse_incomplete_or_colliding_baseline(self):
        with patch.object(f21.f20, 'database_snapshot', side_effect=lambda: {str(i): [] for i in range(14)}), patch.object(f21, 'owned_sql', return_value='[]') as sql:
            result = f21.database_snapshot(); self.assertEqual(16, len(result)); self.assertEqual(2, sql.call_count)
            self.assertIn('json_agg(r ORDER BY note_id)', sql.call_args_list[0].args[0])
            self.assertIn('json_agg(r ORDER BY member_id,note_id)', sql.call_args_list[1].args[0])
        for value in ({}, dict({str(i): [] for i in range(13)}, private_notes=[])):
            with patch.object(f21.f20, 'database_snapshot', return_value=value), patch.object(f21, 'owned_sql') as sql:
                with self.assertRaises(SystemExit): f21.database_snapshot()
                sql.assert_not_called()

    def test_given_fresh_keys__when_seeding__then_atomic_exact_owned_fk_rows_without_update_or_claiming_existing_rows(self):
        with patch.object(f21, 'require_fixture'), patch.object(f21, 'owned_sql', side_effect=['0', '']) as sql:
            self.assertEqual(f21.OWNED, f21.prepare_business_rows())
        self.assertEqual(2, sql.call_count); text = sql.call_args_list[1].args[0]
        self.assertTrue(text.startswith('BEGIN;')); self.assertTrue(text.endswith('COMMIT;'))
        for table in ('users', 'contact_deletions', 'private_note_members', 'private_notes', 'private_note_mailboxes'): self.assertIn('INSERT INTO ', text); self.assertIn(table, text)
        for forbidden in ('UPDATE ', 'DELETE ', 'ON CONFLICT', 'private_note_requests'): self.assertNotIn(forbidden, text)
        for note in f21.ALL_NOTES: self.assertIn(note, text)
        self.assertIn("'SENT'", text); self.assertIn("'1970-01-01T00:00:00Z'", text)

    def test_given_preexisting_key__when_seeding__then_refuse_before_insert_and_never_return_owned_keys(self):
        with patch.object(f21, 'require_fixture'), patch.object(f21, 'owned_sql', return_value='1') as sql:
            with self.assertRaises(SystemExit): f21.prepare_business_rows()
            self.assertEqual(1, sql.call_count)

    def test_given_only_successful_owned_keys__when_cleaning__then_exact_fk_order_and_reject_other_keys_before_sql(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f21, 'require_owner'), patch.object(f21, 'require_f21_receipt'), patch.object(f21, 'owned_sql') as sql:
            with self.assertRaises(ValueError): f21.cleanup_business_rows((1,))
            sql.assert_not_called(); f21.cleanup_business_rows(f21.OWNED)
            self.assertEqual(5, sql.call_count)
            self.assertEqual(['private_note_mailboxes', 'private_notes', 'private_note_members', 'contact_deletions', 'users'],
                             [c.args[0].split('WHERE')[0].split('.')[-1].strip() for c in sql.call_args_list])
            self.assertTrue(all(' WHERE ' in c.args[0] for c in sql.call_args_list))
            self.assertIn('body=', sql.call_args_list[1].args[0]); self.assertIn('(user_id,email)', sql.call_args_list[-1].args[0])

    def test_given_failure_while_either_acl_revoked__when_unwinding__then_identity_before_restore_and_full_fixture_only_after_restore(self):
        for relation, column in (('pawbridge_community.private_notes', 'expires_at'), ('pawbridge_user.users', 'nickname')):
            events = []
            with patch.object(f21, 'require_fixture', side_effect=lambda: events.append('full') or '123-1'), \
                    patch.object(f21.f19, 'require_identity', side_effect=lambda owner: events.append('identity')), patch.object(f21, 'check_receipt'), \
                    patch.object(f21, 'require_f21_receipt'), patch.object(f21, 'owned_sql', side_effect=lambda query: events.append(query)):
                with self.assertRaisesRegex(RuntimeError, 'body failure'):
                    with f21.denied_select(relation, column): raise RuntimeError('body failure')
            self.assertEqual(['full', 'REVOKE SELECT(' + column + ') ON ' + relation + ' FROM pawbridge_f2_reader',
                              'identity', 'GRANT SELECT(' + column + ') ON ' + relation + ' TO pawbridge_f2_reader', 'full'], events)

    def test_given_owner_changed_under_revocation__when_unwinding__then_no_restore_sql(self):
        with patch.object(f21, 'require_fixture', return_value='123-1'), patch.object(f21.f19, 'require_identity', side_effect=SystemExit('foreign owner')), \
                patch.object(f21, 'owned_sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'foreign owner'):
                with f21.denied_select('pawbridge_user.users', 'nickname'): pass
            self.assertEqual(1, sql.call_count); self.assertTrue(sql.call_args.args[0].startswith('REVOKE '))

    def test_given_used_token_and_old_unrelated_keys__when_loading__then_require_regular_0600_owner_id_role_and_ttl_without_rewrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / 'f3-tokens-123-1.json'
            env = dict(HOSTED, RUNNER_TEMP=str(path.parent), F3_TEST_TOKENS_PATH=str(path))
            now = time.time(); valid = {'F21_CALLER': token(f21.CALLER, now + 600), 'expired-old': 'preserve'}
            with patch.dict(os.environ, env, clear=True):
                path.write_text(json.dumps(valid)); path.chmod(0o600); original = path.read_bytes()
                self.assertEqual({'F21_CALLER': valid['F21_CALLER']}, f21.load_tokens()); self.assertEqual(original, path.read_bytes())
                for value in ({}, dict(valid, F21_CALLER=token(f21.CALLER, now + 30)), dict(valid, F21_CALLER=token(f21.OTHER, now + 600)),
                              dict(valid, F21_CALLER=token(f21.CALLER, now + 600, 'ROLE_ADMIN')), dict(valid, F21_CALLER=token(float(f21.CALLER), now + 600))):
                    path.write_text(json.dumps(value))
                    with self.assertRaises(SystemExit): f21.load_tokens()
                path.write_text(json.dumps(valid)); path.chmod(0o644)
                with self.assertRaises(SystemExit): f21.load_tokens()
                path.chmod(0o600); other = path.parent / 'other'; path.rename(other); path.symlink_to(other)
                with self.assertRaises(SystemExit): f21.load_tokens()

    def test_given_xml__when_reporting__then_require_exact_classes_positive_counts_named_seoul_case_and_no_hidden_child_failures(self):
        reports = {f21.TASK: {f21.PG_CLASS: 2, f21.MVC_CLASS: 2}, f21.SEOUL_TASK: {f21.PG_CLASS: 1}}
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f21, 'ROOT', Path(directory).resolve()), patch.object(f21, 'REPORTS', reports):
            with self.assertRaises(SystemExit): f21.verify_results()
            for task, classes in reports.items():
                root = f21.ROOT / 'boot/build/test-results' / task; root.mkdir(parents=True)
                for name, count in classes.items():
                    cases = ''.join('<testcase classname="' + name + '" name="' + (f21.SEOUL_METHOD + '()' if task == f21.SEOUL_TASK else 'case' + str(i)) + '"/>' for i in range(count))
                    (root / ('TEST-' + name + '.xml')).write_text('<testsuite name="' + name + '" tests="' + str(count) + '" failures="0" errors="0" skipped="0">' + cases + '</testsuite>')
            self.assertEqual(2, len(f21.verify_results()))
            target = f21.ROOT / 'boot/build/test-results' / f21.SEOUL_TASK / ('TEST-' + f21.PG_CLASS + '.xml'); good = target.read_text()
            for bad in (good.replace(f21.SEOUL_METHOD, 'wrong'), good.replace('tests="1"', 'tests="0"'), good.replace('skipped="0"', 'skipped="1"'),
                        good.replace('classname="com.', 'classname="foreign.'), *(good.replace('/></testsuite>', '><' + tag + '/></testcase></testsuite>') for tag in ('failure', 'error', 'skipped'))):
                target.write_text(bad)
                with self.assertRaises(SystemExit): f21.verify_results()
            target.write_text(good); (target.parent / 'TEST-extra.xml').write_text('unexpected')
            with self.assertRaisesRegex(SystemExit, 'exact UTC/Seoul'): f21.verify_results()

    def test_given_mocked_packaged_journey__when_observing__then_exact_payloads_recovery_ttl_calls_and_cleanup_without_claiming_real_execution(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            calls = []; current = f21.load_tokens
            def fresh(): calls.append(len(requests)); return current()
            with patch.object(f21, 'load_tokens', side_effect=fresh): f21.smoke()
            result = json.loads((evidence / 'f21-packaged-result.txt').read_text())
            self.assertEqual('passed', result['status']); self.assertEqual(9, len(requests))
            self.assertEqual(MOCK_COUNTS, {key: result[key] for key in f21.COUNTERS})
            self.assertEqual([0, 0, 1, 2, 3, 4, 5, 6, 8], calls)
            self.assertNotIn('Authorization', requests[7][1]['headers']); self.assertEqual('POST', requests[8][1]['method'])
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f21.OWNED)
            self.assertEqual(2, result['shutdown_pools']); self.assertEqual(0, result['remaining_application_connections'])
            self.assertEqual('1', start.call_args.args[1]['F2_POOL_MAX_SIZE'])
            for key in ('DAILY_VISITORS_ENABLED', 'SPRING_TASK_SCHEDULING_ENABLED'): self.assertNotIn(key, start.call_args.args[1])

    def test_given_stale_used_token_after_snapshot__when_request_due__then_request_zero_children_stopped_owned_cleanup_and_incomplete(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            state = {'before': False}; events = []; database, tokens = f21.database_snapshot, f21.load_tokens
            def snapshot():
                value = database()
                if seed.called: state['before'] = True; events.append('snapshot')
                return value
            def fresh():
                if state['before']: events.append('stale'); raise SystemExit('stale after snapshot')
                events.append('mask'); return tokens()
            with patch.object(f21, 'database_snapshot', side_effect=snapshot), patch.object(f21, 'load_tokens', side_effect=fresh):
                with self.assertRaisesRegex(SystemExit, 'stale after snapshot'): f21.smoke()
            self.assertEqual(['mask', 'snapshot', 'stale'], events); self.assertEqual([], requests)
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f21.OWNED)
            self.assertEqual('incomplete', json.loads((evidence / 'f21-packaged-result.txt').read_text())['status'])

    def test_given_request_failure_during_either_revocation__when_unwinding__then_restore_grant_stop_children_clean_owned_rows_and_record_incomplete(self):
        for number, column in ((4, 'expires_at'), (6, 'nickname')):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, failure=number) as (evidence, requests, sql, start, stop, cleanup, seed):
                with self.assertRaisesRegex(AssertionError, 'mocked request failed'): f21.smoke()
                self.assertIn('GRANT SELECT(' + column + ')', '\n'.join(c.args[0] for c in sql.call_args_list))
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f21.OWNED)
                self.assertEqual('incomplete', json.loads((evidence / 'f21-packaged-result.txt').read_text())['status'])

    def test_given_cache_cookie_db_or_s3_change__when_observing__then_fail_without_passing_receipt_and_cleanup(self):
        for fault in ({'cache': 'public'}, {'cookie': True}, {'mutation': True}, {'storage_mutation': True}):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **fault) as (evidence, _, _, _, stop, cleanup, seed):
                with self.assertRaises(AssertionError): f21.smoke()
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f21.OWNED)
                self.assertEqual('incomplete', json.loads((evidence / 'f21-packaged-result.txt').read_text())['status'])

    def test_given_startup_mutation__when_starting__then_seed_zero_and_stop_both_children(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, startup_mutation=True) as (_, requests, _, _, stop, cleanup, seed):
            with self.assertRaisesRegex(AssertionError, 'startup changed'): f21.smoke()
            seed.assert_not_called(); cleanup.assert_not_called(); self.assertEqual([], requests); self.assertEqual(2, stop.call_count)

    def test_given_sql_failure_with_private_command_and_output__when_reporting__then_metadata_only_message_without_original_trace(self):
        failure = subprocess.CalledProcessError(1, ['private-command'], output='private-body', stderr='private-password')
        with patch.object(f21.f3, 'sql', side_effect=failure):
            with self.assertRaises(SystemExit) as raised: f21.owned_sql('SELECT private-body')
            self.assertEqual('F21 owned fixture SQL failed without exposing command, SQL or output', str(raised.exception))
            self.assertTrue(raised.exception.__suppress_context__)

    def test_given_catalog_failure__when_recording__then_only_bounded_masked_primary_error_with_f21_stage_and_no_detail_sql_or_command(self):
        failure = subprocess.CalledProcessError(1, ['private-command'], output='private-stdout',
                stderr='ERROR: private-token ' + 'x' * 900 + '\nDETAIL: private-detail\nSTATEMENT: SELECT hidden-catalog-text')
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'F3_TEST_JWT_SECRET': 'private-token'}, clear=True), \
                patch.object(f21, 'EVIDENCE', Path(directory).resolve()), patch.object(f21.f3, 'sql', side_effect=failure):
            with self.assertRaisesRegex(SystemExit, 'bounded f21-fixture-catalog-error'): f21.catalog('SELECT hidden-catalog-text', 'acl')
            value = (f21.EVIDENCE / 'f21-fixture-catalog-error.txt').read_text()
            self.assertIn('stage=f21-notifications-acl', value); self.assertIn('[redacted]', value); self.assertLess(len(value), 600)
            for secret in ('private-token', 'private-command', 'private-stdout', 'private-detail', 'SELECT hidden-catalog-text'):
                self.assertNotIn(secret, value)

    def test_given_sparse_checkout_without_legacy_directories__when_loading_fixed_ddl__then_require_exact_commit_object_and_all_hashes(self):
        marker = f21.ROOT.parent / '.git'
        git_dir = Path(marker.read_text().removeprefix('gitdir:').strip()) if marker.is_file() else marker
        if not git_dir.is_absolute(): git_dir = (marker.parent / git_dir).resolve()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); (root / '.git').write_text('gitdir: ' + str(git_dir.resolve()) + '\n')
            subprocess.check_output(['git', 'cat-file', '-e', f21.SOURCE + '^{commit}'], cwd=root)
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f21, 'ROOT', root / 'backend-app'):
                ddl, proof = f21.verified_ddl()
                self.assertEqual(19, len(oracle_sources()))
            self.assertIn('source_sha=' + f21.SOURCE, proof); self.assertIn('CREATE TABLE pawbridge_community.private_notes', ddl)
            self.assertFalse((root / 'community-service').exists()); self.assertFalse((root / 'user-service').exists())

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

    def test_given_late_oracle_source_tamper__when_extracting__then_all_nineteen_validate_before_any_output(self):
        sources = oracle_sources(); sources[next(reversed(sources))] = b'tampered'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); output, evidence = root / oracle.OUTPUT_ROOT, root / oracle.EVIDENCE_PATH
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kw: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                with self.assertRaisesRegex(SystemExit, 'checksum mismatch'): oracle.extract(root, output, evidence)
                write.assert_not_called(); self.assertFalse(output.exists()); self.assertFalse(evidence.exists())

    def test_given_any_raw_or_adapted_shared_tamper__when_extracting__then_preserve_all_thirteen_reused_artifacts_before_output(self):
        sources = oracle_sources()
        for relative in (*oracle.SHARED.values(), oracle.F18_ADAPTED_PATH):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve(); shared_sources(root, sources); (root / relative).write_bytes(b'preserve-tamper')
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kw: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaises(SystemExit): oracle.extract(root, root / oracle.OUTPUT_ROOT, root / oracle.EVIDENCE_PATH)
                    write.assert_not_called(); self.assertEqual(b'preserve-tamper', (root / relative).read_bytes())

    def test_given_selected_unit_constant_annotation_or_logger_hash_mismatch__when_extracting__then_refuse_before_write(self):
        sources = oracle_sources()
        specifications = (oracle.SERVICE_UNITS[0], oracle.CONTROLLER_UNIT, oracle.ADVICE_UNITS[0], oracle.PAGE_SIZE,
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
                target = output / oracle.OUTPUTS[-1] if kind == 'output' else evidence if kind == 'receipt' else root / 'backend-app/boot/src/test/java/other/F21LegacyNotificationsService.java'
                target.parent.mkdir(parents=True, exist_ok=True)
                original = b'preserve-conflict' if kind != 'duplicate' else b'package com.pawbridge.communityservice.contact; public class F21LegacyNotificationsService {}'
                target.write_bytes(original)
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kw: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaises(SystemExit): oracle.extract(root, output, evidence)
                    write.assert_not_called(); self.assertEqual(original, target.read_bytes())

    def test_given_all_fixed_oracle_inputs__when_extracting__then_three_unique_outputs_preserve_eleven_methods_annotated_cursor_four_handlers_and_shared_logger(self):
        sources = oracle_sources()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); shared_sources(root, sources)
            output, evidence = root / oracle.OUTPUT_ROOT, root / oracle.EVIDENCE_PATH
            before = {relative: (root / relative).read_bytes() for relative in (*oracle.SHARED.values(), oracle.F18_ADAPTED_PATH)}
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kw: sources[args[-1].split(':', 1)[1]]): oracle.extract(root, output, evidence)
            self.assertEqual((19, 12, 3), (len(sources), len(oracle.SHARED), len(list(output.rglob('*.java')))))
            service, controller, advice = [(output / relative).read_text() for relative in oracle.OUTPUTS]
            self.assertEqual(11, len(oracle.SERVICE_UNITS))
            for specification in oracle.SERVICE_UNITS: self.assertIn(oracle.checked_method(sources[oracle.SERVICE].decode(), specification), service)
            self.assertIn(oracle.checked_method(sources[oracle.CONTROLLER].decode(), oracle.CONTROLLER_UNIT), controller)
            for specification in oracle.ADVICE_UNITS: self.assertIn(oracle.checked_method(sources[oracle.ADVICE].decode(), specification), advice)
            self.assertIn(oracle.LOGGER['verbatim_utf8'], advice); self.assertIn(oracle.F21_ADVICE_ANNOTATIONS, advice)
            self.assertEqual(before, {relative: (root / relative).read_bytes() for relative in before})
            self.assertEqual(16, evidence.read_text().count('  wrapper-method ')); self.assertIn('source_sha=' + f21.SOURCE, evidence.read_text())

    def test_given_workflow__when_integrating_f21__then_f20_finishes_first_exact_tasks_before_smoke_and_always_cleanup_with_jar_isolation(self):
        workflow = (f21.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('- name: Verify F21 exact notes/mailbox')
        self.assertLess(workflow.index('python3 scripts/f20_ci.py smoke'), boundary)
        stage = workflow[boundary:]
        for first, second in (('test_f21_ci_guards.py', 'python3 scripts/f21_ci.py prepare'), ('python3 scripts/f21_ci.py prepare', ':boot:' + f21.TASK),
                              (':boot:' + f21.SEOUL_TASK, 'python3 scripts/f21_ci.py results'), ('python3 scripts/f21_ci.py results', 'python3 scripts/f21_ci.py smoke')):
            self.assertLess(stage.index(first), stage.index(second))
        self.assertEqual(1, stage.count(':boot:' + f21.TASK + ' ')); self.assertEqual(1, stage.count(':boot:' + f21.SEOUL_TASK))
        cleanup = stage.index('- name: Remove only the owned disposable fixture'); self.assertIn('if: always()', stage[cleanup:cleanup + 180])
        self.assertNotRegex(stage[:cleanup], r'python3 scripts/f(?:[2-9]|1[0-9]|20)_ci\.py (?:prepare|smoke)')
        self.assertIn('f21-oracle-sha256.txt', workflow); self.assertIn('f21-fixture-reuse.txt', workflow); self.assertIn('f21-helper-refusal.txt', workflow)
        self.assertIn('sparse-checkout:', workflow); self.assertNotIn('            community-service\n', workflow)
        source_start = workflow.index('- name: Require the immutable F21 original-source Git object')
        source_end = workflow.index('\n      - name:', source_start)
        source_step = workflow[source_start:source_end]
        for value in (f21.SOURCE, 'git cat-file -e', 'git fetch --no-tags --no-recurse-submodules --depth=1 origin',
                      'GIT_CONFIG_COUNT=1', 'F21_SOURCE_READ_TOKEN: ${{ github.token }}', 'printf \'::add-mask::%s\\n\' "$source_auth"'):
            self.assertIn(value, source_step)
        self.assertNotIn('--force', source_step); self.assertNotIn('git config ', source_step)
        self.assertNotRegex(source_step, r'https://[^\s]*\$(?:F21_SOURCE_READ_TOKEN|source_auth)')
        self.assertLess(source_step.index('::add-mask::'), source_step.index('GIT_CONFIG_COUNT=1'))
        self.assertEqual(1, source_step.count('GIT_CONFIG_COUNT=1')); self.assertNotIn('export GIT_CONFIG', source_step)
        denied = next(line for line in workflow.splitlines() if "if grep -E '" in line).split("if grep -E '", 1)[1].split("' build/ci/", 1)[0]
        allowed = next(line for line in workflow.splitlines() if "| grep -Ev '" in line).split("| grep -Ev '", 1)[1].split("'; then", 1)[0]
        for value in ('BOOT-INF/classes/f21LegacyOracle/Input.class', 'BOOT-INF/classes/com/pawbridge/communityservice/contact/F21LegacyNotificationsService.class'):
            self.assertIsNotNone(re.search(denied, value)); self.assertIsNone(re.search(allowed, value))


if __name__ == '__main__':
    print('F21 guards: mocked resources; immutable DDL inputs use read-only Git objects. No actual PG/JAR evidence.', flush=True)
    with redirect_stdout(io.StringIO()): unittest.main()
