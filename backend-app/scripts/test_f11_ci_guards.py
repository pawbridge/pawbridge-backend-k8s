"""Mock-only F11 staged ACL/target/report/oracle checks; no Docker, DB, JVM or socket."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import f11_ci as f11
import prepare_f11_legacy_oracle as oracle
from test_f10_ci_guards import HOSTED as F10_HOSTED

HOSTED = dict(F10_HOSTED, F11_CI_OWNER='123-1')


class F11GuardTest(unittest.TestCase):
    def test_given_previous_day_utc_storage__when_checking_seoul_get__then_match_next_day_with_microseconds(self):
        f11.require_seoul_get_timestamp('2026-10-20T01:00:00.123456', '2026-10-19T16:00:00.123456')

    def test_given_stale_or_wrong_timestamp__when_checking_committed_seoul_get__then_refuse(self):
        stored_old, stored_new = '2026-10-19T15:59:59.000001', '2026-10-19T16:00:00.123456'
        observed_old, observed_new = '2026-10-20T00:59:59.000001', '2026-10-20T01:00:00.123456'
        for observed, stored in ((observed_old, stored_new), (observed_new, stored_old),
                                 (stored_new, stored_new), ('2026-10-20T01:00:00.123457', stored_new)):
            with self.subTest(observed=observed, stored=stored):
                with self.assertRaisesRegex(AssertionError, 'committed PUT stored timestamp'):
                    f11.require_seoul_get_timestamp(observed, stored)

    def test_given_non_hosted_invocation__when_preparing_seeding_reporting_or_smoking__then_no_resource_call(self):
        for action in (f11.prepare, f11.prepare_business_rows, f11.verify_results, f11.smoke):
            with self.subTest(action=action.__name__), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f11.f3, 'sql') as sql, patch.object(f11.f2, 'command') as command, \
                    patch.object(f11.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'):
                    action()
                sql.assert_not_called()
                command.assert_not_called()
                start.assert_not_called()

    def test_given_missing_run_owner__when_preparing__then_refuse_before_sql_or_docker(self):
        with patch.dict(os.environ, dict(HOSTED, GITHUB_RUN_ID=''), clear=True), \
                patch.object(f11.f3, 'sql') as sql, patch.object(f11.f2, 'command') as command:
            with self.assertRaisesRegex(SystemExit, 'Missing CI owner identity'):
                f11.prepare()
            sql.assert_not_called()
            command.assert_not_called()

    def test_given_wrong_target_or_reader__when_preparing__then_inherit_refusal_before_docker(self):
        for changed, message in [({'F2_TEST_JDBC_URL': 'jdbc:postgresql://127.0.0.1:54321/production'},
                                  'Refusing non-loopback, non-fixture test URL'),
                                 ({'F2_TEST_JDBC_URL': 'jdbc:postgresql://db.example:5432/pawbridge_f2_ci'},
                                  'Refusing non-loopback, non-fixture test URL'),
                                 ({'F2_TEST_DB_USER': 'postgres'}, 'Unexpected fixture reader')]:
            with self.subTest(changed=changed), patch.dict(os.environ, dict(HOSTED, **changed), clear=True), \
                    patch.object(f11.f2, 'command') as command:
                with self.assertRaisesRegex(SystemExit, message):
                    f11.prepare()
                command.assert_not_called()

    def test_given_foreign_container_label__when_preparing__then_inspect_only_without_mutation(self):
        inspection = [{'Config': {'Labels': {f11.f2.OWNER: 'foreign'}}}]
        with patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f11.f2, 'command', return_value=SimpleNamespace(stdout=json.dumps(inspection))) as command, \
                patch.object(f11.f3, 'sql') as mutation:
            with self.assertRaisesRegex(SystemExit, 'Refusing an unowned CI container'):
                f11.prepare()
            self.assertEqual(1, command.call_count)
            self.assertEqual(['docker', 'inspect', HOSTED['F2_CI_CONTAINER']], command.call_args.args[0])
            mutation.assert_not_called()

    def test_given_wrong_admin_or_database_marker__when_preparing__then_no_mutation(self):
        for identity in ('production:postgres:pawbridge-f2-ci-only-v1',
                         'pawbridge_f2_ci:other:pawbridge-f2-ci-only-v1'):
            with self.subTest(identity=identity), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f11.f2, 'sql', return_value=identity) as sql, \
                    patch.object(f11.f2, 'command') as command, patch.object(f11.f3, 'sql') as mutation:
                with self.assertRaisesRegex(SystemExit, 'Fixture identity mismatch'):
                    f11.prepare()
                self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
                command.assert_not_called()
                mutation.assert_not_called()

    def test_given_foreign_token_path__when_preparing__then_ancestor_select_only_and_refusal(self):
        with patch.dict(os.environ, dict(HOSTED, F3_TEST_TOKENS_PATH='/synthetic/foreign.json'), clear=True), \
                patch.object(f11.f2, 'require_fixture'), patch.object(f11.f2, 'command') as command, \
                patch.object(f11.f3, 'sql', return_value=f11.f3.MARKER + ':123-1') as sql:
            with self.assertRaisesRegex(SystemExit, 'F3 token file must belong to this hosted run'):
                f11.prepare()
            self.assertEqual(1, sql.call_count)
            self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
            command.assert_not_called()

    def test_given_mismatched_owned_port__when_preparing__then_no_ddl_or_environment_export(self):
        for ports in (None, [{'HostIp': '127.0.0.1', 'HostPort': '54322'}],
                      [{'HostIp': '0.0.0.0', 'HostPort': '54321'}],
                      [{'HostIp': '127.0.0.1', 'HostPort': '54321'}] * 2):
            with self.subTest(ports=ports), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f11.f8.f7, 'require_fixture', return_value='123-1'), \
                    patch.object(f11.f3, 'sql', return_value='t') as sql, \
                    patch.object(f11.f2, 'inspect_owned', return_value={'NetworkSettings': {'Ports': {'5432/tcp': ports}}}), \
                    patch.object(f11.f2, 'export_environment') as export:
                with self.assertRaisesRegex(SystemExit, 'JDBC target must match the owned fixture container port'):
                    f11.prepare()
                self.assertEqual(1, sql.call_count)
                self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
                export.assert_not_called()

    def test_given_non_original_f10_acl__when_preparing__then_no_f11_mutation_or_export(self):
        with patch.object(f11.f10, 'require_fixture', side_effect=SystemExit('F10 strict ACL mismatch')), \
                patch.object(f11.f3, 'sql') as sql, patch.object(f11.f2, 'export_environment') as export:
            with self.assertRaisesRegex(SystemExit, 'F10 strict ACL mismatch'):
                f11.prepare()
            sql.assert_not_called()
            export.assert_not_called()

    def test_given_owned_original_acl__when_preparing__then_extend_only_one_content_update_column(self):
        events = []
        with patch.object(f11.f10, 'require_fixture', side_effect=lambda: events.append('F10') or '123-1'), \
                patch.object(f11.f2, 'export_environment', side_effect=lambda values: events.append('owner')) as export, \
                patch.object(f11.f3, 'sql', side_effect=lambda **kwargs: events.append('DDL')) as sql, \
                patch.object(f11, 'require_fixture', side_effect=lambda: events.append('F11') or '123-1'):
            f11.prepare()
            self.assertEqual(['F10', 'owner', 'DDL', 'F11'], events)
            export.assert_called_once_with({'F11_CI_OWNER': '123-1'})
            ddl = sql.call_args.kwargs['input_text']
            self.assertIn("VALUES ('pawbridge-f11-ci-only-v1', :'fixture_owner')", ddl)
            self.assertIn('GRANT UPDATE(content) ON pawbridge_community.comments TO pawbridge_f3_writer;', ddl)
            self.assertEqual(1, ddl.count('GRANT '))
            for forbidden in ('CREATE ROLE', 'GRANT INSERT', 'GRANT SELECT', 'GRANT DELETE', 'GRANT ALL', 'GRANT USAGE',
                              'ON pawbridge_user.', 'ON pawbridge_community.posts', 'ALTER TABLE', 'TRUNCATE', 'DROP '):
                self.assertNotIn(forbidden, ddl)

    def test_given_foreign_f9_f10_or_f11_owner__when_checking_extended_fixture__then_refuse_before_mutation(self):
        markers = [f11.f9.MARKER + ':123-1', f11.f10.MARKER + ':123-1', f11.MARKER + ':123-1']
        for stage in (9, 10, 11):
            with self.subTest(stage=stage), \
                    patch.dict(os.environ, dict(HOSTED, **{'F' + str(stage) + '_CI_OWNER': 'foreign'}), clear=True), \
                    patch.object(f11.f8, 'require_fixture', return_value='123-1'), \
                    patch.object(f11.f3, 'sql', side_effect=markers) as sql:
                with self.assertRaisesRegex(SystemExit, 'F' + str(stage) + ' fixture owner mismatch'):
                    f11.require_fixture()
                self.assertTrue(all(call.args[0].startswith('SELECT ') for call in sql.call_args_list))

    def test_given_foreign_stage_marker_or_wrong_acl__when_checking_extended_fixture__then_select_only_and_refuse(self):
        markers = [f11.f9.MARKER + ':123-1', f11.f10.MARKER + ':123-1', f11.MARKER + ':123-1']
        failures = [(markers[:index] + ['foreign'], 'F' + str(index + 9) + ' fixture identity mismatch')
                    for index in range(3)] + [(markers + ['f'], 'exact extended comment ACL')]
        for responses, message in failures:
            with self.subTest(message=message), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f11.f8, 'require_fixture', return_value='123-1'), \
                    patch.object(f11.f3, 'sql', side_effect=responses) as sql:
                with self.assertRaisesRegex(SystemExit, message):
                    f11.require_fixture()
                self.assertTrue(all(call.args[0].startswith('SELECT ') for call in sql.call_args_list))

    def test_given_exact_extended_acl__when_checking_fixture__then_preserve_negative_privileges_without_ancestor_strict_calls(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f11.f8, 'require_fixture', return_value='123-1'), \
                patch.object(f11.f9, 'require_fixture') as f9_strict, \
                patch.object(f11.f10, 'require_fixture') as f10_strict, \
                patch.object(f11.f3, 'sql', side_effect=[f11.f9.MARKER + ':123-1', f11.f10.MARKER + ':123-1',
                                                       f11.MARKER + ':123-1', 't']) as sql:
            self.assertEqual('123-1', f11.require_fixture())
            f9_strict.assert_not_called()
            f10_strict.assert_not_called()
            acl = sql.call_args.args[0]
            for required in ('count(*)=7', "column_name IN ('comment_id','author_id','deleted_at')",
                             "column_name IN ('post_id','author_id','content','created_at','updated_at')",
                             "column_name IN ('content','deleted_at','updated_at')", 'AND convalidated',
                             "'USAGE,SELECT,UPDATE'", "'pawbridge_user.users','INSERT,UPDATE,DELETE'",
                             "'pawbridge_community','CREATE'", "'pawbridge_community.comments','DELETE'"):
                self.assertIn(required, acl)

    def test_given_unowned_fixture__when_seeding__then_no_business_mutation(self):
        with patch.object(f11, 'require_fixture', side_effect=SystemExit('unowned')), patch.object(f11.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'unowned'):
                f11.prepare_business_rows()
            sql.assert_not_called()

    def test_given_readiness_failure__when_smoking__then_no_business_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'boot/build/libs/pawbridge-backend-app.jar'
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b'never executed')
            tokens = root / 'synthetic-tokens.json'
            tokens.write_text('{}')
            values = {'F3_TEST_TOKENS_PATH': str(tokens), 'F2_TEST_JDBC_URL': 'unused',
                      'F2_TEST_DB_USER': 'unused', 'F2_TEST_DB_PASSWORD': 'synthetic',
                      'F3_TEST_WRITER_PASSWORD': 'synthetic', 'F3_TEST_JWT_SECRET': 'synthetic'}
            with patch.dict(os.environ, values, clear=True), patch.object(f11, 'ROOT', root), \
                    patch.object(f11, 'EVIDENCE', root / 'evidence'), \
                    patch.object(f11, 'require_fixture', return_value='123-1'), \
                    patch.object(f11.f4, 'start_ready_children', side_effect=AssertionError('readiness failure')), \
                    patch.object(f11, 'prepare_business_rows') as rows, patch.object(f11.f3, 'sql') as sql:
                with self.assertRaisesRegex(AssertionError, 'readiness failure'):
                    f11.smoke()
                rows.assert_not_called()
                sql.assert_not_called()

    @staticmethod
    def reports(root, *, tests=1, failures=0, errors=0, skipped=0, classname=None, cases=None):
        classname = classname or f11.COMPATIBILITY_CLASS
        for task in ('commentUpdateCompatibilityTest', 'commentUpdateCompatibilitySeoulTest'):
            path = root / 'boot/build/test-results' / task / ('TEST-' + f11.COMPATIBILITY_CLASS + '.xml')
            path.parent.mkdir(parents=True, exist_ok=True)
            suite = ET.Element('testsuite', name=classname, tests=str(tests), failures=str(failures),
                               errors=str(errors), skipped=str(skipped))
            for index in range(tests if cases is None else cases):
                ET.SubElement(suite, 'testcase', name='synthetic-' + str(index), classname=classname)
            ET.ElementTree(suite).write(path)

    def test_given_separate_successful_reports__when_verifying__then_require_nonzero_execution_in_both_task_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.reports(root, tests=2)
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f11, 'ROOT', root):
                self.assertEqual({'UTC': 2, 'Asia/Seoul': 2}, f11.verify_results())

    def test_given_missing_zero_failed_skipped_or_wrong_reports__when_verifying__then_refuse_not_skip(self):
        for settings in (None, {'tests': 0}, {'failures': 1}, {'errors': 1}, {'skipped': 1},
                         {'classname': 'OtherTest'}, {'tests': 2, 'cases': 1}):
            with self.subTest(settings=settings), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                if settings is not None:
                    self.reports(root, **settings)
                with patch.dict(os.environ, HOSTED, clear=True), patch.object(f11, 'ROOT', root):
                    with self.assertRaisesRegex(SystemExit, 'F11 compatibility report'):
                        f11.verify_results()
        for task in ('commentUpdateCompatibilityTest', 'commentUpdateCompatibilitySeoulTest'):
            for failure in ('missing', 'malformed'):
                with self.subTest(task=task, failure=failure), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    self.reports(root)
                    report = root / 'boot/build/test-results' / task / ('TEST-' + f11.COMPATIBILITY_CLASS + '.xml')
                    if failure == 'missing':
                        report.unlink()
                    else:
                        report.write_text('<testsuite')
                    with patch.dict(os.environ, HOSTED, clear=True), patch.object(f11, 'ROOT', root):
                        with self.assertRaisesRegex(SystemExit, 'F11 compatibility report'):
                            f11.verify_results()

    def test_given_workflow_phases__when_declaring_checks__then_finish_original_acl_before_extending_and_use_exact_tasks(self):
        workflow = (f11.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('- name: Extend the owned fixture with exact F11 comment content update permission')
        self.assertLess(workflow.index('python3 scripts/f10_ci.py smoke'), boundary)
        self.assertLess(workflow.index('python3 scripts/f11_ci.py prepare', boundary),
                        workflow.index(':boot:commentUpdateCompatibilityTest'))
        self.assertLess(workflow.index(':boot:commentUpdateCompatibilitySeoulTest'),
                        workflow.index('python3 scripts/f11_ci.py results'))
        self.assertLess(workflow.index('python3 scripts/f11_ci.py results'), workflow.index('python3 scripts/f11_ci.py smoke'))
        after = workflow[boundary:]
        self.assertNotRegex(after, r'python3 scripts/f(?:9|10)_ci\.py (?:prepare|smoke)')
        for required in ('scripts/test_f11_ci_guards.py', 'build/ci/f11-helper-refusal.txt',
                         'f11_fixture', 'f11LegacyOracle', 'build/ci/f11-oracle-sha256.txt'):
            self.assertIn(required, workflow)

    def test_given_declared_test_tasks__when_selecting_stages__then_isolate_f11_from_both_ancestor_matrices(self):
        build = (f11.ROOT / 'boot/build.gradle').read_text()
        original = build.split("['compatibilityTest':", 1)[1].split("['commentDeleteCompatibilityTest':", 1)[0]
        delete = build.split("['commentDeleteCompatibilityTest':", 1)[1].split("['commentUpdateCompatibilityTest':", 1)[0]
        update = build.split("['commentUpdateCompatibilityTest':", 1)[1].split("tasks.named('commentUpdateCompatibilitySeoulTest')", 1)[0]
        self.assertIn("filter.excludeTestsMatching '" + f11.COMPATIBILITY_CLASS + "'", original)
        self.assertIn("includeTestsMatching '" + f11.f10.COMPATIBILITY_CLASS + "'", delete)
        self.assertNotIn('includeTestsMatching ' + "'" + f11.COMPATIBILITY_CLASS, delete)
        self.assertIn("includeTestsMatching '" + f11.COMPATIBILITY_CLASS + "'", update)
        self.assertEqual(1, update.count('includeTestsMatching'))
        for required in ('failOnNoMatchingTests = true', "systemProperty 'user.timezone', zone",
                         "systemProperty 'junit.jupiter.execution.parallel.enabled', 'false'",
                         "shouldRunAfter tasks.named('commentDeleteCompatibilitySeoulTest')"):
            self.assertIn(required, update)

    def test_given_non_dedicated_oracle_paths__when_extracting__then_no_git_or_write(self):
        self.assertEqual('0bad12b804ea5e90ed0db2d5109566e92978ea8d', oracle.SOURCE)
        self.assertEqual({'com/pawbridge/communityservice/dto/request/UpdateCommentRequest.java':
                          '0f57106e37e24707cb0e69e46ab904d1d4f529eb00944c7bf4323021b81872bd'}, oracle.EXPECTED)
        root = Path('/synthetic').resolve()
        output = root / 'backend-app/boot/build/generated/sources/f11LegacyOracle/java'
        evidence = root / 'backend-app/build/ci/f11-oracle-sha256.txt'
        for changed_output, changed_evidence in ((root / 'outside', evidence), (output, root / 'outside')):
            with self.subTest(output=changed_output, evidence=changed_evidence), \
                    patch.object(oracle.subprocess, 'check_output') as git:
                with self.assertRaisesRegex(SystemExit, 'Refusing non-dedicated F11 oracle output'):
                    oracle.extract(root, changed_output, changed_evidence)
                git.assert_not_called()

    def test_given_tampered_shared_oracle__when_extracting__then_no_new_generated_source(self):
        for stage in ('f8', 'f9'):
            for failure in ('hash', 'missing', 'differing-existing'):
                with self.subTest(stage=stage, failure=failure), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory).resolve()
                    output = root / 'backend-app/boot/build/generated/sources/f11LegacyOracle/java'
                    evidence = root / 'backend-app/build/ci/f11-oracle-sha256.txt'
                    relative, content = 'synthetic/Shared.java', b'synthetic fixed shared source'
                    shared = root / ('backend-app/boot/build/generated/sources/' + stage + 'LegacyOracle/java') / relative
                    if failure != 'missing':
                        shared.parent.mkdir(parents=True)
                        shared.write_bytes(b'different' if failure == 'differing-existing' else content)
                    expected = hashlib.sha256(content).hexdigest() if failure != 'hash' else 'wrong'
                    with patch.dict(oracle.f8.EXPECTED, {relative: expected} if stage == 'f8' else {}, clear=True), \
                            patch.dict(oracle.f9.EXPECTED, {relative: expected} if stage == 'f9' else {}, clear=True), \
                            patch.object(oracle.subprocess, 'check_output', return_value=content):
                        with self.assertRaisesRegex(SystemExit, 'Shared oracle differs from fixed F11 input'):
                            oracle.extract(root, output, evidence)
                    self.assertFalse(output.exists())
                    self.assertFalse(evidence.exists())

    def test_given_tampered_new_oracle_input__when_extracting__then_no_generated_source_or_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            output = root / 'backend-app/boot/build/generated/sources/f11LegacyOracle/java'
            evidence = root / 'backend-app/build/ci/f11-oracle-sha256.txt'
            with patch.dict(oracle.f8.EXPECTED, {}, clear=True), patch.dict(oracle.f9.EXPECTED, {}, clear=True), \
                    patch.object(oracle.subprocess, 'check_output', return_value=b'tampered'):
                with self.assertRaisesRegex(SystemExit, 'Legacy F11 input checksum mismatch'):
                    oracle.extract(root, output, evidence)
            self.assertFalse(output.exists())
            self.assertFalse(evidence.exists())

    def test_given_differing_oracle_destination__when_extracting__then_no_overwrite_or_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            output = root / 'backend-app/boot/build/generated/sources/f11LegacyOracle/java'
            evidence = root / 'backend-app/build/ci/f11-oracle-sha256.txt'
            relative, content = 'synthetic/Input.java', b'synthetic fixed source'
            target = output / relative
            target.parent.mkdir(parents=True)
            target.write_bytes(b'existing different source')
            with patch.dict(oracle.f8.EXPECTED, {}, clear=True), patch.dict(oracle.f9.EXPECTED, {}, clear=True), \
                    patch.dict(oracle.EXPECTED, {relative: hashlib.sha256(content).hexdigest()}, clear=True), \
                    patch.object(oracle.subprocess, 'check_output', return_value=content):
                with self.assertRaisesRegex(SystemExit, 'Refusing to overwrite differing F11 oracle input'):
                    oracle.extract(root, output, evidence)
            self.assertEqual(b'existing different source', target.read_bytes())
            self.assertFalse(evidence.exists())

    def test_given_actual_jar_filter__when_f11_artifacts_leak__then_reject_without_widening_validator_exception(self):
        workflow = (f11.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        pipeline = re.search(r'          if (grep -E .*?); then', workflow, re.DOTALL).group(1)
        allowed = 'BOOT-INF/lib/hibernate-validator-8.0.3.Final.jar\n'
        for forbidden in ('', 'BOOT-INF/classes/f11_fixture.sql', 'BOOT-INF/classes/f11LegacyOracle/Input.class',
                          'test/f11LegacyOracle/hibernate-validator-8.0.3.Final.jar'):
            with self.subTest(forbidden=forbidden), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / 'build/ci/boot-jar-contents.txt'
                target.parent.mkdir(parents=True)
                target.write_text(allowed + forbidden + '\n')
                actual = subprocess.run(['bash', '-o', 'pipefail', '-c', pipeline], cwd=directory,
                                        capture_output=True, text=True, timeout=3)
                self.assertEqual(0 if forbidden else 1, actual.returncode, actual.stderr)


if __name__ == '__main__':
    unittest.main()
