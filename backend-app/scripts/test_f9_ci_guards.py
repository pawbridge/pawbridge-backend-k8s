"""Mock-only F9 fixture/CI checks; never start a DB, Docker, JVM or socket."""
import json
import os
from pathlib import Path
import re
import subprocess
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import f9_ci as f9
from test_f8_ci_guards import HOSTED as F8_HOSTED

HOSTED = dict(F8_HOSTED, F9_CI_OWNER='123-1')


class F9GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_preparing_seeding_or_smoking__then_no_resource_call(self):
        for action in (f9.prepare, f9.prepare_business_rows, f9.smoke):
            with self.subTest(action=action.__name__), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f9.f3, 'sql') as sql, patch.object(f9.f2, 'command') as command, \
                    patch.object(f9.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'):
                    action()
                sql.assert_not_called()
                command.assert_not_called()
                start.assert_not_called()

    def test_given_missing_run_owner__when_preparing__then_refuse_before_sql_or_docker(self):
        with patch.dict(os.environ, dict(HOSTED, GITHUB_RUN_ID=''), clear=True), \
                patch.object(f9.f3, 'sql') as sql, patch.object(f9.f2, 'command') as command:
            with self.assertRaisesRegex(SystemExit, 'Missing CI owner identity'):
                f9.prepare()
            sql.assert_not_called()
            command.assert_not_called()

    def test_given_wrong_target_or_reader__when_preparing__then_inherit_f8_refusal_before_docker(self):
        for changed, message in [({'F2_TEST_JDBC_URL': 'jdbc:postgresql://127.0.0.1:54321/production'},
                                  'Refusing non-loopback, non-fixture test URL'),
                                 ({'F2_TEST_JDBC_URL': 'jdbc:postgresql://db.example:5432/pawbridge_f2_ci'},
                                  'Refusing non-loopback, non-fixture test URL'),
                                 ({'F2_TEST_DB_USER': 'postgres'}, 'Unexpected fixture reader')]:
            with self.subTest(changed=changed), patch.dict(os.environ, dict(HOSTED, **changed), clear=True), \
                    patch.object(f9.f2, 'command') as command:
                with self.assertRaisesRegex(SystemExit, message):
                    f9.prepare()
                command.assert_not_called()

    def test_given_foreign_container_label__when_preparing__then_inspect_only_without_mutation(self):
        inspection = [{'Config': {'Labels': {f9.f2.OWNER: 'foreign'}}}]
        with patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f9.f2, 'command', return_value=SimpleNamespace(stdout=json.dumps(inspection))) as command, \
                patch.object(f9.f3, 'sql') as mutation:
            with self.assertRaisesRegex(SystemExit, 'Refusing an unowned CI container'):
                f9.prepare()
            self.assertEqual(1, command.call_count)
            self.assertEqual(['docker', 'inspect', HOSTED['F2_CI_CONTAINER']], command.call_args.args[0])
            mutation.assert_not_called()

    def test_given_wrong_database_or_admin_marker__when_preparing__then_no_mutation(self):
        for identity in ('production:postgres:pawbridge-f2-ci-only-v1',
                         'pawbridge_f2_ci:other:pawbridge-f2-ci-only-v1'):
            with self.subTest(identity=identity), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f9.f2, 'sql', return_value=identity) as sql, \
                    patch.object(f9.f2, 'command') as command, patch.object(f9.f3, 'sql') as mutation:
                with self.assertRaisesRegex(SystemExit, 'Fixture identity mismatch'):
                    f9.prepare()
                self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
                command.assert_not_called()
                mutation.assert_not_called()

    def test_given_foreign_token_path__when_preparing__then_readonly_ancestor_check_and_refusal(self):
        with patch.dict(os.environ, dict(HOSTED, F3_TEST_TOKENS_PATH='/synthetic/foreign.json'), clear=True), \
                patch.object(f9.f2, 'require_fixture'), patch.object(f9.f2, 'command') as command, \
                patch.object(f9.f3, 'sql', return_value=f9.f3.MARKER + ':123-1') as sql:
            with self.assertRaisesRegex(SystemExit, 'F3 token file must belong to this hosted run'):
                f9.prepare()
            self.assertEqual(1, sql.call_count)
            self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
            command.assert_not_called()

    def test_given_wrong_f8_marker__when_preparing__then_no_f9_mutation_or_export(self):
        with patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f9.f8, 'require_parent_fixture', return_value='123-1'), \
                patch.object(f9.f3, 'sql', return_value=f9.f8.MARKER + ':foreign') as sql, \
                patch.object(f9.f2, 'export_environment') as export:
            with self.assertRaisesRegex(SystemExit, 'F8 fixture identity mismatch'):
                f9.prepare()
            self.assertEqual(1, sql.call_count)
            self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
            export.assert_not_called()

    def test_given_mismatched_owned_port__when_preparing__then_no_ddl_or_environment_export(self):
        for ports in (None, [{'HostIp': '127.0.0.1', 'HostPort': '54322'}],
                      [{'HostIp': '0.0.0.0', 'HostPort': '54321'}]):
            with self.subTest(ports=ports), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f9.f8.f7, 'require_fixture', return_value='123-1'), \
                    patch.object(f9.f3, 'sql', return_value='t') as sql, \
                    patch.object(f9.f2, 'inspect_owned', return_value={'NetworkSettings': {'Ports': {'5432/tcp': ports}}}), \
                    patch.object(f9.f2, 'export_environment') as export:
                with self.assertRaisesRegex(SystemExit, 'JDBC target must match the owned fixture container port'):
                    f9.prepare()
                self.assertEqual(1, sql.call_count)
                self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
                export.assert_not_called()

    def test_given_foreign_f9_owner__when_checking_fixture__then_no_f9_marker_query(self):
        for owner in ('foreign', ''):
            with self.subTest(owner=owner), patch.dict(os.environ, {'F9_CI_OWNER': owner}, clear=True), \
                    patch.object(f9.f8, 'require_fixture', return_value='123-1'), \
                    patch.object(f9.f3, 'sql') as sql:
                with self.assertRaisesRegex(SystemExit, 'F9 fixture owner mismatch'):
                    f9.require_fixture()
                sql.assert_not_called()

    def test_given_wrong_f9_marker_or_grants__when_checking_fixture__then_refuse_without_mutation(self):
        for responses, message in [([f9.MARKER + ':foreign'], 'F9 fixture identity mismatch'),
                                   ([f9.MARKER + ':123-1', 'f'], 'lacks the exact comment writer grants')]:
            with self.subTest(message=message), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f9.f8, 'require_fixture', return_value='123-1'), \
                    patch.object(f9.f3, 'sql', side_effect=responses) as sql:
                with self.assertRaisesRegex(SystemExit, message):
                    f9.require_fixture()
                self.assertTrue(all(call.args[0].startswith('SELECT ') for call in sql.call_args_list))

    def test_given_owned_fixture__when_preparing__then_only_named_charset_check_and_minimal_writer_grants(self):
        with patch.object(f9.f8, 'require_fixture', return_value='123-1'), \
                patch.object(f9.f2, 'export_environment') as export, \
                patch.object(f9, 'require_fixture', return_value='123-1') as checked, \
                patch.object(f9.f3, 'sql') as sql:
            f9.prepare()
            export.assert_called_once_with({'F9_CI_OWNER': '123-1'})
            checked.assert_called_once_with()
            ddl = sql.call_args.kwargs['input_text']
            self.assertIn("VALUES ('pawbridge-f9-ci-only-v1', :'fixture_owner')", ddl)
            self.assertIn('ADD CONSTRAINT ck_rollback_charset_comments\n'
                          ' CHECK (content COLLATE "C" !~ U&\'[\\+010000-\\+10FFFF]\');', ddl)
            self.assertIn('GRANT INSERT(' + f9.INSERT_COLUMNS + ')\n'
                          ' ON pawbridge_community.comments TO pawbridge_f3_writer;', ddl)
            self.assertIn('GRANT SELECT(comment_id) ON pawbridge_community.comments TO pawbridge_f3_writer;', ddl)
            for forbidden in ('CREATE ROLE', 'GRANT UPDATE', 'GRANT DELETE', 'GRANT ALL',
                              'ON pawbridge_user.', 'ON pawbridge_community.posts', 'REFERENCES',
                              'TRUNCATE', 'DROP ', 'pawbridge_f2_reader', 'GRANT USAGE', 'GRANT SELECT ON SEQUENCE'):
                self.assertNotIn(forbidden, ddl)

    def test_given_unowned_fixture__when_seeding__then_no_business_mutation(self):
        with patch.object(f9, 'require_fixture', side_effect=SystemExit('unowned')), \
                patch.object(f9.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'unowned'):
                f9.prepare_business_rows()
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
            with patch.dict(os.environ, values, clear=True), patch.object(f9, 'ROOT', root), \
                    patch.object(f9, 'EVIDENCE', root / 'evidence'), \
                    patch.object(f9, 'require_fixture', return_value='123-1'), \
                    patch.object(f9.f4, 'start_ready_children', side_effect=AssertionError('readiness failure')), \
                    patch.object(f9, 'prepare_business_rows') as rows, patch.object(f9.f3, 'sql') as sql:
                with self.assertRaisesRegex(AssertionError, 'readiness failure'):
                    f9.smoke()
                rows.assert_not_called()
                sql.assert_not_called()

    def test_given_f9_workflow__when_declaring_checks__then_prepare_before_packaged_checks_and_smoke_after_f8(self):
        workflow = (f9.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        self.assertLess(workflow.index('- name: Extend the owned fixture with minimal F9 comment INSERT permissions'),
                        workflow.index('- name: Verify production JAR isolation\n'))
        self.assertNotIn('python3 scripts/f9_ci.py smoke', workflow, 'The packaged F9 smoke moved to the Spring contract tests.')
        for required in ('scripts/test_f9_ci_guards.py', 'build/ci/f9-helper-refusal.txt',
                         'f[0-9]+_fixture', 'com/pawbridge/backend/compatibility/',
                         "grep -Ev '^BOOT-INF/lib/(hibernate-validator-[0-9][^/]*|spring-data-redis-3[.]4[.]11|feign-(core|form)-13[.]5|hibernate-core-6[.]6[.]33[.]Final|hibernate-commons-annotations-7[.]0[.]3[.]Final|spring-data-jpa-3[.]4[.]11)\\.jar$'"):
            self.assertIn(required, workflow)

    def test_given_actual_jar_filter__when_allowing_validator__then_orm_compatibility_and_test_artifacts_still_fail(self):
        workflow = (f9.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        pipeline = re.search(r'          if (grep -E .*?); then', workflow, re.DOTALL).group(1)
        allowed = ('BOOT-INF/lib/hibernate-validator-8.0.3.Final.jar\n'
                   'BOOT-INF/lib/spring-data-redis-3.4.11.jar\n'
                   'BOOT-INF/lib/feign-core-13.5.jar\n'
                   'BOOT-INF/lib/feign-form-13.5.jar\n'
                   'BOOT-INF/lib/hibernate-core-6.6.33.Final.jar\n'
                   'BOOT-INF/lib/hibernate-commons-annotations-7.0.3.Final.jar\n'
                   'BOOT-INF/lib/spring-data-jpa-3.4.11.jar\n'
                   'BOOT-INF/lib/spring-aspects-6.2.12.jar\n'
                   'BOOT-INF/lib/aspectjweaver-1.9.24.jar\n')
        for forbidden in ('', 'BOOT-INF/lib/hibernate-core-6.6.31.Final.jar',
                          'BOOT-INF/lib/hibernate-other-1.jar',
                          'BOOT-INF/lib/hibernate-core-6.6.33.Final-extra.jar',
                          'BOOT-INF/lib/hibernate-commons-annotations-7.0.2.Final.jar',
                          'BOOT-INF/lib/hibernate-commons-annotations-7.0.3.Final-extra.jar',
                          'BOOT-INF/lib/spring-data-jpa-3.4.10.jar',
                          'BOOT-INF/lib/spring-data-jpa-3.4.11-extra.jar',
                          'BOOT-INF/classes/hibernate-core-6.6.33.Final.jar',
                          'BOOT-INF/classes/hibernate-commons-annotations-7.0.3.Final.jar',
                          'BOOT-INF/classes/spring-data-jpa-3.4.11.jar',
                          'BOOT-INF/lib/spring-data-redis-3.4.10.jar',
                          'BOOT-INF/lib/feign-core-13.4.jar', 'BOOT-INF/lib/feign-core-13.50.jar',
                          'BOOT-INF/lib/feign-okhttp-13.5.jar', 'BOOT-INF/lib/feign-core-13.5-extra.jar',
                          'BOOT-INF/classes/feign-core-13.5.jar',
                          'BOOT-INF/lib/feign-form-13.4.jar', 'BOOT-INF/lib/feign-form-13.50.jar',
                          'BOOT-INF/lib/feign-form-spring-13.5.jar', 'BOOT-INF/lib/feign-form-extra-13.5.jar',
                          'BOOT-INF/lib/feign-form-13.5-extra.jar', 'BOOT-INF/classes/feign-form-13.5.jar',
                          'test/feign-form-13.5.jar',
                          'BOOT-INF/lib/junit-jupiter-5.11.4.jar', 'BOOT-INF/classes/f9_fixture.sql',
                          'BOOT-INF/classes/com/pawbridge/backend/compatibility/Input.class',
                          'BOOT-INF/classes/com/pawbridge/communityservice/Input.class',
                          'test/compatibilityTest/hibernate-validator-8.0.3.Final.jar'):
            with self.subTest(forbidden=forbidden), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / 'build/ci/boot-jar-contents.txt'
                target.parent.mkdir(parents=True)
                target.write_text(allowed + forbidden + '\n')
                actual = subprocess.run(['bash', '-o', 'pipefail', '-c', pipeline], cwd=directory,
                                        capture_output=True, text=True, timeout=3)
                self.assertEqual(0 if forbidden else 1, actual.returncode, actual.stderr)


if __name__ == '__main__':
    unittest.main()
