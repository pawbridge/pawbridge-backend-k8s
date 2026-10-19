"""No-socket F15 owner, V9, ACL, oracle, XML and packaged cleanup regressions."""
from contextlib import ExitStack, contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import f15_ci as f15
import prepare_f15_legacy_oracle as oracle
from test_f14_ci_guards import HOSTED as F14_HOSTED

HOSTED = dict(F14_HOSTED, F15_CI_OWNER='123-1')


def oracle_paths(directory):
    root = Path(directory).resolve()
    return (root, root / 'backend-app/boot/build/generated/sources/f15LegacyOracle/java',
            root / 'backend-app/build/ci/f15-oracle-sha256.txt')


@contextmanager
def packaged_harness(directory, failure=None, mutation=False):
    root = Path(directory)
    jar = root / 'boot/build/libs/pawbridge-backend-app.jar'; jar.parent.mkdir(parents=True); jar.write_bytes(b'synthetic jar')
    evidence = root / 'evidence'
    env = dict(HOSTED, F2_TEST_DB_PASSWORD='private-reader', F3_TEST_WRITER_PASSWORD='private-writer',
               F3_TEST_JWT_SECRET='private-jwt')
    video = {key: None for key in f15.FIELDS}; video.update(id=f15.IDS[0], videoId='F15Video001')
    rows, requests = [video], []
    app, stub = object(), object()
    def expect(base, path, status, **kwargs):
        if path == '/__f3__/state': return {'counts': {}, 'requests': []}
        requests.append((status, kwargs.get('headers', {})))
        if failure == len(requests): raise AssertionError('synthetic request failure')
        return {'code': status, 'message': f15.SUCCESS if status == 200 else f15.UNAVAILABLE,
                'data': None if status != 200 else [] if len(requests) == 5 else rows}
    with ExitStack() as stack:
        for item in (patch.dict(os.environ, env, clear=True), patch.object(f15, 'ROOT', root),
                     patch.object(f15, 'EVIDENCE', evidence), patch.object(f15, 'require_fixture', return_value='123-1'),
                     patch.object(f15, 'prepare_business_rows', return_value=rows), patch.object(f15, 'remove_visible_rows'),
                     patch.object(f15, 'database_snapshot', side_effect=([{'state': 1}, {'state': 2}] if mutation else None),
                                  return_value={'state': 1}),
                     patch.object(f15.f3, 'expect', side_effect=expect),
                     patch.object(f15.f4, 'fresh_log', return_value='Shutdown completed. Shutdown completed. Graceful shutdown complete'),
                     patch.object(f15.f14, 'load_tokens', side_effect=AssertionError('Public F15 must not load tokens'))):
            stack.enter_context(item)
        sql = stack.enter_context(patch.object(f15.f3, 'sql', return_value='0'))
        start = stack.enter_context(patch.object(f15.f4, 'start_ready_children', return_value=(app, stub, 'http://app', 'http://stub', 1, 0)))
        stop = stack.enter_context(patch.object(f15.f3, 'stop_owned_child', return_value=0))
        cleanup = stack.enter_context(patch.object(f15, 'cleanup_business_rows'))
        yield evidence, requests, sql, start, stop, cleanup


class F15GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_actions__then_refuse_before_resources(self):
        for action in (f15.prepare, f15.require_fixture, f15.prepare_business_rows, f15.remove_visible_rows,
                       f15.cleanup_business_rows, f15.smoke, f15.verify_results, f15.verified_ddl):
            with self.subTest(action=action.__name__), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f15.f3, 'sql') as sql, patch.object(f15.f2, 'command') as command, \
                    patch.object(f15.subprocess, 'check_output') as git, patch.object(f15.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'): action()
                sql.assert_not_called(); command.assert_not_called(); git.assert_not_called(); start.assert_not_called()

    def test_given_wrong_target_or_reader__when_preparing__then_no_resource_command(self):
        for changed in ({'F2_TEST_JDBC_URL': 'jdbc:postgresql://production:5432/pawbridge_f2_ci'}, {'F2_TEST_DB_USER': 'postgres'}):
            with self.subTest(changed=changed), patch.dict(os.environ, dict(HOSTED, **changed), clear=True), \
                    patch.object(f15.f2, 'command') as command:
                with self.assertRaises(SystemExit): f15.prepare()
                command.assert_not_called()

    def test_given_invalid_f14_chain__when_preparing__then_refuse_before_export_source_or_write(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f15.f14, 'require_fixture', side_effect=SystemExit('F14 mismatch')), \
                patch.object(f15, 'verified_ddl') as source, patch.object(f15.f3, 'sql') as sql, patch.object(f15.f2, 'export_environment') as export:
            with self.assertRaisesRegex(SystemExit, 'F14 mismatch'): f15.prepare()
            source.assert_not_called(); sql.assert_not_called(); export.assert_not_called()

    def test_given_owned_fixture__when_extending__then_check_source_and_preserve_all_prior_business_rows(self):
        events = []
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f15, 'EVIDENCE', Path(directory)), \
                patch.object(f15.f14, 'require_fixture', side_effect=lambda: events.append('F14') or '123-1'), \
                patch.object(f15, 'verified_ddl', side_effect=lambda: events.append('source') or ('DDL', 'hashes')), \
                patch.object(f15.f2, 'export_environment', side_effect=lambda value: events.append('owner')) as export, \
                patch.object(f15, 'database_snapshot', side_effect=lambda **kwargs: events.append('snapshot') or {'favorites': []}) as snapshot, \
                patch.object(f15.f3, 'sql', side_effect=lambda **kwargs: events.append('DDL')) as sql, \
                patch.object(f15, 'require_fixture', side_effect=lambda: events.append('F15') or '123-1'):
            f15.prepare()
            self.assertEqual(['F14', 'source', 'owner', 'snapshot', 'DDL', 'snapshot', 'F15'], events)
            self.assertEqual([{'include_home': False}] * 2, [call.kwargs for call in snapshot.call_args_list])
            export.assert_called_once_with({'F15_CI_OWNER': '123-1'}); sql.assert_called_once_with(input_text='DDL')
            self.assertEqual('hashes', (Path(directory) / 'f15-ddl-sha256.txt').read_text())

    def test_given_changed_prior_rows__when_extending__then_do_not_report_success(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f15.f14, 'require_fixture', return_value='123-1'), \
                patch.object(f15, 'verified_ddl', return_value=('DDL', 'hashes')), patch.object(f15.f2, 'export_environment'), \
                patch.object(f15, 'database_snapshot', side_effect=[{'favorites': []}, {'favorites': ['changed']}]), \
                patch.object(f15.f3, 'sql'), patch.object(f15, 'require_fixture') as success:
            with self.assertRaisesRegex(SystemExit, 'changed existing business rows'): f15.prepare()
            success.assert_not_called()

    def test_given_actual_pinned_v9__when_verifying__then_full_board_row_videos_and_index_are_verbatim(self):
        with patch.dict(os.environ, HOSTED, clear=True): ddl, evidence = f15.verified_ddl()
        self.assertIn(f15.DDL_HASH + '  ' + f15.DDL_SOURCE, evidence)
        self.assertIn('source_sha=' + f15.SOURCE, evidence)
        for required in ('home_video_board', 'VALUES (1)', 'revision bigint NOT NULL DEFAULT 0',
                         'id uuid PRIMARY KEY', "UNIQUE CHECK (video_id ~ '^[A-Za-z0-9_-]{11}$')",
                         'created_at timestamptz NOT NULL', 'checked_at timestamptz', 'idx_home_video_refresh',
                         'GRANT SELECT(' + ','.join(f15.COLUMNS) + ') ON pawbridge_community.home_videos TO pawbridge_f2_reader'):
            self.assertIn(required, ddl)
        for forbidden in ('pawbridge_f3_writer', 'CREATE ROLE', 'TRUNCATE', 'UPDATE ', 'DELETE FROM', 'DROP ',
                          'CREATE SEQUENCE', 'IDENTITY', 'GRANT SELECT ON', 'GRANT USAGE'):
            self.assertNotIn(forbidden, ddl)

    def test_given_tampered_source_or_removed_initial_row__when_verifying__then_refuse(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f15.subprocess, 'check_output', return_value=b'changed'), \
                patch.object(Path, 'read_text') as read:
            with self.assertRaisesRegex(SystemExit, 'checksum mismatch'): f15.verified_ddl()
            read.assert_not_called()
        raw = (f15.ROOT / 'scripts/f15_fixture.sql').read_text()
        original, repository = f15.subprocess.check_output, f15.ROOT.parent
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True):
            root = Path(directory); (root / 'scripts').mkdir()
            (root / 'scripts/f15_fixture.sql').write_text(raw.replace('VALUES (1)', 'VALUES (2)'))
            with patch.object(f15, 'ROOT', root), patch.object(f15.subprocess, 'check_output', side_effect=lambda args, **kwargs: original(args, cwd=repository)):
                with self.assertRaisesRegex(SystemExit, 'differs from the full pinned V9'): f15.verified_ddl()

    def test_given_foreign_owner__when_checking__then_no_f15_query(self):
        with patch.dict(os.environ, dict(HOSTED, F15_CI_OWNER='foreign'), clear=True), \
                patch.object(f15.f14, 'require_fixture', return_value='123-1'), patch.object(f15.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'owner mismatch'): f15.require_fixture()
            sql.assert_not_called()

    def test_given_bad_marker_shape_acl_constraints_or_board__when_checking__then_select_only_and_refuse(self):
        valid = [f15.MARKER + ':123-1', 't', 't', 't', 't']
        for index in range(len(valid)):
            values = valid.copy(); values[index] = 'foreign' if index == 0 else 'f'
            with self.subTest(index=index), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f15.f14, 'require_fixture', return_value='123-1'), patch.object(f15.f3, 'sql', side_effect=values) as sql:
                with self.assertRaises(SystemExit): f15.require_fixture()
                self.assertTrue(all(call.args[0].startswith('SELECT ') for call in sql.call_args_list))

    def test_given_exact_home_fixture__when_checking__then_reuse_f14_and_enforce_reader_eleven_board_writer_zero(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f15.f14, 'require_fixture', return_value='123-1') as parent, \
                patch.object(f15.f3, 'sql', side_effect=[f15.MARKER + ':123-1', 't', 't', 't', 't']) as sql:
            self.assertEqual('123-1', f15.require_fixture()); parent.assert_called_once_with()
        shape, acl, constraints, board = [call.args[0] for call in sql.call_args_list[1:]]
        for required in ('count(*)=13', "table_name='home_video_board')=2", "is_identity='NO'", 'timestamp with time zone', '2048'):
            self.assertIn(required, shape)
        for required in ("= (table_name='home_videos')", 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER',
                         "pg_get_serial_sequence('pawbridge_community.home_video_board','id') IS NULL",
                         "pg_get_serial_sequence('pawbridge_community.home_videos','id') IS NULL"):
            self.assertIn(required, acl)
        self.assertNotIn('has_sequence_privilege', acl)
        for required in ('convalidated', 'conkey=ARRAY', 'home_videos_video_id_key', 'idx_home_video_refresh',
                         'i.indisvalid', 'i.indisready', 'i.indnatts=1', "'id=1'", "video_id~''^[A-Za-z0-9_-]{11}$''"):
            self.assertIn(required, constraints)
        self.assertIn('count(*)=1 AND bool_and(id=1 AND revision=0)', board)

    def test_given_catalog_failures__when_recording__then_use_f15_stage_and_bounded_masked_primary_only(self):
        for stage in ('marker', 'shape', 'acl', 'constraints', 'board'):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as directory, \
                    patch.object(f15, 'EVIDENCE', Path(directory)), \
                    patch.dict(os.environ, {'F2_TEST_ADMIN_PASSWORD': 'private-password', 'F3_TEST_JWT_SECRET': 'private-jwt'}, clear=True), \
                    patch.object(f15.f3, 'sql', side_effect=subprocess.CalledProcessError(3, ['private-cmd'],
                        output='private-stdout', stderr='ERROR: private-password private-jwt ' + 'x' * 1000 + '\nDETAIL: private-detail\nSELECT private-sql')):
                with self.assertRaisesRegex(SystemExit, 'bounded f15-fixture-catalog-error.txt'): f15.fixture_catalog('SELECT catalog-only', stage)
                text = (Path(directory) / 'f15-fixture-catalog-error.txt').read_text()
                self.assertIn('stage=f15-home-' + stage, text); self.assertIn('returncode=3', text); self.assertIn('[redacted]', text)
                self.assertLessEqual(len(text.split('stderr=')[1].rstrip('\n')), 512)
                for forbidden in ('private-password', 'private-jwt', 'private-cmd', 'private-stdout', 'private-detail', 'private-sql'):
                    self.assertNotIn(forbidden, text)
        with patch.object(f15.f3, 'sql') as sql:
            for query, stage in (('DELETE FROM table', 'acl'), ('SELECT 1', 'foreign')):
                with self.assertRaises(ValueError): f15.fixture_catalog(query, stage)
            sql.assert_not_called()

    def test_given_snapshot_modes__when_reading__then_cover_home_favorites_and_all_previous_business_tables(self):
        with patch.object(f15.f3, 'sql', return_value='{}') as sql:
            f15.database_snapshot(); full = sql.call_args.args[0]
            f15.database_snapshot(include_home=False); before = sql.call_args.args[0]
        for table in ('pawbridge_user.users', 'pawbridge_user.favorites', 'pawbridge_community.posts',
                      'pawbridge_community.comments', 'pawbridge_f4_fixture.comments', 'pawbridge_f4_fixture.private_notes',
                      'pawbridge_community.outbox_events', 'pawbridge_community.post_search_documents'):
            self.assertIn(table, full); self.assertIn(table, before)
        for table in ('home_videos', 'home_video_board'):
            self.assertIn(table, full); self.assertNotIn(table, before)

    def test_given_owned_jar_rows__when_seeding_and_cleaning__then_use_disjoint_uuid_no_overwrite_and_safe_expiry_margins(self):
        with patch.object(f15, 'require_fixture', return_value='123-1'), patch.object(f15.f3, 'sql', side_effect=['0', '', '', '']) as sql:
            expected = f15.prepare_business_rows(); f15.remove_visible_rows(); f15.cleanup_business_rows()
        setup, partial, cleanup = [call.args[0] for call in sql.call_args_list[1:]]
        self.assertIn('BEGIN;', setup); self.assertIn('COMMIT;', setup)
        self.assertNotIn('ON CONFLICT', setup); self.assertNotIn('UPDATE', setup); self.assertNotIn('TRUNCATE', cleanup)
        self.assertNotIn('home_video_board', setup + partial + cleanup)
        for identity in f15.IDS: self.assertIn(identity, setup); self.assertIn(identity, cleanup)
        self.assertTrue(all(value.startswith('f15b0000-') for value in f15.IDS))
        self.assertTrue(all(value not in partial for value in f15.IDS[4:]))
        self.assertEqual(3, len(expected)); self.assertTrue(all(set(row) == f15.FIELDS for row in expected))
        self.assertIsNone(expected[0]['title']); self.assertEqual('', expected[0]['channelTitle'])
        self.assertIsNone(expected[0]['durationSeconds']); self.assertEqual(3000000000, expected[1]['durationSeconds'])
        fresh = f15.datetime.datetime.fromisoformat(expected[0]['checkedAt'].replace('Z', '+00:00'))
        age = f15.datetime.datetime.now(f15.datetime.timezone.utc) - fresh
        self.assertLess(abs(age.total_seconds() - 86400), 2)
        stale = (fresh - f15.datetime.timedelta(days=30)).isoformat().replace('+00:00', 'Z')
        self.assertIn(stale, setup)

    def test_given_unremoved_pg_rows__when_seeding__then_do_not_overwrite_or_delete(self):
        with patch.object(f15, 'require_fixture', return_value='123-1'), patch.object(f15.f3, 'sql', return_value='1') as sql:
            with self.assertRaisesRegex(SystemExit, 'removed its owned video rows'): f15.prepare_business_rows()
            sql.assert_called_once_with('SELECT count(*) FROM pawbridge_community.home_videos')

    def test_given_missing_failed_skipped_or_wrong_xml__when_reporting__then_require_both_exact_timezone_suites(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f15, 'ROOT', Path(directory)):
            paths = []
            valid = '<testsuite name="' + f15.COMPATIBILITY_CLASS + '" tests="1" failures="0" errors="0" skipped="0"><testcase classname="' + f15.COMPATIBILITY_CLASS + '" name="contract"/></testsuite>'
            for task, zone in f15.TASKS:
                path = Path(directory) / 'boot/build/test-results' / task / ('TEST-' + f15.COMPATIBILITY_CLASS + '.xml')
                paths.append(path); path.parent.mkdir(parents=True)
            with self.assertRaises(SystemExit): f15.verify_results()
            paths[0].write_text(valid)
            with self.assertRaises(SystemExit): f15.verify_results()
            for index in range(2):
                for text in ('broken', valid.replace('tests="1"', 'tests="0"'), valid.replace('failures="0"', 'failures="1"'),
                             valid.replace('errors="0"', 'errors="1"'), valid.replace('skipped="0"', 'skipped="1"'),
                             valid.replace('classname="' + f15.COMPATIBILITY_CLASS, 'classname="wrong')):
                    for path in paths: path.write_text(valid)
                    paths[index].write_text(text)
                    with self.assertRaises(SystemExit): f15.verify_results()
            for path in paths: path.write_text(valid)
            self.assertEqual({'UTC': 1, 'Asia/Seoul': 1}, f15.verify_results())

    def test_given_successful_packaged_journey__when_running__then_public_no_tokens_exact_counts_and_two_pool_shutdown(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory) as (evidence, requests, sql, start, stop, cleanup):
            f15.smoke()
            result = json.loads((evidence / 'f15-packaged-result.txt').read_text())
            self.assertEqual([200, 200, 503, 200, 200], [status for status, headers in requests])
            self.assertNotIn('Authorization', requests[0][1]); self.assertIn('definitely-not-a-jwt', requests[1][1]['Authorization'])
            for key, expected in {'status': 'passed', 'home_get_checks': 5, 'nonempty_checks': 3, 'empty_checks': 1,
                                  'unavailable_checks': 1, 'malformed_bearer_checks': 1, 'shutdown_pools': 2,
                                  'remaining_application_connections': 0, 'database_changes': 0, 's3_requests': 0}.items():
                self.assertEqual(expected, result[key])
            self.assertEqual('1', start.call_args.args[1]['F2_POOL_MAX_SIZE']); self.assertEqual(2, stop.call_count)
            cleanup.assert_called_once_with()

    def test_given_permission_request_failure__when_unwinding__then_restore_acl_stop_both_and_cleanup_owned_rows(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, failure=3) as (evidence, requests, sql, start, stop, cleanup):
            with self.assertRaisesRegex(AssertionError, 'synthetic request failure'): f15.smoke()
            statements = [call.args[0] for call in sql.call_args_list]
            self.assertTrue(any(value.startswith('REVOKE SELECT(checked_at)') for value in statements))
            self.assertTrue(any(value.startswith('GRANT SELECT(checked_at)') for value in statements))
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with()
            self.assertEqual('incomplete', json.loads((evidence / 'f15-packaged-result.txt').read_text())['status'])

    def test_given_get_database_mutation__when_comparing_snapshot__then_fail_and_cleanup_without_success(self):
        with tempfile.TemporaryDirectory() as directory, packaged_harness(directory, mutation=True) as (evidence, requests, sql, start, stop, cleanup):
            with self.assertRaisesRegex(AssertionError, 'changed a business row or contacted S3'): f15.smoke()
            self.assertEqual(2, stop.call_count); cleanup.assert_called_once_with()
            self.assertEqual('incomplete', json.loads((evidence / 'f15-packaged-result.txt').read_text())['status'])

    def test_given_workflow__when_extending_f15__then_preserve_prior_stages_two_exact_tasks_and_always_cleanup(self):
        text = (f15.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = text.index('- name: Extend the owned fixture with pinned F15 board and videos and reader-only columns')
        self.assertLess(text.index('python3 scripts/f14_ci.py smoke'), boundary)
        self.assertLess(text.index('python3 scripts/f15_ci.py prepare', boundary), text.index(':boot:homeVideoCompatibilityTest'))
        self.assertLess(text.index(':boot:homeVideoCompatibilitySeoulTest'), text.index('python3 scripts/f15_ci.py results'))
        self.assertLess(text.index('python3 scripts/f15_ci.py results'), text.index('python3 scripts/f15_ci.py smoke'))
        self.assertLess(text.index('python3 scripts/f15_ci.py smoke'), text.index('run: python3 scripts/f3_ci.py cleanup'))
        cleanup = text[text.index('- name: Remove only the owned disposable fixture'):]
        self.assertIn('if: always()', cleanup)
        for required in ('test_f15_ci_guards.py', 'f15-helper-refusal.txt', 'f15-oracle-sha256.txt', 'f15-ddl-sha256.txt', 'f15_fixture', 'f15LegacyOracle'):
            self.assertIn(required, text)
        stages = text[boundary:text.index('- name: Remove only the owned disposable fixture')]
        self.assertNotIn(':boot:compatibilityTest ', stages); self.assertNotIn(':boot:compatibilitySeoulTest ', stages)

    def test_given_wrong_oracle_paths__when_extracting__then_refuse_before_git_or_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory)
            for target, report in ((root / 'foreign', evidence), (output, root / 'foreign.txt')):
                with patch.object(oracle.subprocess, 'check_output') as git, patch.object(Path, 'mkdir') as mkdir, patch.object(Path, 'write_bytes') as write:
                    with self.assertRaisesRegex(SystemExit, 'non-dedicated F15'): oracle.extract(root, target, report)
                    git.assert_not_called(); mkdir.assert_not_called(); write.assert_not_called()

    def test_given_late_oracle_hash_mismatch__when_extracting__then_write_nothing_and_preserve_other_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory)
            sentinel = output / 'existing.java'; sentinel.parent.mkdir(parents=True); sentinel.write_bytes(b'existing')
            expected = {oracle.PREFIX + 'First.java': hashlib.sha256(b'first').hexdigest(), oracle.PREFIX + 'Second.java': hashlib.sha256(b'original').hexdigest()}
            with patch.dict(oracle.EXPECTED, expected, clear=True), patch.object(oracle.subprocess, 'check_output', side_effect=[b'first', b'tampered']):
                with self.assertRaisesRegex(SystemExit, 'checksum mismatch'): oracle.extract(root, output, evidence)
            self.assertFalse((output / 'First.java').exists()); self.assertFalse(evidence.exists())
            self.assertEqual(b'existing', sentinel.read_bytes())

    def test_given_different_later_oracle_output__when_extracting__then_refuse_before_earlier_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory)
            second = output / 'Second.java'; second.parent.mkdir(parents=True); second.write_bytes(b'preserve')
            expected = {oracle.PREFIX + name: hashlib.sha256(b'original').hexdigest() for name in ('First.java', 'Second.java')}
            with patch.dict(oracle.EXPECTED, expected, clear=True), patch.object(oracle.subprocess, 'check_output', return_value=b'original'):
                with self.assertRaisesRegex(SystemExit, 'overwrite differing F15'): oracle.extract(root, output, evidence)
            self.assertFalse((output / 'First.java').exists()); self.assertEqual(b'preserve', second.read_bytes()); self.assertFalse(evidence.exists())

    def test_given_actual_five_oracle_sources__when_extracting__then_preserve_verbatim_bytes_and_evidence(self):
        original = subprocess.check_output
        with tempfile.TemporaryDirectory() as directory:
            root, output, evidence = oracle_paths(directory)
            with patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: original(args, cwd=f15.ROOT.parent)):
                oracle.extract(root, output, evidence)
            self.assertEqual(5, len(oracle.EXPECTED)); self.assertIn('source_sha=' + f15.SOURCE, evidence.read_text())
            for source, digest in oracle.EXPECTED.items():
                content = (output / source[len(oracle.PREFIX):]).read_bytes()
                self.assertEqual(digest, hashlib.sha256(content).hexdigest()); self.assertIn(digest + '  ' + source, evidence.read_text())
            service = (output / 'com/pawbridge/communityservice/curation/HomeVideoService.java').read_text()
            self.assertIn('public List<Video> home() { return repository.visible(expiryCutoff()); }', service)
            self.assertIn('clock.instant().minus(Duration.ofDays(29))', service)

    def test_given_f15_test_artifact__when_applying_existing_jar_filter__then_reject_and_keep_validation_exception(self):
        text = (f15.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        pipeline = re.search(r'          if (grep -E .*?); then', text, re.DOTALL).group(1)
        allowed = 'BOOT-INF/lib/hibernate-validator-8.0.3.Final.jar\n'
        for forbidden in ('', 'BOOT-INF/classes/f15_fixture.sql', 'BOOT-INF/classes/f15LegacyOracle/Input.class',
                          'BOOT-INF/classes/com/pawbridge/communityservice/curation/HomeVideoService.class',
                          'test/f15LegacyOracle/hibernate-validator-8.0.3.Final.jar'):
            with self.subTest(forbidden=forbidden), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / 'build/ci/boot-jar-contents.txt'
                target.parent.mkdir(parents=True); target.write_text(allowed + forbidden + '\n')
                result = subprocess.run(['bash', '-o', 'pipefail', '-c', pipeline], cwd=directory,
                                        capture_output=True, text=True, timeout=3)
                self.assertEqual(0 if forbidden else 1, result.returncode, result.stderr)


if __name__ == '__main__': unittest.main()
