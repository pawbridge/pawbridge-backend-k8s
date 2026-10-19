"""Non-executing F6 guard tests: no database, sockets, JVM, containers or S3."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import f6_ci as f6
import prepare_f6_legacy_oracle as oracle


class F6GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_smoking__then_refuse_before_any_resource_call(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(f6.f3, 'sql') as sql, \
                patch.object(f6.f4, 'start_ready_children') as start:
            with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'):
                f6.smoke()
            sql.assert_not_called()
            start.assert_not_called()

    def test_given_foreign_owner__when_checking_existing_marker_chain__then_refuse_before_sql(self):
        with patch.dict(os.environ, {'F5_CI_OWNER': 'other'}, clear=True), \
                patch.object(f6.f4, 'require_fixture', return_value='123-1'), \
                patch.object(f6.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'F5 fixture owner mismatch'):
                f6.require_fixture()
            sql.assert_not_called()

    def test_given_wrong_marker__when_checking_existing_fixture__then_refuse_without_mutation(self):
        with patch.dict(os.environ, {'F5_CI_OWNER': '123-1'}, clear=True), \
                patch.object(f6.f4, 'require_fixture', return_value='123-1'), \
                patch.object(f6.f3, 'sql', return_value='pawbridge-f5-ci-only-v1:foreign') as sql:
            with self.assertRaisesRegex(SystemExit, 'F5 fixture identity mismatch'):
                f6.require_fixture()
            self.assertEqual(1, sql.call_count)
            self.assertTrue(sql.call_args.args[0].startswith('SELECT '))

    def test_given_unowned_fixture__when_preparing_rows__then_no_mutation(self):
        with patch.object(f6, 'require_fixture', side_effect=SystemExit('unowned')), \
                patch.object(f6.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'unowned'):
                f6.prepare_business_rows()
            sql.assert_not_called()

    def test_given_readiness_failure__when_smoking__then_no_business_row_preparation(self):
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
            with patch.dict(os.environ, values, clear=True), patch.object(f6, 'ROOT', root), \
                    patch.object(f6, 'EVIDENCE', root / 'evidence'), \
                    patch.object(f6, 'require_fixture', return_value='123-1'), \
                    patch.object(f6.f4, 'start_ready_children', side_effect=AssertionError('readiness failure')), \
                    patch.object(f6, 'prepare_business_rows') as rows, patch.object(f6.f3, 'sql') as sql:
                with self.assertRaisesRegex(AssertionError, 'readiness failure'):
                    f6.smoke()
                rows.assert_not_called()
                sql.assert_not_called()

    def test_given_non_dedicated_oracle_output__when_extracting__then_refuse_before_git_or_writes(self):
        with patch.object(oracle.sys, 'argv', ['oracle', '/synthetic', '/outside', '/outside']), \
                patch.object(oracle.subprocess, 'check_output') as git:
            with self.assertRaisesRegex(SystemExit, 'Refusing non-dedicated F6 oracle output'):
                oracle.main()
            git.assert_not_called()

    def test_given_tampered_shared_oracle__when_extracting__then_refuse_before_new_generated_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory).resolve()
            output = repository / 'backend-app/boot/build/generated/sources/f6LegacyOracle/java'
            evidence = repository / 'backend-app/build/ci/f6-oracle-sha256.txt'
            with patch.object(oracle.sys, 'argv', ['oracle', str(repository), str(output), str(evidence)]), \
                    patch.object(oracle.subprocess, 'check_output', return_value=b'tampered'):
                with self.assertRaisesRegex(SystemExit, 'Shared F2 oracle differs from fixed F6 input'):
                    oracle.main()
            self.assertFalse(output.exists())
            self.assertFalse(evidence.exists())


if __name__ == '__main__':
    unittest.main()
