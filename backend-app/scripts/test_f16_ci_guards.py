"""F16 CI guards: mocked resources; read-only pinned Git objects supply sparse-checkout inputs."""
from contextlib import ExitStack, contextmanager, redirect_stdout
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import f16_ci as f16
import prepare_f16_legacy_oracle as oracle
from test_f15_ci_guards import HOSTED as F15_HOSTED

HOSTED = dict(F15_HOSTED, F16_CI_OWNER='123-1')
DAY = datetime.date(2026, 10, 19)
COUNTS = {'2026-10-18': 7, '2026-10-19': 9007199254740993, '2026-10-20': 11}


def pinned_source(source, repository=None):
    # Read immutable objects before installing a subprocess mock: legacy paths are not checked out in CI.
    if source == f16.DDL_SOURCE:
        expected = f16.DDL_HASH
    elif source in oracle.EXPECTED:
        expected = oracle.EXPECTED[source]
    elif source in oracle.SHARED:
        expected = oracle.f7.EXPECTED[source]
    else:
        raise ValueError('F16 guard source must be explicitly hash-pinned')
    content = subprocess.check_output(['git', 'show', f16.SOURCE + ':' + source],
                                      cwd=repository if repository is not None else f16.ROOT.parent)
    if hashlib.sha256(content).hexdigest() != expected:
        raise AssertionError('Fixed F16 guard input checksum mismatch: ' + source)
    return content


def oracle_paths(directory):
    root = Path(directory).resolve()
    return (root, root / 'backend-app/boot/build/generated/sources/f16LegacyOracle/java',
            root / 'backend-app/build/ci/f16-oracle-sha256.txt')


@contextmanager
def packaged_harness(directory, failure=None, mutation=False, cache='no-store', cookie=False):
    root = Path(directory); evidence = root / 'evidence'
    jar = root / 'boot/build/libs/pawbridge-backend-app.jar'; jar.parent.mkdir(parents=True); jar.write_bytes(b'synthetic')
    env = dict(HOSTED, F2_TEST_DB_PASSWORD='private-reader', F3_TEST_WRITER_PASSWORD='private-writer', F3_TEST_JWT_SECRET='private-jwt')
    requests = []
    def request(base, path, **kwargs):
        requests.append(kwargs.get('headers', {})); index = len(requests)
        if failure == index: raise AssertionError('synthetic request failure')
        if index == 6: return 404, b'not a legacy JSON envelope', {}
        status = 503 if index == 3 else 200
        data = None if status == 503 else {'date': DAY.isoformat(), 'count': 0 if index == 5 else COUNTS[DAY.isoformat()]}
        body = json.dumps({'code': status, 'message': f16.SUCCESS if status == 200 else f16.UNAVAILABLE, 'data': data}).encode()
        headers = {'Cache-Control': cache}
        if cookie: headers['Set-Cookie'] = 'forbidden'
        return status, body, headers
    with ExitStack() as stack:
        for item in (patch.dict(os.environ, env, clear=True), patch.object(f16, 'ROOT', root), patch.object(f16, 'EVIDENCE', evidence),
                     patch.object(f16, 'require_fixture', return_value='123-1'), patch.object(f16, 'seoul_day', return_value=DAY),
                     patch.object(f16, 'prepare_business_rows', return_value=COUNTS.copy()),
                     patch.object(f16, 'database_snapshot', side_effect=lambda: {'state': 'changed' if mutation and requests else 'same'}),
                     patch.object(f16.f3, 'request', side_effect=request),
                     patch.object(f16.f3, 'expect', return_value={'counts': {'PUT': 0, 'DELETE': 0}, 'objects': [], 'requests': []}),
                     patch.object(f16.f4, 'fresh_log', return_value='Shutdown completed. Shutdown completed. Graceful shutdown complete'),
                     patch.object(f16.f15.f14, 'load_tokens', side_effect=AssertionError('Public F16 must not load JWTs'))):
            stack.enter_context(item)
        sql = stack.enter_context(patch.object(f16.f3, 'sql', return_value='0'))
        start = stack.enter_context(patch.object(f16.f4, 'start_ready_children', side_effect=lambda *args: (object(), object(), 'http://app', 'http://stub', 1, 0)))
        stop = stack.enter_context(patch.object(f16.f3, 'stop_owned_child', return_value=0))
        cleanup = stack.enter_context(patch.object(f16, 'cleanup_business_rows'))
        yield evidence, requests, sql, start, stop, cleanup


class F16GuardTest(unittest.TestCase):
    def test_given_minimal_checkout_without_legacy_dirs__when_loading_inputs__then_verify_pinned_git_objects(self):
        repository = f16.ROOT.parent
        marker = repository / '.git'
        if marker.is_file():
            git_dir = Path(marker.read_text().removeprefix('gitdir:').strip())
            if not git_dir.is_absolute(): git_dir = (repository / git_dir).resolve()
        else:
            git_dir = marker.resolve()
        # A temporary pointer reads the same object store; it never registers/resets a worktree or changes Git state.
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            (checkout / '.git').write_text('gitdir: ' + str(git_dir) + '\n')
            (checkout / 'backend-app').mkdir(); (checkout / '.github/workflows').mkdir(parents=True)
            self.assertFalse((checkout / 'community-service').exists())
            self.assertFalse((checkout / 'user-service').exists())
            for source in (f16.DDL_SOURCE, *oracle.SHARED, *oracle.EXPECTED):
                self.assertTrue(pinned_source(source, repository=checkout))
            self.assertFalse((checkout / 'community-service').exists())
        with patch.object(subprocess, 'check_output', return_value=b'tampered'):
            with self.assertRaisesRegex(AssertionError, 'input checksum mismatch'): pinned_source(f16.DDL_SOURCE)

    def test_given_non_hosted_invocation__when_calling_actions__then_refuse_before_resources(self):
        for action in (f16.prepare, f16.require_fixture, f16.prepare_business_rows, lambda: f16.cleanup_business_rows({}),
                       f16.smoke, f16.verify_results, f16.verified_ddl):
            with self.subTest(action=action.__name__), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f16.f3, 'sql') as sql, patch.object(f16.f2, 'command') as command, \
                    patch.object(f16.subprocess, 'check_output') as git, patch.object(f16.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'): action()
                sql.assert_not_called(); command.assert_not_called(); git.assert_not_called(); start.assert_not_called()

    def test_given_wrong_target_or_reader__when_preparing__then_no_resource_command(self):
        for changed in ({'F2_TEST_JDBC_URL': 'jdbc:postgresql://production:5432/pawbridge_f2_ci'}, {'F2_TEST_DB_USER': 'postgres'}):
            with patch.dict(os.environ, dict(HOSTED, **changed), clear=True), patch.object(f16.f2, 'command') as command:
                with self.assertRaises(SystemExit): f16.prepare()
                command.assert_not_called()

    def test_given_invalid_f15_chain__when_preparing__then_no_source_export_or_write(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f16.f15, 'require_fixture', side_effect=SystemExit('F15 mismatch')), \
                patch.object(f16, 'verified_ddl') as source, patch.object(f16.f3, 'sql') as sql, patch.object(f16.f2, 'export_environment') as export:
            with self.assertRaisesRegex(SystemExit, 'F15 mismatch'): f16.prepare()
            source.assert_not_called(); sql.assert_not_called(); export.assert_not_called()

    def test_given_owned_fixture__when_extending__then_preserve_prior_rows_before_success(self):
        events = []
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f16, 'EVIDENCE', Path(directory)), \
                patch.object(f16.f15, 'require_fixture', side_effect=lambda: events.append('F15') or '123-1'), \
                patch.object(f16, 'verified_ddl', side_effect=lambda: events.append('source') or ('DDL', 'hashes')), \
                patch.object(f16.f2, 'export_environment', side_effect=lambda value: events.append('owner')) as export, \
                patch.object(f16.f15, 'database_snapshot', side_effect=lambda: events.append('snapshot') or {'favorites': [], 'videos': [], 'board': [0]}), \
                patch.object(f16.f3, 'sql', side_effect=lambda **kwargs: events.append('DDL')) as sql, \
                patch.object(f16, 'require_fixture', side_effect=lambda: events.append('F16') or '123-1'):
            f16.prepare(); self.assertEqual(['F15', 'source', 'owner', 'snapshot', 'DDL', 'snapshot', 'F16'], events)
            export.assert_called_once_with({'F16_CI_OWNER': '123-1'}); sql.assert_called_once_with(input_text='DDL')
            self.assertEqual('hashes', (Path(directory) / 'f16-ddl-sha256.txt').read_text())

    def test_given_prior_row_mutation__when_extending__then_do_not_report_success(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f16.f15, 'require_fixture', return_value='123-1'), \
                patch.object(f16, 'verified_ddl', return_value=('DDL', 'hashes')), patch.object(f16.f2, 'export_environment'), \
                patch.object(f16.f15, 'database_snapshot', side_effect=[{'board': [0]}, {'board': [1]}]), \
                patch.object(f16.f3, 'sql'), patch.object(f16, 'require_fixture') as success:
            with self.assertRaisesRegex(SystemExit, 'changed existing business rows'): f16.prepare()
            success.assert_not_called()

    def test_given_pinned_v11_bytes__when_verifying__then_only_original_count_create_and_two_reader_columns(self):
        raw = pinned_source(f16.DDL_SOURCE)
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f16.subprocess, 'check_output', return_value=raw) as git:
            ddl, evidence = f16.verified_ddl()
        git.assert_called_once_with(['git', 'show', f16.SOURCE + ':' + f16.DDL_SOURCE], cwd=f16.ROOT.parent)
        self.assertIn(f16.DDL_HASH + '  ' + f16.DDL_SOURCE, evidence)
        for required in ('visit_date date PRIMARY KEY', 'unique_visitors bigint NOT NULL CHECK (unique_visitors >= 0)',
                         'GRANT SELECT(visit_date,unique_visitors) ON pawbridge_community.daily_visit_count TO pawbridge_f2_reader'):
            self.assertIn(required, ddl)
        for forbidden in ('daily_visitor', 'visit_collection', 'pawbridge_f3_writer', 'CREATE ROLE', 'TRUNCATE', 'UPDATE ',
                          'DELETE FROM', 'DROP ', 'SEQUENCE', 'IDENTITY', 'GRANT SELECT ON', 'GRANT USAGE'):
            self.assertNotIn(forbidden, ddl)

    def test_given_tampered_source_or_count_check__when_verifying__then_refuse_before_mutation(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f16.subprocess, 'check_output', return_value=b'changed'), patch.object(Path, 'read_text') as read:
            with self.assertRaisesRegex(SystemExit, 'checksum mismatch'): f16.verified_ddl()
            read.assert_not_called()
        raw = pinned_source(f16.DDL_SOURCE)
        ddl = (f16.ROOT / 'scripts/f16_fixture.sql').read_text()
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True):
            root = Path(directory); (root / 'scripts').mkdir(); (root / 'scripts/f16_fixture.sql').write_text(ddl.replace('>= 0', '> 0'))
            with patch.object(f16, 'ROOT', root), patch.object(f16.subprocess, 'check_output', return_value=raw):
                with self.assertRaisesRegex(SystemExit, 'pinned V11 count-table fragment'): f16.verified_ddl()

    def test_given_foreign_owner_or_bad_catalog_result__when_checking__then_read_only_refusal(self):
        with patch.dict(os.environ, dict(HOSTED, F16_CI_OWNER='foreign'), clear=True), patch.object(f16.f15, 'require_fixture', return_value='123-1'), patch.object(f16.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'owner mismatch'): f16.require_fixture()
            sql.assert_not_called()
        valid = [f16.MARKER + ':123-1', 't', 't', 't']
        for index in range(len(valid)):
            values = valid.copy(); values[index] = 'foreign' if index == 0 else 'f'
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f16.f15, 'require_fixture', return_value='123-1'), patch.object(f16.f3, 'sql', side_effect=values) as sql:
                with self.assertRaises(SystemExit): f16.require_fixture()
                self.assertTrue(all(call.args[0].startswith('SELECT ') for call in sql.call_args_list))

    def test_given_exact_fixture__when_checking__then_date_pk_nonnegative_check_and_reader_two_writer_zero(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f16.f15, 'require_fixture', return_value='123-1') as parent, \
                patch.object(f16.f3, 'sql', side_effect=[f16.MARKER + ':123-1', 't', 't', 't']) as sql:
            self.assertEqual('123-1', f16.require_fixture()); parent.assert_called_once_with()
        shape, acl, constraints = [call.args[0] for call in sql.call_args_list[1:]]
        for required in ('count(*)=2', "is_identity='NO'", "is_generated='NEVER'", 'column_default IS NULL', "('visit_date','date','NO')", "('unique_visitors','bigint','NO')"):
            self.assertIn(required, shape)
        for required in ('has_column_privilege', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER', "pg_get_serial_sequence('pawbridge_community.daily_visit_count','visit_date') IS NULL"):
            self.assertIn(required, acl)
        self.assertNotIn('has_sequence_privilege', acl)
        for required in ('count(*)=2', 'convalidated', "contype='p'", "contype='c'", 'conkey=ARRAY', "'unique_visitors>=0'"):
            self.assertIn(required, constraints)

    def test_given_catalog_failure__when_recording__then_f16_tag_bounded_masked_primary_only(self):
        for stage in ('marker', 'shape', 'acl', 'constraints'):
            with tempfile.TemporaryDirectory() as directory, patch.object(f16, 'EVIDENCE', Path(directory)), \
                    patch.dict(os.environ, {'F2_TEST_ADMIN_PASSWORD': 'private-password', 'F3_TEST_JWT_SECRET': 'private-jwt'}, clear=True), \
                    patch.object(f16.f3, 'sql', side_effect=subprocess.CalledProcessError(3, ['private-cmd'], output='private-stdout',
                        stderr='ERROR: private-password private-jwt ' + 'x' * 1000 + '\nDETAIL: private-detail\nSELECT private-sql')):
                with self.assertRaisesRegex(SystemExit, 'bounded f16-fixture-catalog-error.txt'): f16.fixture_catalog('SELECT catalog-only', stage)
                text = (Path(directory) / 'f16-fixture-catalog-error.txt').read_text()
                self.assertIn('stage=f16-visit-' + stage, text); self.assertIn('[redacted]', text)
                self.assertLessEqual(len(text.split('stderr=')[1].rstrip('\n')), 512)
                for value in ('private-password', 'private-jwt', 'private-cmd', 'private-stdout', 'private-detail', 'private-sql'): self.assertNotIn(value, text)
        with patch.object(f16.f3, 'sql') as sql:
            with self.assertRaises(ValueError): f16.fixture_catalog('DELETE FROM table', 'acl')
            sql.assert_not_called()

    def test_given_snapshot__when_reading__then_extend_full_f15_snapshot_with_daily_totals(self):
        prior = {'users': [], 'favorites': [], 'videos': [], 'board': [{'id': 1, 'revision': 0}]}
        with patch.object(f16.f15, 'database_snapshot', return_value=prior) as previous, patch.object(f16.f3, 'sql', return_value='[]') as sql:
            result = f16.database_snapshot(); previous.assert_called_once_with()
        self.assertEqual([], result['daily_visit_count']); self.assertIn('board', result); self.assertIn('favorites', result)
        self.assertIn('json_agg(v ORDER BY visit_date)', sql.call_args.args[0])

    def test_given_owned_neighbor_days__when_seeding_and_cleaning__then_preserve_long_and_use_exact_inserted_keys(self):
        with patch.object(f16, 'require_fixture', return_value='123-1'), patch.object(f16, 'seoul_day', return_value=DAY), \
                patch.object(f16.f3, 'sql', side_effect=['0', '', '']) as sql:
            counts = f16.prepare_business_rows(); f16.cleanup_business_rows(counts)
        self.assertEqual(COUNTS, counts)
        setup, cleanup = sql.call_args_list[1].args[0], sql.call_args_list[2].args[0]
        self.assertIn('BEGIN;', setup); self.assertIn('COMMIT;', setup); self.assertNotIn('ON CONFLICT', setup)
        self.assertIn('9007199254740993', setup); self.assertIn('WHERE (visit_date,unique_visitors) IN', cleanup)
        for day in counts: self.assertIn(day, setup); self.assertIn(day, cleanup)
        self.assertNotIn('TRUNCATE', cleanup); self.assertNotIn('users', setup + cleanup)

    def test_given_unremoved_pg_rows_or_invalid_cleanup_key__when_mutating__then_refuse_before_write(self):
        with patch.object(f16, 'require_fixture', return_value='123-1'), patch.object(f16.f3, 'sql', return_value='1') as sql:
            with self.assertRaisesRegex(SystemExit, 'removed their owned visit rows'): f16.prepare_business_rows()
            sql.assert_called_once_with('SELECT count(*) FROM pawbridge_community.daily_visit_count')
        for counts in ({'2026-10-19': True}, {'2026-10-19': -1}, {'2026-10-19': 9223372036854775808}, {'20261019': 1}, {'bad': 1}):
            with patch.object(f16, 'require_fixture', return_value='123-1'), patch.object(f16.f3, 'sql') as sql:
                with self.assertRaises(ValueError): f16.cleanup_business_rows(counts)
                sql.assert_not_called()

    def test_given_request_crossing_seoul_midnight__when_comparing__then_accept_only_matching_before_or_after_day_count(self):
        tomorrow = DAY + datetime.timedelta(days=1)
        for day in (DAY, tomorrow): f16.require_day_count({'date': day.isoformat(), 'count': COUNTS[day.isoformat()]}, COUNTS, DAY, tomorrow)
        f16.require_day_count({'date': DAY.isoformat(), 'count': 0}, {}, DAY, DAY)
        invalid = [{'date': '2026-10-18', 'count': 7}, {'date': '2026-10-19', 'count': 11},
                   {'date': '20261019', 'count': 9007199254740993}, {'date': '2026-13-19', 'count': 1},
                   {'date': '2026-10-19', 'count': True}, {'date': '2026-10-19', 'count': 9007199254740992.0},
                   {'date': '2026-10-19', 'count': 1, 'extra': 1}]
        for data in invalid:
            with self.assertRaises(AssertionError): f16.require_day_count(data, COUNTS, DAY, tomorrow)
        with self.assertRaises(AssertionError): f16.require_day_count({'date': DAY.isoformat(), 'count': COUNTS[DAY.isoformat()]}, COUNTS, tomorrow, DAY)

    def test_given_report_fault_or_extra_class__when_reporting__then_require_exact_four_xml_with_nonzero_unskipped_cases(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f16, 'ROOT', Path(directory)):
            paths = []
            def valid(name): return '<testsuite name="' + name + '" tests="1" failures="0" errors="0" skipped="0"><testcase classname="' + name + '" name="contract"/></testsuite>'
            for task, names in f16.REPORTS.items():
                for name in names:
                    path = Path(directory) / 'boot/build/test-results' / task / ('TEST-' + name + '.xml')
                    path.parent.mkdir(parents=True, exist_ok=True); paths.append((path, name))
            with self.assertRaises(SystemExit): f16.verify_results()
            for path, name in paths: path.write_text(valid(name))
            for path, name in paths:
                for text in ('broken', valid(name).replace('tests="1"', 'tests="0"'), valid(name).replace('errors="0"', 'errors="1"'),
                             valid(name).replace('failures="0"', 'failures="1"'), valid(name).replace('skipped="0"', 'skipped="1"'),
                             valid(name).replace('classname="' + name, 'classname="wrong'),
                             *(valid(name).replace('/></testsuite>', '><' + tag + '/></testcase></testsuite>')
                               for tag in ('failure', 'error', 'skipped'))):
                    path.write_text(text)
                    with self.assertRaises(SystemExit): f16.verify_results()
                path.write_text(valid(name))
            extra = paths[0][0].parent / 'TEST-extra.xml'; extra.write_text(valid('extra'))
            with self.assertRaisesRegex(SystemExit, 'exact XML class set'): f16.verify_results()
            extra.unlink(); result = f16.verify_results()
            self.assertEqual(4, sum(len(names) for names in result.values()))

    def test_given_mock_jars__when_running_both_phases__then_no_jwt_two_pool_receipt_each_and_honest_disabled_proof(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup):
            f16.smoke(); result = json.loads((evidence / 'f16-packaged-result.txt').read_text())
            self.assertEqual('passed', result['status']); self.assertEqual(2, start.call_count); self.assertEqual(4, stop.call_count)
            self.assertEqual(['true', 'false'], [call.args[1]['DAILY_VISITORS_ENABLED'] for call in start.call_args_list])
            self.assertTrue(all('DAILY_VISITORS_SIGNING_KEY' not in call.args[1] for call in start.call_args_list))
            self.assertEqual(6, len(requests)); self.assertNotIn('Authorization', requests[0]); self.assertIn('definitely-not-a-jwt', requests[1]['Authorization'])
            enabled, disabled = result['enabled'], result['disabled']
            for key, value in {'visit_get_checks': 5, 'positive_checks': 3, 'zero_checks': 1, 'unavailable_checks': 1, 'malformed_bearer_checks': 1}.items(): self.assertEqual(value, enabled[key])
            self.assertEqual(1, disabled['visit_get_checks']); self.assertEqual(1, disabled['disabled_404_checks'])
            for phase in (enabled, disabled): self.assertEqual(2, phase['shutdown_pools']); self.assertEqual(0, phase['remaining_application_connections'])
            self.assertEqual(f16.DISABLED_EVIDENCE, result['disabled_evidence']); self.assertNotIn('sql_attempts', disabled)
            self.assertEqual(2, cleanup.call_count)

    def test_given_request_failure_during_acl_denial__when_unwinding__then_restore_acl_and_cleanup_incomplete_receipt(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, failure=3) as (evidence, requests, sql, start, stop, cleanup):
            with self.assertRaisesRegex(AssertionError, 'synthetic request failure'): f16.smoke()
            statements = [call.args[0] for call in sql.call_args_list]
            self.assertTrue(any(value.startswith('REVOKE SELECT(unique_visitors)') for value in statements))
            self.assertTrue(any(value.startswith('GRANT SELECT(unique_visitors)') for value in statements))
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(COUNTS)
            self.assertEqual('incomplete', json.loads((evidence / 'f16-packaged-result.txt').read_text())['status'])

    def test_given_missing_no_store_cookie_or_db_mutation__when_observing_get__then_fail_and_stop_owned_children(self):
        for changes in ({'cache': 'public'}, {'cookie': True}, {'mutation': True}):
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as directory, packaged_harness(directory, **changes) as (evidence, requests, sql, start, stop, cleanup):
                with self.assertRaises(AssertionError): f16.smoke()
                self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with(COUNTS)
                self.assertEqual('incomplete', json.loads((evidence / 'f16-packaged-result.txt').read_text())['status'])

    def test_given_workflow__when_extending_f16__then_preserve_f15_order_three_tasks_and_always_cleanup(self):
        text = (f16.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = text.index('- name: Extend the owned fixture with only pinned F16 daily totals and reader columns')
        self.assertLess(text.index('python3 scripts/f15_ci.py smoke'), boundary)
        self.assertLess(text.index('python3 scripts/f16_ci.py prepare', boundary), text.index(':boot:visitTodayCompatibilityTest'))
        self.assertLess(text.index(':boot:visitTodayLegacyMvcTest'), text.index('python3 scripts/f16_ci.py results'))
        self.assertLess(text.index('python3 scripts/f16_ci.py results'), text.index('python3 scripts/f16_ci.py smoke'))
        cleanup = text.index('- name: Remove only the owned disposable fixture')
        self.assertLess(text.index('python3 scripts/f16_ci.py smoke'), cleanup); self.assertIn('if: always()', text[cleanup:])
        for value in ('f16-helper-refusal.txt', 'f16-oracle-sha256.txt', 'f16-ddl-sha256.txt', 'test_f16_ci_guards.py', 'f16_fixture', 'f16LegacyOracle'): self.assertIn(value, text)
        self.assertNotIn(':boot:compatibilityTest ', text[boundary:cleanup]); self.assertNotIn('F3_TEST_TOKENS_PATH', text[boundary:cleanup])

    def test_given_wrong_oracle_paths__when_extracting__then_refuse_before_git_or_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory)
            for target, report in ((root / 'foreign', evidence), (output, root / 'foreign.txt')):
                with patch.object(oracle.subprocess, 'check_output') as git, patch.object(Path, 'mkdir') as mkdir, patch.object(Path, 'write_bytes') as write:
                    with self.assertRaisesRegex(SystemExit, 'non-dedicated F16'): oracle.extract(root, target, report)
                    git.assert_not_called(); mkdir.assert_not_called(); write.assert_not_called()

    def test_given_shared_f7_source_or_generated_tamper__when_extracting__then_write_nothing(self):
        source = oracle.SHARED[0]
        original = pinned_source(source)
        for source_bytes, generated_bytes in ((b'changed', original), (original, b'changed')):
            with tempfile.TemporaryDirectory() as directory:
                root, output, evidence = oracle_paths(directory)
                shared = root / 'backend-app/boot/build/generated/sources/f7LegacyOracle/java' / source[len(oracle.PREFIX):]
                shared.parent.mkdir(parents=True); shared.write_bytes(generated_bytes)
                with patch.object(oracle, 'SHARED', (source,)), patch.object(oracle.subprocess, 'check_output', return_value=source_bytes), patch.object(Path, 'write_bytes') as write:
                    with self.assertRaisesRegex(SystemExit, 'Shared F7 input differs'): oracle.extract(root, output, evidence)
                    write.assert_not_called(); self.assertFalse(evidence.exists())

    def test_given_late_new_hash_or_existing_output_mismatch__when_extracting__then_preserve_all_existing_outputs(self):
        first, second = oracle.PREFIX + 'First.java', oracle.PREFIX + 'Second.java'
        expected = {first: hashlib.sha256(b'original').hexdigest(), second: hashlib.sha256(b'original').hexdigest()}
        for tampered in (True, False):
            with tempfile.TemporaryDirectory() as directory:
                root, output, evidence = oracle_paths(directory)
                target = output / 'Second.java'; target.parent.mkdir(parents=True); target.write_bytes(b'preserve')
                with patch.object(oracle, 'SHARED', ()), patch.dict(oracle.EXPECTED, expected, clear=True), \
                        patch.object(oracle.subprocess, 'check_output', side_effect=[b'original', b'changed' if tampered else b'original']):
                    with self.assertRaises(SystemExit): oracle.extract(root, output, evidence)
                self.assertFalse((output / 'First.java').exists()); self.assertEqual(b'preserve', target.read_bytes()); self.assertFalse(evidence.exists())

    def test_given_nine_original_bytes_and_shared_f7__when_extracting__then_write_verbatim_no_duplicate_shared_fqn(self):
        sources = {source: pinned_source(source) for source in (*oracle.SHARED, *oracle.EXPECTED)}
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory)
            for source in oracle.SHARED:
                target = root / 'backend-app/boot/build/generated/sources/f7LegacyOracle/java' / source[len(oracle.PREFIX):]
                target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(sources[source])
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: sources[args[-1].split(':', 1)[1]]) as git:
                oracle.extract(root, output, evidence)
            self.assertEqual(9, len(oracle.EXPECTED)); self.assertIn('source_sha=' + f16.SOURCE, evidence.read_text())
            self.assertTrue(all(call.args[0][2].startswith(f16.SOURCE + ':') for call in git.call_args_list))
            for source, digest in oracle.EXPECTED.items():
                self.assertEqual(sources[source], (output / source[len(oracle.PREFIX):]).read_bytes()); self.assertEqual(digest, hashlib.sha256(sources[source]).hexdigest())
            for source in oracle.SHARED: self.assertFalse((output / source[len(oracle.PREFIX):]).exists())
            self.assertNotIn('VisitConfiguration.java', '\n'.join(oracle.EXPECTED))

    def test_given_f16_test_artifacts__when_applying_jar_rules__then_reject_without_widening_validator_exception(self):
        text = (f16.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        denied = next(line for line in text.splitlines() if "if grep -E '" in line).split("if grep -E '", 1)[1].split("' build/ci/", 1)[0]
        allowed = next(line for line in text.splitlines() if "| grep -Ev '" in line).split("| grep -Ev '", 1)[1].split("'; then", 1)[0]
        for path in ('BOOT-INF/classes/f16_fixture.sql', 'BOOT-INF/classes/f16LegacyOracle/Input.class',
                     'BOOT-INF/classes/com/pawbridge/communityservice/visits/VisitService.class', 'test/f16LegacyOracle/hibernate-validator-8.0.3.Final.jar'):
            self.assertIsNotNone(re.search(denied, path)); self.assertIsNone(re.search(allowed, path))
        self.assertIsNotNone(re.search(allowed, 'BOOT-INF/lib/hibernate-validator-8.0.3.Final.jar'))


class VisitCollectionGuardTest(unittest.TestCase):
    """New artifact/mode/late-DDL controls only; no database, Git process, JVM or network."""

    def test_given_wrong_collection_destination_or_source_hash__when_verifying__then_no_evidence_write(self):
        for wrong in ('path','hash'):
            with self.subTest(wrong=wrong), tempfile.TemporaryDirectory() as directory:
                root=Path(directory).resolve();output=root/'backend-app/boot/build/generated/sources/f16LegacyOracle/java'
                target=root/'backend-app/build/ci/f16-collection-oracle-sha256.txt'
                with patch.object(oracle.subprocess,'check_output',return_value=b'changed source') as git,patch.object(Path,'write_text') as write:
                    with self.assertRaises(SystemExit):oracle.extract_collection(root,output if wrong=='hash' else root/'other',target)
                    write.assert_not_called()
                    if wrong=='path':git.assert_not_called()

    def test_given_a_conflicting_collection_receipt_after_controlled_inputs__when_verifying__then_preserve_old_get_and_other_files(self):
        # Controlled input hashes prove conflict-before-write, not the original visit SQL/business contract.
        raw={oracle.COLLECTION_DDL:b'ddl'}
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();output=root/'backend-app/boot/build/generated/sources/f16LegacyOracle/java';output.mkdir(parents=True)
            old=output/'Keep.java';old.write_bytes(b'keep');target=root/'backend-app/build/ci/f16-collection-oracle-sha256.txt';target.parent.mkdir(parents=True)
            base=target.with_name('f16-oracle-sha256.txt');base.write_text(oracle.collection_base_receipt());before=base.read_bytes();target.write_text('conflict')
            with patch.object(oracle,'COLLECTION_EXPECTED',{p:hashlib.sha256(b).hexdigest() for p,b in raw.items()}),patch.object(oracle,'COLLECTION_SHARED',{}), \
                    patch.object(oracle.subprocess,'check_output',return_value=b'ddl'),patch.object(oracle,'collection_fragments',return_value=('first','second')),patch.object(Path,'write_text') as write:
                with self.assertRaisesRegex(SystemExit,'changed F16 collection receipt'):oracle.extract_collection(root,output,target)
                write.assert_not_called()
            self.assertEqual(before,base.read_bytes());self.assertEqual(b'keep',old.read_bytes());self.assertEqual('conflict',target.read_text())

    def test_given_missing_or_changed_shared_or_receipt__when_preparing_either_mode__then_refuse_before_any_ddl_or_grant(self):
        import f29_ci as late
        for action,parent in ((late.visit_collection_prepare,'member_require_fixture'),(late.admin_visit_collection_prepare,'admin_update_require_fixture')):
            for defect in ('shared','receipt'):
                with self.subTest(action=action.__name__,defect=defect),tempfile.TemporaryDirectory() as directory:
                    root=Path(directory).resolve()/'backend-app';evidence=root/'build/ci';evidence.mkdir(parents=True)
                    shared=root/'shared.java';shared.write_bytes(b'shared')
                    with patch.object(oracle,'COLLECTION_EXPECTED',{'source':hashlib.sha256(b'shared').hexdigest()}),patch.object(oracle,'COLLECTION_SHARED',{'source':'backend-app/shared.java'}), \
                            patch.object(late,'ROOT',root),patch.object(late,'EVIDENCE',evidence),patch.object(late.f2,'require_hosted'),patch.object(late,parent,return_value='123-1'),patch.object(late,'owned_sql') as sql:
                        (evidence/'f16-oracle-sha256.txt').write_text(oracle.collection_base_receipt());extra=evidence/'f16-collection-oracle-sha256.txt';extra.write_text(oracle.collection_receipt())
                        if defect=='shared':shared.write_bytes(b'changed')
                        else:extra.write_text(extra.read_text()+'unexpected\n')
                        with self.assertRaisesRegex(SystemExit,'artifacts or separate source receipt'):action()
                        sql.assert_not_called()
                    self.assertFalse((evidence/late.VISIT_COLLECTION_RECEIPT).exists())

    def test_given_either_exact_parent__when_extending__then_create_only_two_original_tables_and_record_the_fixed_mode_without_rows(self):
        import f29_ci as late
        for mode,action,parent,strict,snapshot in (
                ('member-cleanup',late.visit_collection_prepare,'member_require_fixture','visit_collection_require_fixture','visit_collection_database_snapshot'),
                ('admin-user-update',late.admin_visit_collection_prepare,'admin_update_require_fixture','admin_visit_collection_require_fixture','admin_visit_collection_database_snapshot')):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as directory:
                evidence=Path(directory).resolve();before={'users':[{'user_id':42}]};after=dict(before,daily_visitor=[],visit_collection=[])
                with patch.object(late,'EVIDENCE',evidence),patch.object(late.f2,'require_hosted'),patch.object(late,parent,return_value='123-1') as prior, \
                        patch.object(late,'visit_collection_require_oracle_receipt'),patch.object(late.subprocess,'check_output',return_value=b'controlled DDL'), \
                        patch.object(oracle,'collection_fragments',return_value=('CREATE TABLE first;\n','CREATE TABLE second;\n')), \
                        patch.object(late,'owned_sql',return_value='t') as sql,patch.object(late,'member_database_snapshot',return_value=before), \
                        patch.object(late,strict,return_value='123-1'),patch.object(late,snapshot,return_value=after):
                    action();prior.assert_called_once()
                    writes=[c.kwargs['input_text'] for c in sql.call_args_list if 'input_text' in c.kwargs]
                    self.assertEqual(1,len(writes));self.assertEqual(2,writes[0].count('CREATE TABLE '));self.assertEqual(3,writes[0].count('GRANT '))
                    self.assertNotIn('INSERT INTO ',writes[0]);self.assertNotIn('UPDATE pawbridge_user',writes[0]);self.assertNotIn('SEQUENCE',writes[0])
                    self.assertIn('fixture='+mode+' plus visit collection\n',(evidence/late.VISIT_COLLECTION_RECEIPT).read_text())

    def test_given_fixed_modes_and_catalog_failures__when_checking__then_keep_exact_old_or_admin_user_acl_and_reject_every_bad_visit_catalog(self):
        import f29_ci as late
        for action,identity,reads,updates in (
                (late.visit_collection_require_fixture,'visit_collection_require_identity',('user_id','nickname'),('user_id','nickname','updated_at')),
                (late.admin_visit_collection_require_fixture,'admin_visit_collection_require_identity',('user_id','nickname','role','care_reg_no'),('user_id','nickname','updated_at','role','care_reg_no'))):
            with patch.object(late,identity,return_value='123-1'),patch.object(late,'_member_require_fixture_without_user_writer_acl'),patch.object(late,'_member_require_user_writer_acl') as acl,patch.object(late,'_visit_collection_require_tables'):
                self.assertEqual('123-1',action());acl.assert_called_once_with(reads,updates)
        with self.assertRaises(ValueError):late._visit_collection_proof('123-1','inferred-mode')
        self.assertNotEqual(late.visit_collection_fixture_proof('123-1'),late.admin_visit_collection_fixture_proof('123-1'))
        with patch.object(late,'owned_sql',return_value='t') as sql:late._visit_collection_require_tables();self.assertEqual(14,sql.call_count)
        for failed in range(14):
            with self.subTest(failed=failed),patch.object(late,'owned_sql',side_effect=['t']*failed+['f']) as sql:
                with self.assertRaises(SystemExit):late._visit_collection_require_tables()
                self.assertEqual(failed+1,sql.call_count)

    def test_given_preexisting_count_writer_right_or_business_table__when_extending__then_never_commit_or_write_receipt(self):
        import f29_ci as late
        for answers in (['f'],['t','f']):
            with self.subTest(answers=answers),tempfile.TemporaryDirectory() as directory,patch.object(late,'EVIDENCE',Path(directory).resolve()), \
                    patch.object(late,'visit_collection_require_oracle_receipt'),patch.object(late.subprocess,'check_output',return_value=b'controlled DDL'),patch.object(oracle,'collection_fragments',return_value=('one','two')), \
                    patch.object(late,'owned_sql',side_effect=answers) as sql:
                with self.assertRaises(SystemExit):late._visit_collection_extend('123-1',late.visit_collection_fixture_proof('123-1'),lambda:'123-1',lambda:{})
                self.assertFalse(any('input_text' in c.kwargs for c in sql.call_args_list));self.assertFalse((Path(directory)/late.VISIT_COLLECTION_RECEIPT).exists())

    def test_given_twenty_six_snapshot_keys_or_unknown_rows__when_observing__then_keep_all_old_raw_tables_and_reject_missing_or_extra_keys(self):
        import f29_ci as late
        valid={key:[] for key,_,_ in late.SNAPSHOT_TABLES+late.MEMBER_TABLES+late.VISIT_COLLECTION_TABLES};valid['users']=[{'user_id':42}]
        self.assertEqual(26,len(valid));self.assertIn('daily_visitor',valid);self.assertIn('visit_collection',valid)
        with patch.object(late,'owned_sql',return_value=json.dumps(valid)) as sql:self.assertEqual(valid,late._visit_collection_snapshot());self.assertEqual(1,sql.call_count)
        for changed in ({k:v for k,v in valid.items() if k!='users'},dict(valid,unknown=[]),dict(valid,daily_visitor='not rows')):
            with self.subTest(changed_keys=len(changed)),patch.object(late,'owned_sql',return_value=json.dumps(changed)):
                with self.assertRaisesRegex(SystemExit,'twenty-six raw tables'):late._visit_collection_snapshot()


if __name__ == '__main__':
    print('F16 guards: mocked PostgreSQL/JAR/JVM/sockets; fixed inputs use read-only git show, no Git mutations.', flush=True)
    with redirect_stdout(io.StringIO()):
        unittest.main()
