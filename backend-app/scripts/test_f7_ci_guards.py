"""Mock-only F7 guards; never start a socket, JVM, container, DB or S3 service."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import f7_ci as f7


class F7GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_preparing_or_smoking__then_refuse_before_resources(self):
        for action in (f7.prepare, f7.smoke):
            with self.subTest(action=action.__name__), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f7.f3, 'sql') as sql, patch.object(f7.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'):
                    action()
                sql.assert_not_called()
                start.assert_not_called()

    def test_given_unowned_fixture__when_preparing_grants_or_rows__then_no_sql_mutation(self):
        for action in (f7.prepare, f7.prepare_business_rows):
            with self.subTest(action=action.__name__), \
                    patch.object(f7, 'require_fixture', side_effect=SystemExit('unowned')), \
                    patch.object(f7.f3, 'sql') as sql:
                with self.assertRaisesRegex(SystemExit, 'unowned'):
                    action()
                sql.assert_not_called()

    def test_given_owned_fixture__when_preparing__then_grant_only_the_five_search_columns(self):
        with patch.object(f7, 'require_fixture', return_value='123-1'), \
                patch.object(f7.f3, 'sql', side_effect=['', 't']) as sql:
            f7.prepare()
        actual = sql.call_args_list[0].kwargs['input_text']
        statements = '\n'.join(line for line in actual.splitlines()
                               if not line.startswith(('\\', '--'))).strip()
        self.assertEqual('GRANT SELECT(post_id,title_hash,content_hash,analyzer_version,tokens)\n'
                         ' ON pawbridge_community.post_search_documents TO pawbridge_f2_reader;', statements)
        self.assertIn('count(*)=5 AND bool_and', sql.call_args_list[1].args[0])

    def test_given_missing_reader_column_grant__when_preparing__then_fail_instead_of_skipping(self):
        with patch.object(f7, 'require_fixture', return_value='123-1'), \
                patch.object(f7.f3, 'sql', side_effect=['', 'f']):
            with self.assertRaisesRegex(SystemExit, 'lacks the exact search reader columns'):
                f7.prepare()

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
            with patch.dict(os.environ, values, clear=True), patch.object(f7, 'ROOT', root), \
                    patch.object(f7, 'EVIDENCE', root / 'evidence'), \
                    patch.object(f7, 'require_fixture', return_value='123-1'), \
                    patch.object(f7.f4, 'start_ready_children', side_effect=AssertionError('readiness failure')), \
                    patch.object(f7, 'prepare_business_rows') as rows, patch.object(f7.f3, 'sql') as sql:
                with self.assertRaisesRegex(AssertionError, 'readiness failure'):
                    f7.smoke()
                rows.assert_not_called()
                sql.assert_not_called()


if __name__ == '__main__':
    unittest.main()
