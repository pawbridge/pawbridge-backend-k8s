"""F18 guards mock PG/JAR/JVM/sockets; fixed sparse inputs use read-only Git objects."""
from contextlib import ExitStack, contextmanager, redirect_stdout
import base64
import hashlib
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
import urllib.parse

import f18_ci as f18
import prepare_f18_legacy_oracle as oracle
from test_f17_ci_guards import HOSTED


def pinned_source(path, repository=None):
    if path not in oracle.EXPECTED: raise ValueError('F18 guard source must have an explicit immutable hash')
    content = subprocess.check_output(['git', 'show', oracle.SOURCE + ':' + path], cwd=repository or f18.ROOT.parent)
    if hashlib.sha256(content).hexdigest() != oracle.EXPECTED[path]:
        raise AssertionError('Fixed F18 guard input checksum mismatch: ' + path)
    return content


def oracle_paths(directory):
    root = Path(directory).resolve()
    return root, root / 'backend-app/boot/build/generated/sources/f18LegacyOracle/java', root / 'backend-app/build/ci/f18-oracle-sha256.txt'


def shared_inputs(root, sources):
    for source, relative in oracle.SHARED.items():
        target = root / relative; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(sources[source])


def token(identity, expiry, role='ROLE_USER'):
    payload = base64.urlsafe_b64encode(json.dumps({'userId': identity, 'role': role, 'exp': expiry}).encode()).decode().rstrip('=')
    return 'synthetic.' + payload + '.signature'


@contextmanager
def packaged_harness(directory, failure=None, mutation=False, cache='no-store', cookie=False, storage_mutation=False, startup_mutation=False):
    root = Path(directory); evidence = root / 'evidence'
    jar = root / 'boot/build/libs/pawbridge-backend-app.jar'; jar.parent.mkdir(parents=True); jar.write_bytes(b'mocked-jar')
    requests = []; started = False
    tokens = {'F18_CALLER': 'private-caller-token', 'expired': 'private-expired-token'}
    statuses = {3: 400, 4: 400, 5: 400, 6: 400, 7: 503, 9: 503, 11: 401, 12: 401, 13: 403}
    messages = {3: f18.MISSING, 4: f18.INVALID_MEMBERS, 5: f18.INVALID_BINDING, 6: f18.INVALID_MEMBERS,
                7: f18.MEMBER_UNAVAILABLE, 9: f18.STORAGE_UNAVAILABLE, 11: '인증 토큰이 필요합니다.',
                12: '유효하지 않거나 만료된 토큰입니다.', 13: '접근할 수 없습니다.'}
    def request(base, path, **kwargs):
        requests.append((path, kwargs)); number = len(requests)
        if number == failure: raise AssertionError('mocked request failed')
        status = statuses.get(number, 200); message = messages.get(number, f18.SUCCESS)
        response = {'code': status, 'message': message, 'data': None} if status == 200 or number in (3, 11, 12, 13) else {'message': message}
        if status == 200: response['data'] = {'memberIds': [] if number == 2 else [f18.FIRST, f18.SECOND]}
        headers = {'Cache-Control': cache}
        if cookie: headers['Set-Cookie'] = 'forbidden'
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
        for item in (patch.dict(os.environ, env, clear=True), patch.object(f18, 'ROOT', root), patch.object(f18, 'EVIDENCE', evidence),
                     patch.object(f18, 'require_fixture', return_value='123-1'), patch.object(f18, 'require_owner', return_value='123-1'),
                     patch.object(f18, 'load_tokens', return_value=tokens),
                     patch.object(f18, 'database_snapshot', side_effect=lambda: {'state': 'changed' if (mutation and requests) or (startup_mutation and started) else 'same'}),
                     patch.object(f18.f3, 'request', side_effect=request), patch.object(f18.f3, 'expect', side_effect=storage),
                     patch.object(f18.f4, 'fresh_log', return_value='Shutdown completed. Shutdown completed. Graceful shutdown complete')):
            stack.enter_context(item)
        sql = stack.enter_context(patch.object(f18.f3, 'sql', return_value='0'))
        seed = stack.enter_context(patch.object(f18, 'prepare_business_rows', return_value=f18.IDS))
        start_mock = stack.enter_context(patch.object(f18.f4, 'start_ready_children', side_effect=start))
        stop = stack.enter_context(patch.object(f18.f3, 'stop_owned_child', return_value=0))
        cleanup = stack.enter_context(patch.object(f18, 'cleanup_business_rows'))
        yield evidence, requests, sql, start_mock, stop, cleanup, seed


class F18GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_actions__then_refuse_before_resources(self):
        for action in (f18.prepare, f18.require_fixture, f18.require_owner, f18.prepare_business_rows,
                       lambda: f18.cleanup_business_rows(f18.IDS), f18.smoke, f18.verify_results, f18.load_tokens):
            with self.subTest(action=action), patch.dict(os.environ, {}, clear=True), patch.object(f18.f3, 'sql') as sql, \
                    patch.object(f18.f2, 'command') as command, patch.object(subprocess, 'check_output') as git, \
                    patch.object(f18.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'): action()
                sql.assert_not_called(); command.assert_not_called(); git.assert_not_called(); start.assert_not_called()

    def test_given_wrong_target_or_reader__when_preparing__then_no_command_or_evidence(self):
        for changed in ({'F2_TEST_JDBC_URL': 'jdbc:postgresql://production:5432/pawbridge_f2_ci'}, {'F2_TEST_DB_USER': 'postgres'}):
            with patch.dict(os.environ, dict(HOSTED, **changed), clear=True), patch.object(f18.f2, 'command') as command, patch.object(Path, 'write_text') as write:
                with self.assertRaises(SystemExit): f18.prepare()
                command.assert_not_called(); write.assert_not_called()

    def test_given_invalid_f17_chain__when_preparing__then_no_mutation_or_receipt(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f18, 'require_fixture', side_effect=SystemExit('F17 mismatch')), \
                patch.object(f18.f3, 'sql') as sql, patch.object(Path, 'write_text') as write:
            with self.assertRaisesRegex(SystemExit, 'F17 mismatch'): f18.prepare()
            sql.assert_not_called(); write.assert_not_called()

    def test_given_existing_fixture__when_preparing__then_only_read_validate_and_reuse_owner_without_schema_grants_or_marker(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f18, 'EVIDENCE', Path(directory)), \
                patch.object(f18, 'require_fixture', return_value='123-1') as fixture, patch.object(f18.f3, 'sql') as sql, \
                patch.object(f18.f2, 'export_environment') as export:
            f18.prepare(); fixture.assert_called_once_with(); sql.assert_not_called(); export.assert_not_called()
            target = Path(directory) / 'f18-fixture-reuse.txt'
            self.assertIn('owner=123-1', target.read_text()); self.assertIn('new_business_ddl=0', target.read_text())
            target.write_text('preserve-other-proof')
            with self.assertRaisesRegex(SystemExit, 'differing F18'): f18.prepare()
            self.assertEqual('preserve-other-proof', target.read_text())

    def test_given_f18_helpers__when_reusing_f17__then_same_owner_fixture_fullsnapshot_and_no_fixture_sql(self):
        self.assertIs(f18.require_fixture, f18.f17.require_fixture)
        self.assertIs(f18.require_owner, f18.f17.require_owner)
        self.assertIs(f18.database_snapshot, f18.f17.database_snapshot)
        self.assertFalse((f18.ROOT / 'scripts/f18_fixture.sql').exists())
        source = inspect.getsource(f18)
        for forbidden in ('CREATE TABLE', 'CREATE SCHEMA', 'CREATE INDEX', 'CREATE SEQUENCE', 'F18_CI_OWNER', 'export_environment', 'f17.prepare(', 'f17.smoke('):
            self.assertNotIn(forbidden, source)

    def test_given_fresh_owned_rows__when_seeding__then_one_user_four_members_three_blocks_and_no_target_user_or_user42_update(self):
        with patch.object(f18, 'require_fixture', return_value='123-1'), patch.object(f18.f3, 'sql', side_effect=['0', '']) as sql:
            self.assertEqual(f18.IDS, f18.prepare_business_rows())
        preflight, setup = [c.args[0] for c in sql.call_args_list]
        self.assertNotIn(str(f18.CALLER), preflight)
        for identity in f18.IDS[1:]: self.assertIn(str(identity), preflight)
        self.assertIn('BEGIN;', setup); self.assertIn('COMMIT;', setup); self.assertEqual(1, setup.count('INSERT INTO pawbridge_user.users'))
        self.assertEqual(1, setup.count('INSERT INTO pawbridge_community.private_note_members'))
        self.assertEqual(1, setup.count('INSERT INTO pawbridge_community.private_note_blocks'))
        self.assertNotIn('contact_deletions', setup); self.assertNotRegex(setup, r'\b42\b|UPDATE |ON CONFLICT|TRUNCATE|DROP ')

    def test_given_existing_target_user__when_seeding__then_refuse_before_any_insert(self):
        with patch.object(f18, 'require_fixture', return_value='123-1'), patch.object(f18.f3, 'sql', return_value='1') as sql:
            with self.assertRaisesRegex(SystemExit, 'pre-existing User'): f18.prepare_business_rows()
        self.assertEqual(1, sql.call_count); self.assertTrue(sql.call_args.args[0].startswith('SELECT '))

    def test_given_successfully_inserted_keys__when_cleaning__then_delete_only_three_pairs_members_and_exact_caller_in_fk_order(self):
        with patch.object(f18, 'require_owner', return_value='123-1') as owner, patch.object(f18.f3, 'sql') as sql:
            f18.cleanup_business_rows(f18.IDS)
        queries = [c.args[0] for c in sql.call_args_list]
        self.assertEqual(3, owner.call_count)
        self.assertEqual(['private_note_blocks', 'private_note_members', 'users'], [q.split('DELETE FROM ', 1)[1].split()[0].split('.')[1] for q in queries])
        for first, second in f18.BLOCKS: self.assertIn('(' + str(first) + ',' + str(second) + ')', queries[0])
        self.assertIn("AND email='f18-caller@fixture.invalid'", queries[2])
        self.assertNotIn('contact_deletions', '\n'.join(queries))
        with patch.object(f18, 'require_owner') as owner, patch.object(f18.f3, 'sql') as sql:
            with self.assertRaises(ValueError): f18.cleanup_business_rows((42,))
            owner.assert_not_called(); sql.assert_not_called()

    def test_given_token_path_symlink_or_mode_mismatch__when_loading__then_refuse(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / 'f3-tokens-123-1.json'; path.write_text('{}'); path.chmod(0o600)
            env = dict(HOSTED, RUNNER_TEMP=str(root), F3_TEST_TOKENS_PATH=str(path))
            alias = root / 'alias.json'; alias.symlink_to(path)
            for changed in ({'F3_TEST_TOKENS_PATH': str(root / 'foreign.json')}, {'F3_TEST_TOKENS_PATH': str(alias)}):
                with patch.dict(os.environ, dict(env, **changed), clear=True), self.assertRaises(SystemExit): f18.load_tokens()
            path.chmod(0o644)
            with patch.dict(os.environ, env, clear=True), self.assertRaisesRegex(SystemExit, '0600'): f18.load_tokens()

    def test_given_token_map__when_loading__then_only_used_caller_fresh_and_expired_same_caller_without_old_ttl_or_rewrite(self):
        now = time.time()
        valid = {'F18_CALLER': token(f18.CALLER, now + 600), 'expired': token(f18.CALLER, now - 60),
                 'F17_CALLER': 'unused-stale', 'ROLE_USER': token(42, now - 9999), 'F14_OTHER': 'preserved'}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / 'f3-tokens-123-1.json'
            with patch.dict(os.environ, dict(HOSTED, RUNNER_TEMP=str(root), F3_TEST_TOKENS_PATH=str(path)), clear=True):
                for content in ({}, dict(valid, F18_CALLER=token(f18.CALLER, now + 30)), dict(valid, F18_CALLER=token(42, now + 600)),
                                dict(valid, F18_CALLER=token(f18.CALLER, now + 600, 'ROLE_ADMIN')), dict(valid, F18_CALLER=token(float(f18.CALLER), now + 600)),
                                dict(valid, expired=token(42, now - 60)), dict(valid, expired=token(f18.CALLER, now + 600)), dict(valid, F18_CALLER=7)):
                    path.write_text(json.dumps(content)); path.chmod(0o600)
                    with self.assertRaises(SystemExit): f18.load_tokens()
                path.write_text(json.dumps(valid)); path.chmod(0o600); before = path.read_bytes()
                self.assertEqual({key: valid[key] for key in ('F18_CALLER', 'expired')}, f18.load_tokens())
                self.assertEqual(before, path.read_bytes())

    def test_given_missing_empty_failed_skipped_or_inconsistent_xml__when_reporting__then_require_exact_two_executed_classes(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f18, 'ROOT', Path(directory)):
            root = Path(directory) / 'boot/build/test-results' / f18.TASK; root.mkdir(parents=True)
            def valid(name):
                count = f18.EXPECTED_TESTS.get(name, 1)
                return ('<testsuite name="' + name + '" tests="' + str(count) + '" failures="0" errors="0" skipped="0">'
                        + ''.join('<testcase classname="' + name + '" name="contract' + str(i) + '"/>' for i in range(count)) + '</testsuite>')
            with self.assertRaises(SystemExit): f18.verify_results()
            for name in f18.CLASSES: (root / ('TEST-' + name + '.xml')).write_text(valid(name))
            path = root / ('TEST-' + f18.CLASSES[0] + '.xml'); good = valid(f18.CLASSES[0])
            for text in ('broken', good.replace('tests="7"', 'tests="0"'), good.replace('tests="7"', 'tests="6"'), good.replace('errors="0"', 'errors="1"'),
                         good.replace('skipped="0"', 'skipped="1"'), good.replace('classname="com.', 'classname="wrong.'),
                         *(good.replace('/></testsuite>', '><' + tag + '/></testcase></testsuite>') for tag in ('failure', 'error', 'skipped'))):
                path.write_text(text)
                with self.assertRaises(SystemExit): f18.verify_results()
            path.write_text(good); extra = root / 'TEST-extra.xml'; extra.write_text(valid('extra'))
            with self.assertRaisesRegex(SystemExit, 'exact two-class'): f18.verify_results()
            extra.unlink(); self.assertEqual(2, len(f18.verify_results()))

    def test_given_mocked_jar_journey__when_observing__then_exact_mixed_bodies_minimal_requests_and_two_pool_receipt(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup, seed):
            f18.smoke(); result = json.loads((evidence / 'f18-packaged-result.txt').read_text())
            self.assertEqual('passed', result['status']); self.assertEqual(12, result['blocked_authors_get_checks'])
            self.assertEqual(4, result['successful_checks']); self.assertEqual(8, result['rejected_checks']); self.assertEqual(1, result['denied_route_checks'])
            self.assertEqual(3, result['invalid_parameter_checks']); self.assertEqual(1, result['missing_parameter_checks'])
            self.assertEqual(1, result['member_unavailable_checks']); self.assertEqual(1, result['storage_unavailable_checks'])
            self.assertEqual(13, len(requests)); self.assertEqual(1, start.call_count); self.assertEqual(2, stop.call_count)
            cleanup.assert_called_once_with(f18.IDS); self.assertEqual(1, result['shutdown_pools']); self.assertEqual(0, result['remaining_application_connections'])
            self.assertEqual('4', start.call_args.args[1]['PAWBRIDGE_DB_POOL_MAX_SIZE'])
            self.assertNotIn('DAILY_VISITORS_ENABLED', start.call_args.args[1]); self.assertNotIn('DAILY_VISITORS_SIGNING_KEY', start.call_args.args[1])
            parsed = [urllib.parse.parse_qs(urllib.parse.urlsplit(path).query, keep_blank_values=True) for path, _ in requests]
            self.assertEqual({}, parsed[2]); self.assertEqual({'memberIds': ['']}, parsed[3])
            self.assertEqual([str(f18.FIRST) + ',' + str(f18.SECOND), str(f18.REVERSE)], parsed[4]['memberIds'])
            self.assertEqual({'memberIds': ['0']}, parsed[5]); self.assertNotIn('Authorization', requests[10][1]['headers'])

    def test_given_failure_while_acl_revoked__when_unwinding__then_restore_grant_stop_both_and_cleanup_only_owned_rows(self):
        for number, column in ((6, 'user_id'), (7, 'user_id'), (9, 'member_id')):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, failure=number) as (evidence, requests, sql, start, stop, cleanup, seed):
                with self.assertRaisesRegex(AssertionError, 'mocked request failed'): f18.smoke()
                self.assertIn('GRANT SELECT(' + column + ')', '\n'.join(c.args[0] for c in sql.call_args_list))
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f18.IDS)
                self.assertEqual('incomplete', json.loads((evidence / 'f18-packaged-result.txt').read_text())['status'])

    def test_given_wrong_cache_cookie_db_or_s3_mutation__when_observing__then_fail_and_cleanup(self):
        for fault in ({'cache': 'public'}, {'cookie': True}, {'mutation': True}, {'storage_mutation': True}):
            with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **fault) as (_, _, _, _, stop, cleanup, seed):
                with self.assertRaises(AssertionError): f18.smoke()
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(f18.IDS)

    def test_given_startup_mutation__when_starting__then_fail_before_seed_and_do_not_claim_owned_keys(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, startup_mutation=True) as (_, requests, _, _, stop, cleanup, seed):
            with self.assertRaisesRegex(AssertionError, 'startup changed'): f18.smoke()
            seed.assert_not_called(); cleanup.assert_not_called(); self.assertEqual([], requests); self.assertEqual(2, stop.call_count)

    def test_given_workflow__when_connecting_f18__then_keep_prior_stages_one_utc_task_and_always_cleanup_without_fixture_extension(self):
        workflow = (f18.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('- name: Verify F18 unchanged fixture')
        self.assertLess(workflow.index('python3 scripts/f17_ci.py smoke'), boundary)
        stage = workflow[boundary:]
        for first, second in (('test_f18_ci_guards.py', 'python3 scripts/f18_ci.py prepare'),
                              ('python3 scripts/f18_ci.py prepare', ':boot:blockedAuthorsCompatibilityTest'),
                              (':boot:blockedAuthorsCompatibilityTest', 'python3 scripts/f18_ci.py results'),
                              ('python3 scripts/f18_ci.py results', 'python3 scripts/f18_ci.py smoke')):
            self.assertLess(stage.index(first), stage.index(second))
        self.assertEqual(1, stage.count(':boot:blockedAuthorsCompatibilityTest')); self.assertNotIn('blockedAuthorsCompatibilitySeoulTest', workflow)
        cleanup = stage.index('- name: Remove only the owned disposable fixture')
        self.assertIn('if: always()', stage[cleanup:cleanup + 180]); self.assertIn('python3 scripts/f3_ci.py cleanup', stage[cleanup:cleanup + 180])
        self.assertNotRegex(stage[:cleanup], r'python3 scripts/f(?:[2-9]|1[0-7])_ci\.py (?:prepare|smoke)')
        self.assertNotIn('f18_fixture', workflow)
        for required in ('f18-oracle-sha256.txt', 'f18-fixture-reuse.txt', 'f18-helper-refusal.txt'): self.assertIn(required, workflow)

    def test_given_f18_test_artifacts__when_applying_jar_filter__then_reject_without_widening_validator_exception(self):
        text = (f18.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        denied = next(line for line in text.splitlines() if "if grep -E '" in line).split("if grep -E '", 1)[1].split("' build/ci/", 1)[0]
        allowed = next(line for line in text.splitlines() if "| grep -Ev '" in line).split("| grep -Ev '", 1)[1].split("'; then", 1)[0]
        for path in ('BOOT-INF/classes/f18LegacyOracle/Input.class', 'BOOT-INF/classes/com/pawbridge/communityservice/contact/PrivateNoteExceptionAdvice.class',
                     'test/f18LegacyOracle/hibernate-validator-8.0.3.Final.jar'):
            self.assertIsNotNone(re.search(denied, path)); self.assertIsNone(re.search(allowed, path))
        self.assertIsNotNone(re.search(allowed, 'BOOT-INF/lib/hibernate-validator-8.0.3.Final.jar'))

    def test_given_wrong_oracle_paths__when_extracting__then_refuse_before_git_or_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory)
            for target, report in ((root / 'outside', evidence), (output, root / 'outside')):
                with patch.object(oracle.subprocess, 'check_output') as git, patch.object(Path, 'mkdir') as mkdir, patch.object(Path, 'write_bytes') as write:
                    with self.assertRaisesRegex(SystemExit, 'non-dedicated F18'): oracle.extract(root, target, report)
                    git.assert_not_called(); mkdir.assert_not_called(); write.assert_not_called()

    def test_given_late_source_hash_tamper__when_extracting__then_validate_all_fifteen_before_any_write(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        last = next(reversed(sources)); sources[last] = b'tampered-last-input'
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory)
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                with self.assertRaisesRegex(SystemExit, 'checksum mismatch'): oracle.extract(root, output, evidence)
                write.assert_not_called(); self.assertFalse(output.exists()); self.assertFalse(evidence.exists())

    def test_given_any_shared_stage_byte_tamper__when_extracting__then_preserve_every_existing_shared_file_and_write_nothing(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        for source, relative in oracle.SHARED.items():
            with self.subTest(source=source), tempfile.TemporaryDirectory() as directory:
                root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
                target = root / relative; target.write_bytes(b'preserve-other-writer')
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaisesRegex(SystemExit, 'Shared generated source'): oracle.extract(root, output, evidence)
                    write.assert_not_called()
                self.assertEqual(b'preserve-other-writer', target.read_bytes()); self.assertFalse(output.exists()); self.assertFalse(evidence.exists())

    def test_given_method_annotation_or_logger_hash_tamper__when_extracting__then_preserve_all_outputs_and_evidence(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        units = (oracle.SERVICE_UNITS[0], oracle.CONTROLLER_UNIT, oracle.ADVICE_UNITS[0], oracle.GLOBAL_UNIT,
                 oracle.CONTROLLER_ANNOTATIONS, oracle.ADVICE_ANNOTATIONS, oracle.LOGGER)
        for unit in units:
            with tempfile.TemporaryDirectory() as directory:
                root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]), \
                        patch.dict(unit, sha256='changed'), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaises(SystemExit): oracle.extract(root, output, evidence)
                    write.assert_not_called(); self.assertFalse(output.exists()); self.assertFalse(evidence.exists())

    def test_given_late_output_or_evidence_collision__when_extracting__then_reject_before_all_source_and_receipt_writes(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        for kind in ('source', 'evidence'):
            with tempfile.TemporaryDirectory() as directory:
                root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
                target = output / 'com/pawbridge/communityservice/contact/PrivateNoteHttpBoundary.java' if kind == 'source' else evidence
                target.parent.mkdir(parents=True); target.write_bytes(b'preserve-existing')
                with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaisesRegex(SystemExit, 'overwrite differing'): oracle.extract(root, output, evidence)
                    write.assert_not_called()
                self.assertEqual(b'preserve-existing', target.read_bytes())
                self.assertFalse((output / 'com/pawbridge/communityservice/contact/F18LegacyBlockedAuthorsService.java').exists())

    def test_given_actual_fifteen_inputs__when_extracting__then_reuse_eleven_fqns_and_preserve_twelve_units_in_four_outputs(self):
        sources = {source: pinned_source(source) for source in oracle.EXPECTED}
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory); shared_inputs(root, sources)
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]): oracle.extract(root, output, evidence)
            self.assertEqual(15, len(sources)); self.assertEqual(11, len(oracle.SHARED)); self.assertEqual(4, len(list(output.rglob('*.java'))))
            prefix = output / 'com/pawbridge/communityservice/contact'
            service = (prefix / 'F18LegacyBlockedAuthorsService.java').read_text()
            controller = (prefix / 'F18LegacyBlockedAuthorsController.java').read_text()
            advice = (prefix / 'PrivateNoteExceptionAdvice.java').read_text()
            for unit in oracle.SERVICE_UNITS: self.assertIn(oracle.checked_unit(sources[oracle.SERVICE].decode(), unit), service)
            self.assertIn(oracle.checked_unit(sources[oracle.CONTROLLER].decode(), oracle.CONTROLLER_UNIT), controller)
            self.assertIn(oracle.CONTROLLER_ANNOTATIONS['verbatim_utf8'], controller)
            for unit in oracle.ADVICE_UNITS: self.assertIn(oracle.checked_unit(sources[oracle.ADVICE].decode(), unit), advice)
            self.assertIn(oracle.LOGGER['verbatim_utf8'], advice)
            self.assertIn('@RestControllerAdvice(assignableTypes = F18LegacyBlockedAuthorsController.class)', advice)
            self.assertEqual(sources[oracle.BOUNDARY], (prefix / 'PrivateNoteHttpBoundary.java').read_bytes())
            self.assertEqual(12, evidence.read_text().count('  selected-method '))
            for source in oracle.SHARED:
                relative = source.split('/src/main/java/', 1)[1]
                self.assertFalse((output / relative).exists())
            self.assertIn('source_sha=' + oracle.SOURCE, evidence.read_text()); self.assertIn('wiring:', evidence.read_text())

    def test_given_sparse_checkout_without_legacy_dirs__when_reading_inputs__then_load_only_pinned_objects_and_reject_tamper(self):
        marker = f18.ROOT.parent / '.git'
        git_dir = Path(marker.read_text().removeprefix('gitdir:').strip()) if marker.is_file() else marker
        if not git_dir.is_absolute(): git_dir = (marker.parent / git_dir).resolve()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / '.git').write_text('gitdir: ' + str(git_dir.resolve()) + '\n'); (root / 'backend-app').mkdir()
            for source in oracle.EXPECTED: self.assertTrue(pinned_source(source, root))
            self.assertFalse((root / 'user-service').exists()); self.assertFalse((root / 'community-service').exists())
            with patch.object(subprocess, 'check_output', return_value=b'changed'):
                with self.assertRaisesRegex(AssertionError, 'checksum mismatch'): pinned_source(next(iter(oracle.EXPECTED)), root)


if __name__ == '__main__':
    print('F18 guards: mocked PostgreSQL/JAR/JVM/sockets; fixed inputs use read-only git show, no Git mutations.', flush=True)
    with redirect_stdout(io.StringIO()): unittest.main()
