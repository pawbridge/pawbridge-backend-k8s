"""Non-executing F6 guard tests: no database, sockets, JVM, containers or S3."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import f6_ci as f6


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

    def test_given_single_app_role__when_injecting_select_fault__then_revoke_and_restore_only_the_other_roles_overlap(self):
        calls = []
        def raw(query, input_text=None):
            calls.append(query)
            if query == "SELECT has_table_privilege('pawbridge_f3_writer','pawbridge_community.posts','SELECT')":
                return 't'
            return 'f'
        with patch.object(f6.f3, '_raw_sql', side_effect=raw), patch.dict(f6.f3._APP_OVERLAP_RESTORES, {}, clear=True):
            f6.f3.sql('REVOKE SELECT(' + f6.POST_COLUMNS + ') ON pawbridge_community.posts FROM pawbridge_f2_reader')
            f6.f3.sql('GRANT SELECT(' + f6.POST_COLUMNS + ') ON pawbridge_community.posts TO pawbridge_f2_reader')
            f6.f3.sql('REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader')
            f6.f3.sql('GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader')
            f6.f3.sql('DELETE FROM pawbridge_user.users WHERE user_id=42; REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader')
        mutations = [query for query in calls if not query.startswith('SELECT ')]
        self.assertEqual(['REVOKE SELECT(' + f6.POST_COLUMNS + ') ON pawbridge_community.posts FROM pawbridge_f2_reader',
                          'REVOKE SELECT ON pawbridge_community.posts FROM pawbridge_f3_writer',
                          'GRANT SELECT(' + f6.POST_COLUMNS + ') ON pawbridge_community.posts TO pawbridge_f2_reader',
                          'GRANT SELECT ON pawbridge_community.posts TO pawbridge_f3_writer',
                          'REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader',
                          'GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader',
                          'DELETE FROM pawbridge_user.users WHERE user_id=42; REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader'],
                         mutations)
        self.assertIn("SELECT has_column_privilege('pawbridge_f3_writer','pawbridge_user.users','nickname','SELECT')", calls)


if __name__ == '__main__':
    unittest.main()
