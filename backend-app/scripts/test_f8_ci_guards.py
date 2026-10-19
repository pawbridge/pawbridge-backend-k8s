"""Mock-only F8 ownership/oracle checks: never open Docker, DB, sockets or a JVM."""
import hashlib
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import f8_ci as f8
import prepare_f8_legacy_oracle as oracle


HOSTED = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
          'GITHUB_REPOSITORY': 'pawbridge/pawbridge-backend-k8s',
          'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1',
          'F2_TEST_JDBC_URL': 'jdbc:postgresql://127.0.0.1:54321/pawbridge_f2_ci',
          'F2_TEST_DB_USER': 'pawbridge_f2_reader', 'F2_CI_CONTAINER': 'pawbridge-f2-ci-123-1',
          'F2_TEST_ADMIN_PASSWORD': 'synthetic', 'F3_CI_OWNER': '123-1',
          'F3_TEST_WRITER_USER': 'pawbridge_f3_writer', 'F4_CI_OWNER': '123-1',
          'F5_CI_OWNER': '123-1', 'F8_CI_OWNER': '123-1', 'RUNNER_TEMP': '/synthetic',
          'F3_TEST_TOKENS_PATH': '/synthetic/f3-tokens-123-1.json'}


class F8GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_preparing_seeding_or_smoking__then_no_resource_call(self):
        for action in (f8.prepare, f8.prepare_business_rows, f8.smoke):
            with self.subTest(action=action.__name__), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f8.f3, 'sql') as sql, patch.object(f8.f2, 'command') as command, \
                    patch.object(f8.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'):
                    action()
                sql.assert_not_called()
                command.assert_not_called()
                start.assert_not_called()

    def test_given_missing_run_owner__when_preparing__then_refuse_before_sql_or_docker(self):
        values = dict(HOSTED, GITHUB_RUN_ID='')
        with patch.dict(os.environ, values, clear=True), patch.object(f8.f3, 'sql') as sql, \
                patch.object(f8.f2, 'command') as command:
            with self.assertRaisesRegex(SystemExit, 'Missing CI owner identity'):
                f8.prepare()
            sql.assert_not_called()
            command.assert_not_called()

    def test_given_wrong_target_or_reader__when_preparing__then_refuse_before_docker(self):
        for changed, message in [({'F2_TEST_JDBC_URL': 'jdbc:postgresql://127.0.0.1:54321/production'},
                                  'Refusing non-loopback, non-fixture test URL'),
                                 ({'F2_TEST_JDBC_URL': 'jdbc:postgresql://db.example:5432/pawbridge_f2_ci'},
                                  'Refusing non-loopback, non-fixture test URL'),
                                 ({'F2_TEST_DB_USER': 'postgres'}, 'Unexpected fixture reader')]:
            with self.subTest(changed=changed), patch.dict(os.environ, dict(HOSTED, **changed), clear=True), \
                    patch.object(f8.f2, 'command') as command:
                with self.assertRaisesRegex(SystemExit, message):
                    f8.prepare()
                command.assert_not_called()

    def test_given_wrong_database_or_admin_marker__when_preparing__then_no_mutation(self):
        for identity in ('production:postgres:pawbridge-f2-ci-only-v1',
                         'pawbridge_f2_ci:other:pawbridge-f2-ci-only-v1',
                         'pawbridge_f2_ci:postgres:foreign'):
            with self.subTest(identity=identity), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f8.f2, 'sql', return_value=identity) as sql, \
                    patch.object(f8.f2, 'command') as command, patch.object(f8.f3, 'sql') as mutation:
                with self.assertRaisesRegex(SystemExit, 'Fixture identity mismatch'):
                    f8.prepare()
                self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
                command.assert_not_called()
                mutation.assert_not_called()

    def test_given_foreign_container_label__when_preparing__then_inspect_only_and_refuse(self):
        inspection = [{'Config': {'Labels': {f8.f2.OWNER: 'foreign'}}}]
        with patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f8.f2, 'command', return_value=SimpleNamespace(stdout=json.dumps(inspection))) as command, \
                patch.object(f8.f3, 'sql') as mutation:
            with self.assertRaisesRegex(SystemExit, 'Refusing an unowned CI container'):
                f8.prepare()
            self.assertEqual(['docker', 'inspect', HOSTED['F2_CI_CONTAINER']], command.call_args.args[0])
            self.assertEqual(1, command.call_count)
            mutation.assert_not_called()

    def test_given_foreign_token_path__when_preparing__then_refuse_after_readonly_ancestor_marker(self):
        with patch.dict(os.environ, dict(HOSTED, F3_TEST_TOKENS_PATH='/synthetic/foreign.json'), clear=True), \
                patch.object(f8.f2, 'require_fixture'), patch.object(f8.f2, 'command') as command, \
                patch.object(f8.f3, 'sql', return_value=f8.f3.MARKER + ':123-1') as sql:
            with self.assertRaisesRegex(SystemExit, 'F3 token file must belong to this hosted run'):
                f8.prepare()
            self.assertEqual(1, sql.call_count)
            self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
            command.assert_not_called()

    def test_given_wrong_ancestor_marker__when_preparing__then_no_mutation(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f8.f3, 'require_fixture'), \
                patch.object(f8.f3, 'sql', return_value='pawbridge-f4-ci-only-v1:foreign') as sql:
            with self.assertRaisesRegex(SystemExit, 'F4 fixture identity mismatch'):
                f8.prepare()
            self.assertEqual(1, sql.call_count)
            self.assertTrue(sql.call_args.args[0].startswith('SELECT '))

    def test_given_missing_f7_grants__when_preparing__then_no_f8_ddl_or_environment_export(self):
        with patch.object(f8.f7, 'require_fixture', return_value='123-1'), \
                patch.object(f8.f3, 'sql', return_value='f') as sql, \
                patch.object(f8.f2, 'export_environment') as export:
            with self.assertRaisesRegex(SystemExit, 'requires the existing F7 search reader permissions'):
                f8.prepare()
            self.assertEqual(1, sql.call_count)
            self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
            export.assert_not_called()

    def test_given_foreign_f8_owner__when_checking_fixture__then_no_marker_query(self):
        with patch.dict(os.environ, {'F8_CI_OWNER': 'foreign'}, clear=True), \
                patch.object(f8, 'require_parent_fixture', return_value='123-1'), \
                patch.object(f8.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'F8 fixture owner mismatch'):
                f8.require_fixture()
            sql.assert_not_called()

    def test_given_mismatched_owned_port__when_preparing__then_no_ddl_or_environment_export(self):
        for ports in (None, [{'HostIp': '127.0.0.1', 'HostPort': '54322'}],
                      [{'HostIp': '0.0.0.0', 'HostPort': '54321'}]):
            with self.subTest(ports=ports), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f8.f7, 'require_fixture', return_value='123-1'), \
                    patch.object(f8.f3, 'sql', return_value='t') as sql, \
                    patch.object(f8.f2, 'inspect_owned', return_value={'NetworkSettings': {'Ports': {'5432/tcp': ports}}}), \
                    patch.object(f8.f2, 'export_environment') as export:
                with self.assertRaisesRegex(SystemExit, 'JDBC target must match the owned fixture container port'):
                    f8.prepare()
                self.assertEqual(1, sql.call_count)
                self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
                export.assert_not_called()

    def test_given_matching_owned_port__when_checking_parent__then_keep_the_same_fixture(self):
        inspection = {'NetworkSettings': {'Ports': {'5432/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '54321'}]}}}
        with patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f8.f7, 'require_fixture', return_value='123-1'), \
                patch.object(f8.f3, 'sql', return_value='t'), \
                patch.object(f8.f2, 'inspect_owned', return_value=inspection) as inspect:
            self.assertEqual('123-1', f8.require_parent_fixture())
            inspect.assert_called_once_with('pawbridge-f2-ci-123-1')

    def test_given_wrong_f8_marker_or_missing_grants__when_checking_fixture__then_refuse(self):
        for responses, message in [([f8.MARKER + ':foreign'], 'F8 fixture identity mismatch'),
                                   ([f8.MARKER + ':123-1', 'f'], 'lacks the exact comment reader columns')]:
            with self.subTest(message=message), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f8, 'require_parent_fixture', return_value='123-1'), \
                    patch.object(f8.f3, 'sql', side_effect=responses) as sql:
                with self.assertRaisesRegex(SystemExit, message):
                    f8.require_fixture()
                self.assertTrue(all(call.args[0].startswith('SELECT ') for call in sql.call_args_list))

    def test_given_owned_fixture__when_preparing__then_only_comment_schema_and_minimal_reader_grant(self):
        with patch.object(f8, 'require_parent_fixture', return_value='123-1'), \
                patch.object(f8.f2, 'export_environment') as export, \
                patch.object(f8, 'require_fixture', return_value='123-1') as checked, \
                patch.object(f8.f3, 'sql') as sql:
            f8.prepare()
            export.assert_called_once_with({'F8_CI_OWNER': '123-1'})
            checked.assert_called_once_with()
            ddl = sql.call_args.kwargs['input_text']
            self.assertIn("VALUES ('pawbridge-f8-ci-only-v1', :'fixture_owner')", ddl)
            self.assertIn('updated_at TIMESTAMP(6) NOT NULL', ddl)
            self.assertIn('GRANT SELECT(' + f8.COMMENT_COLUMNS + ')\n'
                          ' ON pawbridge_community.comments TO pawbridge_f2_reader;', ddl)
            self.assertNotIn('REFERENCES', ddl)
            self.assertNotIn('pawbridge_f3_writer', ddl)
            self.assertNotIn('ALTER TABLE', ddl)

    def test_given_unowned_fixture__when_seeding__then_no_business_mutation(self):
        with patch.object(f8, 'require_fixture', side_effect=SystemExit('unowned')), \
                patch.object(f8.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'unowned'):
                f8.prepare_business_rows()
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
            with patch.dict(os.environ, values, clear=True), patch.object(f8, 'ROOT', root), \
                    patch.object(f8, 'EVIDENCE', root / 'evidence'), \
                    patch.object(f8, 'require_fixture', return_value='123-1'), \
                    patch.object(f8.f4, 'start_ready_children', side_effect=AssertionError('readiness failure')), \
                    patch.object(f8, 'prepare_business_rows') as rows, patch.object(f8.f3, 'sql') as sql:
                with self.assertRaisesRegex(AssertionError, 'readiness failure'):
                    f8.smoke()
                rows.assert_not_called()
                sql.assert_not_called()

    def test_given_non_dedicated_oracle_output__when_extracting__then_no_git_or_write(self):
        with patch.object(oracle.subprocess, 'check_output') as git:
            with self.assertRaisesRegex(SystemExit, 'Refusing non-dedicated F8 oracle output'):
                oracle.extract('/synthetic', '/outside', '/outside')
            git.assert_not_called()

    def test_given_tampered_oracle_input__when_extracting__then_no_generated_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            output = root / 'backend-app/boot/build/generated/sources/f8LegacyOracle/java'
            evidence = root / 'backend-app/build/ci/f8-oracle-sha256.txt'
            with patch.object(oracle.subprocess, 'check_output', return_value=b'tampered'):
                with self.assertRaisesRegex(SystemExit, 'Legacy F8 input checksum mismatch'):
                    oracle.extract(root, output, evidence)
            self.assertFalse(output.exists())
            self.assertFalse(evidence.exists())

    def test_given_differing_oracle_destination__when_extracting__then_no_overwrite_or_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            output = root / 'backend-app/boot/build/generated/sources/f8LegacyOracle/java'
            evidence = root / 'backend-app/build/ci/f8-oracle-sha256.txt'
            content = b'synthetic fixed source'
            relative = 'synthetic/Input.java'
            target = output / relative
            target.parent.mkdir(parents=True)
            target.write_bytes(b'existing different source')
            with patch.dict(oracle.EXPECTED, {relative: hashlib.sha256(content).hexdigest()}, clear=True), \
                    patch.object(oracle.subprocess, 'check_output', return_value=content):
                with self.assertRaisesRegex(SystemExit, 'Refusing to overwrite differing F8 oracle input'):
                    oracle.extract(root, output, evidence)
            self.assertEqual(b'existing different source', target.read_bytes())
            self.assertFalse(evidence.exists())


if __name__ == '__main__':
    unittest.main()
