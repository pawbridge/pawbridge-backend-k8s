"""Mock-only F10 staged ACL/target/report checks; no Docker, DB, JVM or socket."""
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

import f10_ci as f10
from test_f9_ci_guards import HOSTED as F9_HOSTED

HOSTED = dict(F9_HOSTED, F10_CI_OWNER='123-1')


class F10GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_preparing_seeding_reporting_or_smoking__then_no_resource_call(self):
        for action in (f10.prepare, f10.prepare_business_rows, f10.verify_results, f10.smoke):
            with self.subTest(action=action.__name__), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f10.f3, 'sql') as sql, patch.object(f10.f2, 'command') as command, \
                    patch.object(f10.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'):
                    action()
                sql.assert_not_called()
                command.assert_not_called()
                start.assert_not_called()

    def test_given_missing_run_owner__when_preparing__then_refuse_before_sql_or_docker(self):
        with patch.dict(os.environ, dict(HOSTED, GITHUB_RUN_ID=''), clear=True), \
                patch.object(f10.f3, 'sql') as sql, patch.object(f10.f2, 'command') as command:
            with self.assertRaisesRegex(SystemExit, 'Missing CI owner identity'):
                f10.prepare()
            sql.assert_not_called()
            command.assert_not_called()

    def test_given_wrong_target_or_reader__when_preparing__then_inherit_refusal_before_docker(self):
        for changed, message in [({'F2_TEST_JDBC_URL': 'jdbc:postgresql://127.0.0.1:54321/production'},
                                  'Refusing non-loopback, non-fixture test URL'),
                                 ({'F2_TEST_JDBC_URL': 'jdbc:postgresql://db.example:5432/pawbridge_f2_ci'},
                                  'Refusing non-loopback, non-fixture test URL'),
                                 ({'F2_TEST_DB_USER': 'postgres'}, 'Unexpected fixture reader')]:
            with self.subTest(changed=changed), patch.dict(os.environ, dict(HOSTED, **changed), clear=True), \
                    patch.object(f10.f2, 'command') as command:
                with self.assertRaisesRegex(SystemExit, message):
                    f10.prepare()
                command.assert_not_called()

    def test_given_foreign_container_label__when_preparing__then_inspect_only_without_mutation(self):
        inspection = [{'Config': {'Labels': {f10.f2.OWNER: 'foreign'}}}]
        with patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f10.f2, 'command', return_value=SimpleNamespace(stdout=json.dumps(inspection))) as command, \
                patch.object(f10.f3, 'sql') as mutation:
            with self.assertRaisesRegex(SystemExit, 'Refusing an unowned CI container'):
                f10.prepare()
            self.assertEqual(1, command.call_count)
            self.assertEqual(['docker', 'inspect', HOSTED['F2_CI_CONTAINER']], command.call_args.args[0])
            mutation.assert_not_called()

    def test_given_wrong_admin_or_database_marker__when_preparing__then_no_mutation(self):
        for identity in ('production:postgres:pawbridge-f2-ci-only-v1',
                         'pawbridge_f2_ci:other:pawbridge-f2-ci-only-v1'):
            with self.subTest(identity=identity), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f10.f2, 'sql', return_value=identity) as sql, \
                    patch.object(f10.f2, 'command') as command, patch.object(f10.f3, 'sql') as mutation:
                with self.assertRaisesRegex(SystemExit, 'Fixture identity mismatch'):
                    f10.prepare()
                self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
                command.assert_not_called()
                mutation.assert_not_called()

    def test_given_foreign_token_path__when_preparing__then_ancestor_select_only_and_refusal(self):
        with patch.dict(os.environ, dict(HOSTED, F3_TEST_TOKENS_PATH='/synthetic/foreign.json'), clear=True), \
                patch.object(f10.f2, 'require_fixture'), patch.object(f10.f2, 'command') as command, \
                patch.object(f10.f3, 'sql', return_value=f10.f3.MARKER + ':123-1') as sql:
            with self.assertRaisesRegex(SystemExit, 'F3 token file must belong to this hosted run'):
                f10.prepare()
            self.assertEqual(1, sql.call_count)
            self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
            command.assert_not_called()

    def test_given_mismatched_owned_port__when_preparing__then_no_ddl_or_environment_export(self):
        for ports in (None, [{'HostIp': '127.0.0.1', 'HostPort': '54322'}],
                      [{'HostIp': '0.0.0.0', 'HostPort': '54321'}]):
            with self.subTest(ports=ports), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f10.f8.f7, 'require_fixture', return_value='123-1'), \
                    patch.object(f10.f3, 'sql', return_value='t') as sql, \
                    patch.object(f10.f2, 'inspect_owned', return_value={'NetworkSettings': {'Ports': {'5432/tcp': ports}}}), \
                    patch.object(f10.f2, 'export_environment') as export:
                with self.assertRaisesRegex(SystemExit, 'JDBC target must match the owned fixture container port'):
                    f10.prepare()
                self.assertEqual(1, sql.call_count)
                self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
                export.assert_not_called()

    def test_given_non_original_f9_acl__when_preparing__then_no_f10_mutation_or_export(self):
        with patch.object(f10.f9, 'require_fixture', side_effect=SystemExit('F9 strict ACL mismatch')), \
                patch.object(f10.f3, 'sql') as sql, patch.object(f10.f2, 'export_environment') as export:
            with self.assertRaisesRegex(SystemExit, 'F9 strict ACL mismatch'):
                f10.prepare()
            sql.assert_not_called()
            export.assert_not_called()

    def test_given_owned_original_acl__when_preparing__then_extend_only_two_select_and_two_update_columns(self):
        events = []
        with patch.object(f10.f9, 'require_fixture', side_effect=lambda: events.append('F9') or '123-1'), \
                patch.object(f10.f2, 'export_environment', side_effect=lambda values: events.append('owner')) as export, \
                patch.object(f10.f3, 'sql', side_effect=lambda **kwargs: events.append('DDL')) as sql, \
                patch.object(f10, 'require_fixture', side_effect=lambda: events.append('F10') or '123-1'):
            f10.prepare()
            self.assertEqual(['F9', 'owner', 'DDL', 'F10'], events)
            export.assert_called_once_with({'F10_CI_OWNER': '123-1'})
            ddl = sql.call_args.kwargs['input_text']
            self.assertIn("VALUES ('pawbridge-f10-ci-only-v1', :'fixture_owner')", ddl)
            self.assertIn('GRANT SELECT(author_id,deleted_at) ON pawbridge_community.comments TO pawbridge_f3_writer;', ddl)
            self.assertIn('GRANT UPDATE(deleted_at,updated_at) ON pawbridge_community.comments TO pawbridge_f3_writer;', ddl)
            for forbidden in ('CREATE ROLE', 'GRANT INSERT', 'GRANT DELETE', 'GRANT ALL', 'GRANT USAGE',
                              'ON pawbridge_user.', 'ON pawbridge_community.posts', 'ALTER TABLE', 'TRUNCATE', 'DROP '):
                self.assertNotIn(forbidden, ddl)

    def test_given_foreign_f9_or_f10_owner__when_checking_extended_fixture__then_refuse_before_mutation(self):
        for stage in (9, 10):
            with self.subTest(stage=stage), \
                    patch.dict(os.environ, dict(HOSTED, **{'F' + str(stage) + '_CI_OWNER': 'foreign'}), clear=True), \
                    patch.object(f10.f8, 'require_fixture', return_value='123-1'), \
                    patch.object(f10.f3, 'sql', return_value=f10.f9.MARKER + ':123-1') as sql:
                with self.assertRaisesRegex(SystemExit, 'F' + str(stage) + ' fixture owner mismatch'):
                    f10.require_fixture()
                self.assertTrue(all(call.args[0].startswith('SELECT ') for call in sql.call_args_list))

    def test_given_foreign_stage_marker_or_wrong_acl__when_checking_extended_fixture__then_select_only_and_refuse(self):
        for responses, message in [(['foreign'], 'F9 fixture identity mismatch'),
                                   ([f10.f9.MARKER + ':123-1', 'foreign'], 'F10 fixture identity mismatch'),
                                   ([f10.f9.MARKER + ':123-1', f10.MARKER + ':123-1', 'f'], 'exact extended comment ACL')]:
            with self.subTest(message=message), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f10.f8, 'require_fixture', return_value='123-1'), \
                    patch.object(f10.f3, 'sql', side_effect=responses) as sql:
                with self.assertRaisesRegex(SystemExit, message):
                    f10.require_fixture()
                self.assertTrue(all(call.args[0].startswith('SELECT ') for call in sql.call_args_list))

    def test_given_exact_extended_acl__when_checking_fixture__then_keep_f9_negative_checks_without_recalling_strict_acl(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f10.f8, 'require_fixture', return_value='123-1'), \
                patch.object(f10.f9, 'require_fixture') as strict, \
                patch.object(f10.f3, 'sql', side_effect=[f10.f9.MARKER + ':123-1', f10.MARKER + ':123-1', 't']) as sql:
            self.assertEqual('123-1', f10.require_fixture())
            strict.assert_not_called()
            acl = sql.call_args.args[0]
            for required in ('count(*)=7', "column_name IN ('comment_id','author_id','deleted_at')",
                             "column_name IN ('deleted_at','updated_at')", 'AND convalidated',
                             "'USAGE,SELECT,UPDATE'", "'pawbridge_user.users','INSERT,UPDATE,DELETE'",
                             "'pawbridge_community','CREATE'", "'pawbridge_community.comments','DELETE'"):
                self.assertIn(required, acl)

    def test_given_unowned_fixture__when_seeding__then_no_business_mutation(self):
        with patch.object(f10, 'require_fixture', side_effect=SystemExit('unowned')), patch.object(f10.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'unowned'):
                f10.prepare_business_rows()
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
            with patch.dict(os.environ, values, clear=True), patch.object(f10, 'ROOT', root), \
                    patch.object(f10, 'EVIDENCE', root / 'evidence'), \
                    patch.object(f10, 'require_fixture', return_value='123-1'), \
                    patch.object(f10.f4, 'start_ready_children', side_effect=AssertionError('readiness failure')), \
                    patch.object(f10, 'prepare_business_rows') as rows, patch.object(f10.f3, 'sql') as sql:
                with self.assertRaisesRegex(AssertionError, 'readiness failure'):
                    f10.smoke()
                rows.assert_not_called()
                sql.assert_not_called()

    @staticmethod
    def reports(root, *, tests=1, failures=0, errors=0, skipped=0, classname=None, cases=None):
        classname = classname or f10.COMPATIBILITY_CLASS
        for task in ('commentDeleteCompatibilityTest', 'commentDeleteCompatibilitySeoulTest'):
            path = root / 'boot/build/test-results' / task / ('TEST-' + f10.COMPATIBILITY_CLASS + '.xml')
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
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f10, 'ROOT', root):
                self.assertEqual({'UTC': 2, 'Asia/Seoul': 2}, f10.verify_results())

    def test_given_missing_zero_failed_skipped_or_wrong_reports__when_verifying__then_refuse_not_skip(self):
        for settings in (None, {'tests': 0}, {'failures': 1}, {'errors': 1}, {'skipped': 1},
                         {'classname': 'OtherTest'}, {'tests': 2, 'cases': 1}):
            with self.subTest(settings=settings), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                if settings is not None:
                    self.reports(root, **settings)
                with patch.dict(os.environ, HOSTED, clear=True), patch.object(f10, 'ROOT', root):
                    with self.assertRaisesRegex(SystemExit, 'F10 compatibility report'):
                        f10.verify_results()

    def test_given_workflow_phases__when_declaring_checks__then_finish_original_acl_before_extending_and_use_exact_tasks(self):
        workflow = (f10.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('- name: Extend the owned fixture with exact F10 comment deletion permissions')
        self.assertLess(workflow.index('python3 scripts/f9_ci.py smoke'), boundary)
        self.assertLess(workflow.index('python3 scripts/f10_ci.py prepare', boundary),
                        workflow.index(':boot:commentDeleteCompatibilityTest'))
        self.assertLess(workflow.index(':boot:commentDeleteCompatibilitySeoulTest'),
                        workflow.index('python3 scripts/f10_ci.py results'))
        self.assertLess(workflow.index('python3 scripts/f10_ci.py results'), workflow.index('python3 scripts/f10_ci.py smoke'))
        after = workflow[boundary:]
        self.assertNotRegex(after, r'python3 scripts/f9_ci\.py (?:prepare|smoke)')
        for required in ('scripts/test_f10_ci_guards.py', 'build/ci/f10-helper-refusal.txt',
                         'f10_fixture', 'f10LegacyOracle', 'build/ci/f8-oracle-sha256.txt'):
            self.assertIn(required, workflow)

    def test_given_actual_jar_filter__when_f10_artifacts_leak__then_reject_without_widening_validator_exception(self):
        workflow = (f10.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        pipeline = re.search(r'          if (grep -E .*?); then', workflow, re.DOTALL).group(1)
        allowed = 'BOOT-INF/lib/hibernate-validator-8.0.3.Final.jar\n'
        for forbidden in ('', 'BOOT-INF/classes/f10_fixture.sql', 'BOOT-INF/classes/f10LegacyOracle/Input.class',
                          'test/f10LegacyOracle/hibernate-validator-8.0.3.Final.jar'):
            with self.subTest(forbidden=forbidden), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / 'build/ci/boot-jar-contents.txt'
                target.parent.mkdir(parents=True)
                target.write_text(allowed + forbidden + '\n')
                actual = subprocess.run(['bash', '-o', 'pipefail', '-c', pipeline], cwd=directory,
                                        capture_output=True, text=True, timeout=3)
                self.assertEqual(0 if forbidden else 1, actual.returncode, actual.stderr)


if __name__ == '__main__':
    unittest.main()
