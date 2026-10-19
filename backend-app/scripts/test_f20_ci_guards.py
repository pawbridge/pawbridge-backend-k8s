"""F20 mock-only guards; sparse legacy inputs use hash-pinned read-only Git objects."""
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

import f20_ci as f20
import prepare_f20_legacy_oracle as oracle
from test_f17_ci_guards import HOSTED


def pinned_source(source, repository=None):
    if source not in oracle.EXPECTED:
        raise ValueError('F20 input must have an explicit immutable hash')
    raw = subprocess.check_output(['git', 'show', oracle.SOURCE + ':' + source], cwd=repository or f20.ROOT.parent)
    if hashlib.sha256(raw).hexdigest() != oracle.EXPECTED[source]:
        raise AssertionError('Fixed F20 guard input checksum mismatch: ' + source)
    return raw


def oracle_paths(directory):
    root = Path(directory).resolve()
    return root, root / oracle.OUTPUT_ROOT, root / oracle.EVIDENCE_PATH


def shared_inputs(root, sources):
    for source, relative in oracle.SHARED.items():
        target = root / relative; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(sources[source])
    text = sources[oracle.ADVICE].decode()
    annotations = oracle.checked_section(text, oracle.ADVICE_ANNOTATIONS)
    target = root / oracle.F18_ADAPTED_PATH
    target.write_bytes(text.replace(annotations, oracle.F18_ADAPTED_ANNOTATIONS, 1).encode())


def f19_evidence(directory, owner='123-1'):
    root = Path(directory).resolve(); root.mkdir(parents=True, exist_ok=True)
    target = root / 'f19-fixture-reuse.txt'
    target.write_text('owner=' + owner + '\nfixture=F18/F17 unchanged\nnew_business_ddl=0\nnew_acl=0\nnew_marker=0\n')
    return target


def token(identity, expiry, role='ROLE_USER'):
    payload = base64.urlsafe_b64encode(json.dumps({'userId': identity, 'role': role, 'exp': expiry}).encode()).decode().rstrip('=')
    return 'synthetic.' + payload + '.signature'


@contextmanager
def packaged_harness(directory, failure=None, mutation=False, cache=None, cookie=False, storage_mutation=False, startup_mutation=False):
    root = Path(directory).resolve(); evidence = root / 'evidence'
    jar = root / 'boot/build/libs/pawbridge-backend-app.jar'; jar.parent.mkdir(parents=True); jar.write_bytes(b'mock-jar')
    requests = []; started = False
    statuses = {4: 503, 6: 400, 7: 503, 9: 401, 10: 403}
    messages = {4: f20.STORAGE_UNAVAILABLE, 6: f20.INVALID_PAGE, 7: f20.MEMBER_UNAVAILABLE,
                9: '인증 토큰이 필요합니다.', 10: '접근할 수 없습니다.'}
    def request(base, path, **kwargs):
        requests.append((path, kwargs)); number = len(requests)
        if number == failure: raise AssertionError('mocked request failed')
        status = statuses.get(number, 200)
        page = int(urllib.parse.parse_qs(urllib.parse.urlsplit(path).query).get('page', ['0'])[0])
        response = ({'code': 200, 'message': f20.SUCCESS, 'data': f20.expected_page(page)} if status == 200 else
                    {'code': status, 'message': messages[number], 'data': None} if number in (9, 10) else {'message': messages[number]})
        headers = {'Cache-Control': cache if cache is not None else f20.f19.CACHE_CONTROL if number in (9, 10) else 'no-store'}
        if cookie: headers['Set-Cookie'] = 'forbidden'
        return status, json.dumps(response).encode(), headers
    def storage(*args, **kwargs):
        return {'counts': {'PUT': 1 if storage_mutation and requests else 0, 'DELETE': 0}, 'objects': [], 'requests': []}
    def start(*args):
        nonlocal started
        started = True
        return object(), object(), 'http://app', 'http://stub', 1, 0
    env = dict(HOSTED, F2_TEST_DB_PASSWORD='private-reader', F3_TEST_WRITER_PASSWORD='private-writer', F3_TEST_JWT_SECRET='private-jwt')
    with ExitStack() as stack:
        for item in (patch.dict(os.environ, env, clear=True), patch.object(f20, 'ROOT', root), patch.object(f20, 'EVIDENCE', evidence),
                     patch.object(f20, 'require_fixture', return_value='123-1'), patch.object(f20, 'require_owner', return_value='123-1'),
                     patch.object(f20, 'require_f19_evidence'), patch.object(f20.f19, 'require_identity', return_value='123-1'),
                     patch.object(f20, 'load_tokens', return_value={'F20_CALLER': 'private-caller-token'}),
                     patch.object(f20, 'database_snapshot', side_effect=lambda: {'state': 'changed' if
                         (mutation and requests) or (startup_mutation and started) else 'same'}),
                     patch.object(f20.f3, 'request', side_effect=request), patch.object(f20.f3, 'expect', side_effect=storage),
                     patch.object(f20.f4, 'fresh_log', return_value='Shutdown completed. Shutdown completed. Graceful shutdown complete')):
            stack.enter_context(item)
        sql = stack.enter_context(patch.object(f20.f3, 'sql', return_value='0'))
        seed = stack.enter_context(patch.object(f20, 'prepare_business_rows', return_value=f20.OWNED))
        start_mock = stack.enter_context(patch.object(f20.f4, 'start_ready_children', side_effect=start))
        stop = stack.enter_context(patch.object(f20.f3, 'stop_owned_child', return_value=0))
        cleanup = stack.enter_context(patch.object(f20, 'cleanup_business_rows'))
        yield evidence, requests, sql, start_mock, stop, cleanup, seed


class F20GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_actions__then_refuse_before_resources(self):
        for action in (f20.prepare, f20.require_fixture, f20.require_owner, lambda: f20.require_f19_evidence('123-1'),
                       f20.prepare_business_rows, lambda: f20.cleanup_business_rows(f20.OWNED), f20.smoke, f20.verify_results, f20.load_tokens):
            with patch.dict(os.environ, {}, clear=True), patch.object(f20.f3, 'sql') as sql, patch.object(f20.f2, 'command') as command, \
                    patch.object(subprocess, 'check_output') as git, patch.object(f20.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'): action()
                sql.assert_not_called(); command.assert_not_called(); git.assert_not_called(); start.assert_not_called()

    def test_given_wrong_target_or_reader__when_preparing__then_no_command_or_receipt(self):
        for changed in ({'F2_TEST_JDBC_URL': 'jdbc:postgresql://production:5432/pawbridge_f2_ci'}, {'F2_TEST_DB_USER': 'postgres'}):
            with patch.dict(os.environ, dict(HOSTED, **changed), clear=True), patch.object(f20.f2, 'command') as command, patch.object(Path, 'write_text') as write:
                with self.assertRaises(SystemExit): f20.prepare()
                command.assert_not_called(); write.assert_not_called()

    def test_given_missing_foreign_or_redirected_f19_receipt__when_reusing__then_refuse_before_mutation(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f20, 'EVIDENCE', Path(directory).resolve()):
            with self.assertRaises(SystemExit): f20.require_f19_evidence('123-1')
            target = f19_evidence(directory, 'foreign')
            with self.assertRaises(SystemExit): f20.require_f19_evidence('123-1')
            target.unlink(); other = Path(directory) / 'other'; other.write_text('preserve'); target.symlink_to(other)
            with self.assertRaises(SystemExit): f20.require_f19_evidence('123-1')

    def test_given_original_f19_fixture__when_preparing__then_only_timestamp_grant_after_strict_check_and_no_other_changes(self):
        events = []
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f20, 'EVIDENCE', Path(directory).resolve()), \
                patch.object(f20.f19, 'require_fixture', side_effect=lambda: events.append('strict') or '123-1'), \
                patch.object(f20, 'require_fixture', side_effect=lambda: events.append('extended') or '123-1'), \
                patch.object(f20, 'database_snapshot', return_value={'unchanged': []}), \
                patch.object(f20.f3, 'sql', side_effect=lambda q: events.append(q) or '') as sql:
            f19_evidence(directory); f20.prepare()
            self.assertEqual(['strict', 'GRANT SELECT(created_at) ON pawbridge_community.private_note_blocks TO pawbridge_f2_reader', 'extended'], events)
            sql.assert_called_once(); target = Path(directory) / 'f20-fixture-reuse.txt'
            self.assertIn('new_business_ddl=0', target.read_text()); self.assertIn('new_marker=0', target.read_text())
            target.write_text('preserve')
            sql.reset_mock()
            with self.assertRaisesRegex(SystemExit, 'differing or redirected'): f20.prepare()
            sql.assert_not_called(); self.assertEqual('preserve', target.read_text())

    def test_given_extended_acl__when_checking_owner__then_use_safe_f17_chain_and_f19_receipt_without_old_strict_helpers(self):
        with patch.object(f20.f17, 'require_owner', return_value='123-1') as owner, patch.object(f20, 'require_f19_evidence') as receipt, \
                patch.object(f20.f17, 'require_fixture') as old17, patch.object(f20.f19, 'require_fixture') as old19:
            self.assertEqual('123-1', f20.require_owner()); owner.assert_called_once_with(); receipt.assert_called_once_with('123-1')
            old17.assert_not_called(); old19.assert_not_called()
        self.assertIs(f20.database_snapshot, f20.f19.database_snapshot)
        self.assertFalse((f20.ROOT / 'scripts/f20_fixture.sql').exists())

    def test_given_extended_fixture__when_validating_catalog__then_exact_three_table_shapes_acls_constraints_and_indexes(self):
        with patch.object(f20, 'require_owner', return_value='123-1'), patch.object(f20, 'fixture_catalog', return_value='t') as catalog:
            self.assertEqual('123-1', f20.require_fixture())
        self.assertEqual(9, catalog.call_count)
        queries = [c.args[0] for c in catalog.call_args_list]
        self.assertTrue(all(q.startswith('SELECT ') for q in queries))
        self.assertIn("column_name IN ('member_id','blocked_id','created_at')", queries[7])
        self.assertIn("column_name IN ('user_id')", queries[1]); self.assertIn('=(false)', queries[4])
        for fragment in ('pg_get_serial_sequence', 'pawbridge_f3_writer', 'relpersistence', 'confdeltype',
                         'convalidated', 'condeferrable', 'idx_private_note_blocked_member', 'indisvalid', 'conkey'):
            self.assertIn(fragment, '\n'.join(queries))

    def test_given_catalog_shape_acl_or_constraint_mismatch__when_validating__then_reject_without_mutation(self):
        for bad in ('shape', 'acl', 'constraints'):
            with patch.object(f20, 'require_owner', return_value='123-1'), patch.object(f20, 'fixture_catalog', side_effect=lambda q, stage: 'f' if stage == bad else 't'), \
                    patch.object(f20.f3, 'sql') as sql:
                with self.assertRaises(SystemExit): f20.require_fixture()
                sql.assert_not_called()

    def test_given_catalog_failure__when_recording__then_bounded_masked_primary_only_with_f20_tag(self):
        failure = subprocess.CalledProcessError(1, ['private command'], output='private stdout', stderr='ERROR: private-token ' + 'x' * 900 + '\nDETAIL: private detail')
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'F3_TEST_JWT_SECRET': 'private-token'}, clear=True), \
                patch.object(f20, 'EVIDENCE', Path(directory).resolve()), patch.object(f20.f3, 'sql', side_effect=failure):
            with self.assertRaises(SystemExit): f20.fixture_catalog('SELECT true', 'acl')
            value = (Path(directory) / 'f20-fixture-catalog-error.txt').read_text()
            self.assertIn('stage=f20-block-list-acl', value); self.assertIn('[redacted]', value); self.assertLess(len(value), 600)
            for forbidden in ('private-token', 'private stdout', 'private detail', 'private command', 'SELECT true'): self.assertNotIn(forbidden, value)

    def test_given_fresh_owned_ids__when_seeding__then_atomic_four_users_fourteen_members_thirteen_blocks_one_pending(self):
        with patch.object(f20, 'require_fixture', return_value='123-1'), patch.object(f20.f3, 'sql', side_effect=['0', '']) as sql:
            self.assertEqual(f20.OWNED, f20.prepare_business_rows())
        setup = sql.call_args.args[0]
        self.assertTrue(setup.startswith('BEGIN;')); self.assertTrue(setup.endswith('COMMIT;'))
        for identity in f20.IDS: self.assertIn(str(identity), sql.call_args_list[0].args[0])
        self.assertEqual((4, 14, 13, 1), tuple(map(len, f20.OWNED)))
        self.assertIn("'  '", setup); self.assertIn(f20.STORED_TIME, setup)
        self.assertEqual(4, setup.count('INSERT INTO ')); self.assertNotRegex(setup, r'\b42\b|UPDATE |ON CONFLICT|TRUNCATE|DROP ')

    def test_given_existing_owned_id__when_seeding__then_refuse_before_insert(self):
        with patch.object(f20, 'require_fixture', return_value='123-1'), patch.object(f20.f3, 'sql', return_value='1') as sql:
            with self.assertRaises(SystemExit): f20.prepare_business_rows()
            self.assertEqual(1, sql.call_count); self.assertTrue(sql.call_args.args[0].startswith('SELECT '))

    def test_given_successful_owned_keys__when_cleaning__then_exact_pairs_pending_members_users_in_fk_order(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f20, 'require_owner', return_value='123-1') as owner, patch.object(f20.f3, 'sql') as sql:
            f20.cleanup_business_rows(f20.OWNED)
        queries = [c.args[0] for c in sql.call_args_list]
        self.assertEqual(4, owner.call_count)
        self.assertEqual(['private_note_blocks', 'contact_deletions', 'private_note_members', 'users'], [q.split('DELETE FROM ', 1)[1].split()[0].split('.')[1] for q in queries])
        for first, second in f20.BLOCKS: self.assertIn('(' + str(first) + ',' + str(second) + ')', queries[0])
        self.assertNotIn(str(f20.REVERSE), queries[3]); self.assertIn('email', queries[3])
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f20.f3, 'sql') as sql:
            with self.assertRaises(ValueError): f20.cleanup_business_rows((42,))
            sql.assert_not_called()

    def test_given_timestamp_revoked_and_body_failure__when_unwinding__then_safe_owner_restore_before_full_fixture(self):
        events = []
        with patch.object(f20, 'require_fixture', side_effect=lambda: events.append('full') or '123-1'), \
                patch.object(f20, 'require_owner', side_effect=lambda: events.append('owner') or '123-1'), \
                patch.object(f20.f3, 'sql', side_effect=lambda q: events.append(q.split()[0]) or ''):
            with self.assertRaisesRegex(AssertionError, 'body'):
                with f20.denied_select('pawbridge_community.private_note_blocks', 'created_at'): raise AssertionError('body')
        self.assertEqual(['full', 'REVOKE', 'owner', 'GRANT', 'full'], events)

    def test_given_user_nickname_revoked__when_unwinding__then_marker_identity_restore_without_user_seven_chain(self):
        with patch.object(f20, 'require_fixture', return_value='123-1') as full, patch.object(f20, 'require_owner') as unsafe, \
                patch.object(f20.f19, 'require_identity', return_value='123-1') as identity, patch.object(f20, 'require_f19_evidence') as receipt, patch.object(f20.f3, 'sql') as sql:
            with f20.denied_select('pawbridge_user.users', 'nickname'): pass
            unsafe.assert_not_called(); identity.assert_called_once_with('123-1'); receipt.assert_called_once_with('123-1')
            self.assertEqual(2, full.call_count); self.assertEqual(['REVOKE', 'GRANT'], [c.args[0].split()[0] for c in sql.call_args_list])

    def test_given_wrong_owner_while_user_acl_revoked__when_unwinding__then_stop_before_grant(self):
        with patch.object(f20, 'require_fixture', return_value='123-1'), patch.object(f20.f19, 'require_identity', side_effect=SystemExit('wrong owner')), patch.object(f20.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'wrong owner'):
                with f20.denied_select('pawbridge_user.users', 'nickname'): pass
            self.assertEqual(['REVOKE'], [c.args[0].split()[0] for c in sql.call_args_list])

    def test_given_token_path_symlink_or_mode_mismatch__when_loading__then_refuse(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); path = root / 'f3-tokens-123-1.json'; path.write_text('{}'); path.chmod(0o600)
            env = dict(HOSTED, RUNNER_TEMP=str(root), F3_TEST_TOKENS_PATH=str(path))
            alias = root / 'alias.json'; alias.symlink_to(path)
            for changed in ({'F3_TEST_TOKENS_PATH': str(root / 'foreign.json')}, {'F3_TEST_TOKENS_PATH': str(alias)}):
                with patch.dict(os.environ, dict(env, **changed), clear=True), self.assertRaises(SystemExit): f20.load_tokens()
            path.chmod(0o644)
            with patch.dict(os.environ, env, clear=True), self.assertRaisesRegex(SystemExit, '0600'): f20.load_tokens()

    def test_given_token_map__when_loading__then_only_f20_caller_is_fresh_and_old_expired_keys_are_preserved(self):
        now = time.time(); valid = {'F20_CALLER': token(f20.CALLER, now + 600), 'expired': 'old-expired', 'F19_ADMIN': 'old-stale', 'F17_CALLER': 'old-stale'}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); path = root / 'f3-tokens-123-1.json'
            with patch.dict(os.environ, dict(HOSTED, RUNNER_TEMP=str(root), F3_TEST_TOKENS_PATH=str(path)), clear=True):
                for value in ({}, dict(valid, F20_CALLER=token(f20.CALLER, now + 30)), dict(valid, F20_CALLER=token(42, now + 600)),
                              dict(valid, F20_CALLER=token(f20.CALLER, now + 600, 'ROLE_ADMIN')), dict(valid, F20_CALLER=token(float(f20.CALLER), now + 600))):
                    path.write_text(json.dumps(value)); path.chmod(0o600)
                    with self.assertRaises(SystemExit): f20.load_tokens()
                path.write_text(json.dumps(valid)); path.chmod(0o600); before = path.read_bytes()
                self.assertEqual({'F20_CALLER': valid['F20_CALLER']}, f20.load_tokens()); self.assertEqual(before, path.read_bytes())

    def test_given_xml__when_reporting__then_exact_utc_classes_and_one_named_seoul_timestamp_case_without_child_failures(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f20, 'ROOT', Path(directory).resolve()):
            def report(task, name, count):
                cases = ''.join('<testcase classname="' + name + '" name="' + (f20.SEOUL_METHOD + '()' if task == f20.SEOUL_TASK else 'case' + str(i)) + '"/>' for i in range(count))
                return '<testsuite name="' + name + '" tests="' + str(count) + '" failures="0" errors="0" skipped="0">' + cases + '</testsuite>'
            with self.assertRaises(SystemExit): f20.verify_results()
            for task, classes in f20.REPORTS.items():
                root = f20.ROOT / 'boot/build/test-results' / task; root.mkdir(parents=True)
                for name, count in classes.items(): (root / ('TEST-' + name + '.xml')).write_text(report(task, name, count))
            self.assertEqual(2, len(f20.verify_results()))
            target = f20.ROOT / 'boot/build/test-results' / f20.SEOUL_TASK / ('TEST-' + f20.PG_CLASS + '.xml')
            good = target.read_text()
            for value in (good.replace(f20.SEOUL_METHOD, 'wrongMethod'), good.replace('tests="1"', 'tests="0"'),
                          good.replace('skipped="0"', 'skipped="1"'), good.replace('classname="com.', 'classname="wrong.'),
                          *(good.replace('/></testsuite>', '><' + tag + '/></testcase></testsuite>') for tag in ('failure', 'error', 'skipped'))):
                target.write_text(value)
                with self.assertRaises(SystemExit): f20.verify_results()
            target.write_text(good); extra = target.parent / ('TEST-' + f20.MVC_CLASS + '.xml'); extra.write_text('extra')
            with self.assertRaisesRegex(SystemExit, 'exact UTC/Seoul'): f20.verify_results()

    def test_given_mocked_packaged_journey__when_observing__then_ten_requests_get_nine_and_exact_recovery_receipt(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            refreshed_at = []; current = f20.load_tokens
            def fresh(): refreshed_at.append(len(requests)); return current()
            with patch.object(f20, 'load_tokens', side_effect=fresh): f20.smoke()
            result = json.loads((evidence / 'f20-packaged-result.txt').read_text())
            self.assertEqual('passed', result['status']); self.assertEqual(10, len(requests)); self.assertEqual(9, result['block_list_get_checks'])
            self.assertEqual((5, 4, 1), (result['successful_checks'], result['rejected_checks'], result['denied_route_checks']))
            self.assertEqual([0, 0, 1, 2, 3, 4, 5, 6, 7, 9], refreshed_at)
            self.assertEqual(1, start.call_count); self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f20.OWNED)
            self.assertIn('page=-1', requests[5][0]); self.assertNotIn('Authorization', requests[8][1]['headers'])
            self.assertEqual('POST', requests[9][1]['method']); self.assertEqual(2, result['shutdown_pools']); self.assertEqual(0, result['remaining_application_connections'])
            self.assertNotIn('DAILY_VISITORS_ENABLED', start.call_args.args[1])

    def test_given_stale_token_after_before_snapshot__when_request_is_due__then_no_request_stop_children_cleanup_and_incomplete(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, _, start, stop, cleanup, seed):
            state = {'started': False, 'complete': False}; events = []
            storage, database, tokens = f20.f3.expect, f20.database_snapshot, f20.load_tokens
            def before_storage(*args, **kwargs):
                value = storage(*args, **kwargs)
                if seed.called: state['started'] = True
                return value
            def before_database():
                value = database()
                if state['started']: state['complete'] = True; events.append('snapshot')
                return value
            def fresh():
                if state['complete']: events.append('stale'); raise SystemExit('stale after snapshot')
                events.append('mask'); return tokens()
            with patch.object(f20.f3, 'expect', side_effect=before_storage), patch.object(f20, 'database_snapshot', side_effect=before_database), \
                    patch.object(f20, 'load_tokens', side_effect=fresh), self.assertRaisesRegex(SystemExit, 'stale after snapshot'): f20.smoke()
            self.assertEqual(['mask', 'snapshot', 'stale'], events); self.assertEqual([], requests)
            self.assertEqual(2, stop.call_count); seed.assert_called_once_with(); cleanup.assert_called_once_with(f20.OWNED)
            self.assertEqual('incomplete', json.loads((evidence / 'f20-packaged-result.txt').read_text())['status'])

    def test_given_failure_while_either_acl_revoked__when_unwinding__then_restore_stop_children_cleanup_incomplete(self):
        for number, column in ((4, 'created_at'), (6, 'nickname'), (7, 'nickname')):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, failure=number) as (evidence, requests, sql, start, stop, cleanup, seed):
                with self.assertRaisesRegex(AssertionError, 'mocked request failed'): f20.smoke()
                self.assertIn('GRANT SELECT(' + column + ')', '\n'.join(c.args[0] for c in sql.call_args_list))
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f20.OWNED)
                self.assertEqual('incomplete', json.loads((evidence / 'f20-packaged-result.txt').read_text())['status'])

    def test_given_cache_cookie_db_or_s3_fault__when_observing__then_fail_and_cleanup(self):
        for fault in ({'cache': 'public'}, {'cookie': True}, {'mutation': True}, {'storage_mutation': True}):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **fault) as (_, _, _, _, stop, cleanup, seed):
                with self.assertRaises(AssertionError): f20.smoke()
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f20.OWNED)

    def test_given_startup_mutation__when_starting__then_seed_zero_and_stop_children(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, startup_mutation=True) as (_, requests, _, _, stop, cleanup, seed):
            with self.assertRaisesRegex(AssertionError, 'startup changed'): f20.smoke()
            seed.assert_not_called(); cleanup.assert_not_called(); self.assertEqual([], requests); self.assertEqual(2, stop.call_count)

    def test_given_workflow__when_adding_f20__then_f19_first_single_full_utc_thin_seoul_and_always_cleanup(self):
        workflow = (f20.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('- name: Verify F20 exact extended ACL')
        self.assertLess(workflow.index('python3 scripts/f19_ci.py smoke'), boundary); stage = workflow[boundary:]
        for first, second in (('test_f20_ci_guards.py', 'python3 scripts/f20_ci.py prepare'), ('python3 scripts/f20_ci.py prepare', ':boot:' + f20.TASK),
                              (':boot:' + f20.SEOUL_TASK, 'python3 scripts/f20_ci.py results'), ('python3 scripts/f20_ci.py results', 'python3 scripts/f20_ci.py smoke')):
            self.assertLess(stage.index(first), stage.index(second))
        self.assertEqual(1, stage.count(':boot:' + f20.TASK + ' ')); self.assertEqual(1, stage.count(':boot:' + f20.SEOUL_TASK))
        cleanup = stage.index('- name: Remove only the owned disposable fixture')
        self.assertIn('if: always()', stage[cleanup:cleanup + 180]); self.assertNotRegex(stage[:cleanup], r'python3 scripts/f(?:[2-9]|1[0-9])_ci\.py (?:prepare|smoke)')
        self.assertNotIn('f20_fixture', workflow)
        for name in ('f20-oracle-sha256.txt', 'f20-fixture-reuse.txt', 'f20-helper-refusal.txt'): self.assertIn(name, workflow)

    def test_given_f20_legacy_artifacts__when_filtering_jar__then_reject_without_widening_validator_exception(self):
        text = (f20.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        denied = next(line for line in text.splitlines() if "if grep -E '" in line).split("if grep -E '", 1)[1].split("' build/ci/", 1)[0]
        allowed = next(line for line in text.splitlines() if "| grep -Ev '" in line).split("| grep -Ev '", 1)[1].split("'; then", 1)[0]
        for value in ('BOOT-INF/classes/f20LegacyOracle/Input.class', 'BOOT-INF/classes/com/pawbridge/communityservice/contact/F20LegacyBlockListService.class', 'test/f20LegacyOracle/hibernate-validator-8.0.3.Final.jar'):
            self.assertIsNotNone(re.search(denied, value)); self.assertIsNone(re.search(allowed, value))
        self.assertIsNotNone(re.search(allowed, 'BOOT-INF/lib/hibernate-validator-8.0.3.Final.jar'))

    def test_given_wrong_extractor_paths__when_extracting__then_no_git_or_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory)
            for target, report in ((root / 'wrong', evidence), (output, root / 'wrong')):
                with patch.object(oracle.subprocess, 'check_output') as git, patch.object(Path, 'mkdir') as mkdir, patch.object(Path, 'write_bytes') as write:
                    with self.assertRaisesRegex(SystemExit, 'non-dedicated F20'): oracle.extract(root, target, report)
                    git.assert_not_called(); mkdir.assert_not_called(); write.assert_not_called()

    def test_given_late_source_tamper__when_extracting__then_all_seventeen_validate_before_write(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}; sources[next(reversed(sources))] = b'tampered'
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory)
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                with self.assertRaisesRegex(SystemExit, 'checksum mismatch'): oracle.extract(root, output, evidence)
                write.assert_not_called(); self.assertFalse(output.exists()); self.assertFalse(evidence.exists())

    def test_given_raw_or_adapted_shared_tamper__when_extracting__then_preserve_all_thirteen_reused_artifacts(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        for relative in (*oracle.SHARED.values(), oracle.F18_ADAPTED_PATH):
            with tempfile.TemporaryDirectory() as directory:
                root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
                target = root / relative; target.write_bytes(b'preserve-other-writer')
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaises(SystemExit): oracle.extract(root, output, evidence)
                    write.assert_not_called()
                self.assertEqual(b'preserve-other-writer', target.read_bytes()); self.assertFalse(output.exists()); self.assertFalse(evidence.exists())

    def test_given_selected_method_literal_annotation_or_v6_tamper__when_extracting__then_no_source_or_receipt_write(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        selected = (*oracle.SERVICE_UNITS, oracle.CONTROLLER_UNIT, *oracle.ADVICE_UNITS, *oracle.SOURCE_EVIDENCE_UNITS,
                    oracle.PAGE_SIZE, oracle.CONTROLLER_ANNOTATIONS, oracle.ADVICE_ANNOTATIONS, oracle.LOGGER,
                    oracle.USER_LIMIT, oracle.USER_CONTROLLER_ANNOTATIONS, *oracle.MODEL_UNITS, oracle.V6_BLOCKS)
        for item in selected:
            with tempfile.TemporaryDirectory() as directory:
                root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]), patch.dict(item, sha256='changed'), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaises(SystemExit): oracle.extract(root, output, evidence)
                    write.assert_not_called(); self.assertFalse(output.exists()); self.assertFalse(evidence.exists())

    def test_given_any_output_or_receipt_collision__when_extracting__then_reject_before_all_writes(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        for name in (*oracle.OUTPUTS, 'receipt'):
            with tempfile.TemporaryDirectory() as directory:
                root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
                target = evidence if name == 'receipt' else output / name
                target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(b'preserve-existing')
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaisesRegex(SystemExit, 'overwrite differing'): oracle.extract(root, output, evidence)
                    write.assert_not_called()
                self.assertEqual(b'preserve-existing', target.read_bytes())

    def test_given_duplicate_fqn_or_non_directory_receipt_parent__when_extracting__then_reject_before_write(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        for kind in ('duplicate', 'receipt-parent'):
            with tempfile.TemporaryDirectory() as directory:
                root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
                if kind == 'duplicate':
                    target = root / 'backend-app/boot/src/compatibilityTest/java/other/F20LegacyBlockListService.java'
                    target.parent.mkdir(parents=True, exist_ok=True); target.write_text('package com.pawbridge.communityservice.contact; public class F20LegacyBlockListService {}')
                else:
                    evidence.parent.parent.mkdir(parents=True, exist_ok=True); evidence.parent.write_text('preserve-parent-file')
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaises(SystemExit): oracle.extract(root, output, evidence)
                    write.assert_not_called()

    def test_given_actual_fixed_sources__when_extracting__then_only_three_unique_outputs_keep_all_verbatim_units_and_shared_logger(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
            before = (root / oracle.F18_ADAPTED_PATH).read_bytes()
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]): oracle.extract(root, output, evidence)
            self.assertEqual((17, 12, 3), (len(sources), len(oracle.SHARED), len(list(output.rglob('*.java')))))
            service, controller, advice = [(output / name).read_text() for name in oracle.OUTPUTS]
            for item in oracle.SERVICE_UNITS: self.assertIn(oracle.checked_method(sources[oracle.SERVICE].decode(), item), service)
            self.assertIn(oracle.checked_method(sources[oracle.CONTROLLER].decode(), oracle.CONTROLLER_UNIT), controller)
            for item in oracle.ADVICE_UNITS: self.assertIn(oracle.checked_method(sources[oracle.ADVICE].decode(), item), advice)
            self.assertIn(oracle.LOGGER['verbatim_utf8'], advice); self.assertIn(oracle.F20_ADVICE_ANNOTATIONS, advice)
            self.assertIn('public class F20LegacyBlockListExceptionAdvice', advice)
            self.assertEqual(before, (root / oracle.F18_ADAPTED_PATH).read_bytes()); self.assertEqual(17, evidence.read_text().count('  wrapper-method '))
            for name in ('PrivateNoteExceptionAdvice.java', 'PrivateNoteHttpBoundary.java', 'PrivateNoteRepository.java'): self.assertFalse((output / 'com/pawbridge/communityservice/contact' / name).exists())
            self.assertIn('source_sha=' + oracle.SOURCE, evidence.read_text()); self.assertIn('source evidence', evidence.read_text())

    def test_given_sparse_checkout_without_legacy_dirs__when_loading_sources__then_only_read_pinned_objects_and_reject_tamper(self):
        marker = f20.ROOT.parent / '.git'
        git_dir = Path(marker.read_text().removeprefix('gitdir:').strip()) if marker.is_file() else marker
        if not git_dir.is_absolute(): git_dir = (marker.parent / git_dir).resolve()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); (root / '.git').write_text('gitdir: ' + str(git_dir.resolve()) + '\n')
            for source in oracle.EXPECTED: self.assertTrue(pinned_source(source, root))
            self.assertFalse((root / 'user-service').exists()); self.assertFalse((root / 'community-service').exists())
            with patch.object(subprocess, 'check_output', return_value=b'changed'), self.assertRaisesRegex(AssertionError, 'checksum mismatch'):
                pinned_source(next(iter(oracle.EXPECTED)), root)


if __name__ == '__main__':
    print('F20 guards: mocked PostgreSQL/JAR/JVM/sockets; pinned inputs use read-only git show, no Git mutations.', flush=True)
    with redirect_stdout(io.StringIO()): unittest.main()
