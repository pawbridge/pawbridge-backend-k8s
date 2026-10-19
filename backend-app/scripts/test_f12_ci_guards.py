"""Mock-only F12 target, fixture, projection, oracle and staged CI checks."""
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

import f12_ci as f12
import prepare_f12_legacy_oracle as oracle
from test_f11_ci_guards import HOSTED as F11_HOSTED

HOSTED = dict(F11_HOSTED, F12_CI_OWNER='123-1')


class F12GuardTest(unittest.TestCase):
    def test_given_invalid_f11_fixture__when_preparing__then_no_export_ddl_or_snapshot(self):
        with patch.object(f12.f11, 'require_fixture', side_effect=SystemExit('F11 strict mismatch')), \
                patch.object(f12.f3, 'sql') as sql, patch.object(f12.f2, 'export_environment') as export:
            with self.assertRaisesRegex(SystemExit, 'F11 strict mismatch'):
                f12.prepare()
            sql.assert_not_called()
            export.assert_not_called()

    def test_given_missing_second_principal_token__when_smoking__then_refuse_before_children_or_business_seed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'boot/build/libs/pawbridge-backend-app.jar'
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b'never executed')
            tokens = root / 'synthetic-tokens.json'
            tokens.write_text('{}')
            with patch.dict(os.environ, {'F3_TEST_TOKENS_PATH': str(tokens)}, clear=True), \
                    patch.object(f12, 'ROOT', root), patch.object(f12, 'require_fixture', return_value='123-1'), \
                    patch.object(f12.f4, 'start_ready_children') as start, patch.object(f12, 'prepare_business_rows') as seed:
                with self.assertRaisesRegex(SystemExit, 'real legacy issuer second-principal token'):
                    f12.smoke()
                start.assert_not_called()
                seed.assert_not_called()

    def test_given_original_owned_fixture__when_preparing__then_preserve_identities_before_five_column_grant(self):
        events, statements = [], []

        def sql(query='', input_text=None):
            if input_text is not None:
                events.append('DDL'); statements.append(input_text)
                return ''
            events.append('identities' if query == f12.IDENTITIES else 'grant')
            statements.append(query)
            return 'same-identities'

        with patch.object(f12.f11, 'require_fixture', side_effect=lambda: events.append('F11') or '123-1'), \
                patch.object(f12.f2, 'export_environment', side_effect=lambda values: events.append('owner')) as export, \
                patch.object(f12.f3, 'sql', side_effect=sql), \
                patch.object(f12, 'require_fixture', side_effect=lambda: events.append('F12') or '123-1'):
            f12.prepare()
        self.assertEqual(['F11', 'owner', 'identities', 'DDL', 'identities', 'grant', 'F12'], events)
        export.assert_called_once_with({'F12_CI_OWNER': '123-1'})
        ddl = statements[1]
        self.assertIn("VALUES ('pawbridge-f12-ci-only-v1', :'fixture_owner')", ddl)
        self.assertEqual(9, ddl.count('ADD COLUMN '))
        self.assertIn('BEGIN;', ddl)
        self.assertIn('COMMIT;', ddl)
        self.assertIn("CHECK (role IN ('ROLE_ADMIN','ROLE_SHELTER','ROLE_USER'))", ddl)
        self.assertEqual(4, ddl.count('SET NOT NULL'))
        self.assertIn('UPDATE pawbridge_user.users SET\n email=', ddl)
        for forbidden in ('CREATE ROLE', 'GRANT ', 'TRUNCATE', 'DELETE FROM', 'DROP ', 'NOT VALID',
                          'ALTER COLUMN nickname', 'ALTER COLUMN user_id', 'nickname=', 'user_id='):
            self.assertNotIn(forbidden, ddl)
        self.assertEqual('GRANT SELECT(email,name,provider,role,created_at) ON pawbridge_user.users TO pawbridge_f2_reader',
                         statements[3])

    def test_given_changed_existing_identity__when_preparing__then_refuse_before_grant_or_success(self):
        with patch.object(f12.f11, 'require_fixture', return_value='123-1'), \
                patch.object(f12.f2, 'export_environment'), \
                patch.object(f12.f3, 'sql', side_effect=['before', '', 'after']) as sql, \
                patch.object(f12, 'require_fixture') as success:
            with self.assertRaisesRegex(SystemExit, 'changed existing User IDs or nicknames'):
                f12.prepare()
            self.assertEqual(3, sql.call_count)
            self.assertFalse(any(call.args and call.args[0].startswith('GRANT ') for call in sql.call_args_list))
            success.assert_not_called()

    def test_given_missing_or_foreign_f12_owner__when_checking_fixture__then_no_profile_sql(self):
        for owner in (None, 'foreign'):
            values = {} if owner is None else {'F12_CI_OWNER': owner}
            with self.subTest(owner=owner), patch.dict(os.environ, values, clear=True), \
                    patch.object(f12.f11, 'require_fixture', return_value='123-1'), patch.object(f12.f3, 'sql') as sql:
                with self.assertRaisesRegex(SystemExit, 'F12 fixture owner mismatch'):
                    f12.require_fixture()
                sql.assert_not_called()

    def test_given_wrong_marker_shape_acl_or_role_check__when_checking_fixture__then_select_only_and_refuse(self):
        for responses, message in [(['foreign'], 'fixture identity mismatch'),
                                   ([f12.MARKER + ':123-1', 'f'], 'eleven-column User shape'),
                                   ([f12.MARKER + ':123-1', 't', 'f'], 'seven reader columns'),
                                   ([f12.MARKER + ':123-1', 't', 't', 'f'], 'exact three-role constraint')]:
            with self.subTest(message=message), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f12.f11, 'require_fixture', return_value='123-1'), \
                    patch.object(f12.f3, 'sql', side_effect=responses) as sql:
                with self.assertRaisesRegex(SystemExit, message):
                    f12.require_fixture()
                self.assertTrue(all(call.args[0].startswith('SELECT ') for call in sql.call_args_list))

    def test_given_exact_profile_fixture__when_checking__then_preserve_comment_chain_and_enforce_user_shape_acl_check(self):
        with patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f12.f11, 'require_fixture', return_value='123-1') as parent, \
                patch.object(f12.f3, 'sql', side_effect=[f12.MARKER + ':123-1', 't', 't', 't']) as sql:
            self.assertEqual('123-1', f12.require_fixture())
            parent.assert_called_once_with()
            shape, acl, check = [call.args[0] for call in sql.call_args_list[1:]]
            for name in ('user_id', 'nickname', 'email', 'name', 'provider', 'role', 'created_at', *f12.PRIVATE_COLUMNS):
                self.assertIn("'" + name + "'", shape)
            for required in ('count(*)=11', "'timestamp without time zone',0,'YES',6", "'character varying',20,'NO',0"):
                self.assertIn(required, shape)
            for required in ("column_name IN ('user_id','email','name','nickname','provider','role','created_at')",
                             "'INSERT,UPDATE,REFERENCES'", "'SELECT,INSERT,UPDATE,REFERENCES'", 'TRUNCATE,REFERENCES,TRIGGER',
                             "'pawbridge_f2_reader','pawbridge_user','CREATE'", "'pawbridge_f3_writer','pawbridge_user','CREATE'"):
                self.assertIn(required, acl)
            for required in ('convalidated', 'conkey=ARRAY', 'pg_get_expr', 'ck_f12_users_role',
                             'ROLE_ADMIN', 'ROLE_SHELTER', 'ROLE_USER'):
                self.assertIn(required, check)

    def test_given_workflow_phases__when_declaring_checks__then_run_f12_after_all_ancestor_regressions_before_cleanup(self):
        workflow = (f12.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('- name: Extend the owned fixture with exact F12 profile columns and reader permissions')
        self.assertLess(workflow.index('python3 scripts/f11_ci.py smoke'), boundary)
        self.assertLess(workflow.index('python3 scripts/f12_ci.py prepare', boundary), workflow.index(':boot:profileCompatibilityTest'))
        self.assertLess(workflow.index(':boot:profileCompatibilitySeoulTest'), workflow.index('python3 scripts/f12_ci.py results'))
        self.assertLess(workflow.index('python3 scripts/f12_ci.py results'), workflow.index('python3 scripts/f12_ci.py smoke'))
        self.assertLess(workflow.index('python3 scripts/f12_ci.py smoke'), workflow.index('- name: Remove only the owned disposable fixture'))
        self.assertNotRegex(workflow[boundary:], r'python3 scripts/f(?:[2-9]|10|11)_ci\.py (?:prepare|smoke)')
        for required in ('scripts/test_f12_ci_guards.py', 'f12-helper-refusal.txt', 'f12-oracle-sha256.txt',
                         'f12_fixture', 'f12LegacyOracle', 'backend-app/**/build/test-results/**/TEST-*.xml'):
            self.assertIn(required, workflow)

    def test_given_declared_profile_tasks__when_selecting__then_use_only_f12_and_leave_ancestor_class_filters_intact(self):
        build = (f12.ROOT / 'boot/build.gradle').read_text()
        original = build.split("['compatibilityTest':", 1)[1].split("['commentDeleteCompatibilityTest':", 1)[0]
        profile = build.split("['profileCompatibilityTest':", 1)[1].split("tasks.named('profileCompatibilitySeoulTest')", 1)[0]
        self.assertIn("filter.excludeTestsMatching '" + f12.COMPATIBILITY_CLASS + "'", original)
        self.assertIn("includeTestsMatching '" + f12.COMPATIBILITY_CLASS + "'", profile)
        self.assertEqual(1, profile.count('includeTestsMatching'))
        for required in ('failOnNoMatchingTests = true', "systemProperty 'user.timezone', zone",
                         "systemProperty 'junit.jupiter.execution.parallel.enabled', 'false'",
                         "shouldRunAfter tasks.named('commentUpdateCompatibilitySeoulTest')"):
            self.assertIn(required, profile)

    def test_given_wrong_oracle_paths__when_extracting__then_fixed_source_and_no_git_or_write(self):
        self.assertEqual('5473c4ccd95ae974c79acc7030228d4a717af3b3', oracle.SOURCE)
        self.assertEqual({oracle.PREFIX + 'com/pawbridge/userservice/dto/response/UserInfoResponseDto.java':
                          '55e9001190998829dd1d9c87c04f70ed1af88388ac73a29d9950f0241418d341'}, oracle.EXPECTED)
        root = Path('/synthetic').resolve()
        output = root / 'backend-app/boot/build/generated/sources/f12LegacyOracle/java'
        evidence = root / 'backend-app/build/ci/f12-oracle-sha256.txt'
        for target, report in ((root / 'outside', evidence), (output, root / 'outside')):
            with self.subTest(target=target, report=report), patch.object(oracle.subprocess, 'check_output') as git:
                with self.assertRaisesRegex(SystemExit, 'Refusing non-dedicated F12 oracle output'):
                    oracle.extract(root, target, report)
                git.assert_not_called()

    def test_given_tampered_shared_user_or_role__when_extracting__then_no_new_source_or_evidence(self):
        for source in oracle.SHARED:
            for failure in ('hash', 'missing', 'differing-existing'):
                with self.subTest(source=source, failure=failure), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory).resolve()
                    output = root / 'backend-app/boot/build/generated/sources/f12LegacyOracle/java'
                    evidence = root / 'backend-app/build/ci/f12-oracle-sha256.txt'
                    content = b'synthetic fixed source'
                    shared = root / 'backend-app/boot/build/generated/sources/f3LegacyOracle/java' / source[len(oracle.PREFIX):]
                    if failure != 'missing':
                        shared.parent.mkdir(parents=True)
                        shared.write_bytes(b'different' if failure == 'differing-existing' else content)
                    expected = hashlib.sha256(content).hexdigest() if failure != 'hash' else 'wrong'
                    with patch.object(oracle, 'SHARED', (source,)), patch.dict(oracle.f3.EXPECTED, {source: expected}, clear=True), \
                            patch.object(oracle.subprocess, 'check_output', return_value=content):
                        with self.assertRaisesRegex(SystemExit, 'Shared F3 User/Role differs from fixed F12 input'):
                            oracle.extract(root, output, evidence)
                    self.assertFalse(output.exists())
                    self.assertFalse(evidence.exists())

    def test_given_tampered_new_dto__when_extracting__then_no_source_or_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            output = root / 'backend-app/boot/build/generated/sources/f12LegacyOracle/java'
            evidence = root / 'backend-app/build/ci/f12-oracle-sha256.txt'
            with patch.object(oracle, 'SHARED', ()), patch.object(oracle.subprocess, 'check_output', return_value=b'tampered'):
                with self.assertRaisesRegex(SystemExit, 'Legacy F12 input checksum mismatch'):
                    oracle.extract(root, output, evidence)
            self.assertFalse(output.exists())
            self.assertFalse(evidence.exists())

    def test_given_differing_existing_dto__when_extracting__then_preserve_existing_bytes_and_no_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            output = root / 'backend-app/boot/build/generated/sources/f12LegacyOracle/java'
            evidence = root / 'backend-app/build/ci/f12-oracle-sha256.txt'
            relative, content = 'synthetic/Input.java', b'synthetic fixed source'
            target = output / relative
            target.parent.mkdir(parents=True)
            target.write_bytes(b'existing different source')
            with patch.object(oracle, 'SHARED', ()), \
                    patch.dict(oracle.EXPECTED, {oracle.PREFIX + relative: hashlib.sha256(content).hexdigest()}, clear=True), \
                    patch.object(oracle.subprocess, 'check_output', return_value=content):
                with self.assertRaisesRegex(SystemExit, 'Refusing to overwrite differing F12 oracle input'):
                    oracle.extract(root, output, evidence)
            self.assertEqual(b'existing different source', target.read_bytes())
            self.assertFalse(evidence.exists())

    def test_given_non_hosted_invocation__when_preparing_seeding_reporting_or_smoking__then_no_resource_call(self):
        for action in (f12.prepare, f12.prepare_business_rows, f12.verify_results, f12.smoke):
            with self.subTest(action=action.__name__), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f12.f3, 'sql') as sql, patch.object(f12.f2, 'command') as command, \
                    patch.object(f12.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'):
                    action()
                sql.assert_not_called()
                command.assert_not_called()
                start.assert_not_called()

    def test_given_missing_run_owner__when_preparing__then_refuse_before_sql_or_docker(self):
        with patch.dict(os.environ, dict(HOSTED, GITHUB_RUN_ID=''), clear=True), \
                patch.object(f12.f3, 'sql') as sql, patch.object(f12.f2, 'command') as command:
            with self.assertRaisesRegex(SystemExit, 'Missing CI owner identity'):
                f12.prepare()
            sql.assert_not_called()
            command.assert_not_called()

    def test_given_wrong_target_or_reader__when_preparing__then_inherit_refusal_before_docker(self):
        for changed, message in [({'F2_TEST_JDBC_URL': 'jdbc:postgresql://127.0.0.1:54321/production'},
                                  'Refusing non-loopback, non-fixture test URL'),
                                 ({'F2_TEST_JDBC_URL': 'jdbc:postgresql://db.example:5432/pawbridge_f2_ci'},
                                  'Refusing non-loopback, non-fixture test URL'),
                                 ({'F2_TEST_DB_USER': 'postgres'}, 'Unexpected fixture reader')]:
            with self.subTest(changed=changed), patch.dict(os.environ, dict(HOSTED, **changed), clear=True), \
                    patch.object(f12.f2, 'command') as command:
                with self.assertRaisesRegex(SystemExit, message):
                    f12.prepare()
                command.assert_not_called()

    def test_given_foreign_container_label__when_preparing__then_inspect_only_without_mutation(self):
        inspection = [{'Config': {'Labels': {f12.f2.OWNER: 'foreign'}}}]
        with patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f12.f2, 'command', return_value=SimpleNamespace(stdout=json.dumps(inspection))) as command, \
                patch.object(f12.f3, 'sql') as mutation:
            with self.assertRaisesRegex(SystemExit, 'Refusing an unowned CI container'):
                f12.prepare()
            self.assertEqual(1, command.call_count)
            self.assertEqual(['docker', 'inspect', HOSTED['F2_CI_CONTAINER']], command.call_args.args[0])
            mutation.assert_not_called()

    def test_given_wrong_admin_or_database_marker__when_preparing__then_no_mutation(self):
        for identity in ('production:postgres:pawbridge-f2-ci-only-v1',
                         'pawbridge_f2_ci:other:pawbridge-f2-ci-only-v1'):
            with self.subTest(identity=identity), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f12.f2, 'sql', return_value=identity) as sql, \
                    patch.object(f12.f2, 'command') as command, patch.object(f12.f3, 'sql') as mutation:
                with self.assertRaisesRegex(SystemExit, 'Fixture identity mismatch'):
                    f12.prepare()
                self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
                command.assert_not_called()
                mutation.assert_not_called()

    def test_given_foreign_token_path__when_preparing__then_ancestor_select_only_and_refusal(self):
        with patch.dict(os.environ, dict(HOSTED, F3_TEST_TOKENS_PATH='/synthetic/foreign.json'), clear=True), \
                patch.object(f12.f2, 'require_fixture'), patch.object(f12.f2, 'command') as command, \
                patch.object(f12.f3, 'sql', return_value=f12.f3.MARKER + ':123-1') as sql:
            with self.assertRaisesRegex(SystemExit, 'F3 token file must belong to this hosted run'):
                f12.prepare()
            self.assertEqual(1, sql.call_count)
            self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
            command.assert_not_called()

    def test_given_mismatched_owned_port__when_preparing__then_no_ddl_or_environment_export(self):
        for ports in (None, [{'HostIp': '127.0.0.1', 'HostPort': '54322'}],
                      [{'HostIp': '0.0.0.0', 'HostPort': '54321'}],
                      [{'HostIp': '127.0.0.1', 'HostPort': '54321'}] * 2):
            with self.subTest(ports=ports), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f12.f11.f8.f7, 'require_fixture', return_value='123-1'), \
                    patch.object(f12.f3, 'sql', return_value='t') as sql, \
                    patch.object(f12.f2, 'inspect_owned', return_value={'NetworkSettings': {'Ports': {'5432/tcp': ports}}}), \
                    patch.object(f12.f2, 'export_environment') as export:
                with self.assertRaisesRegex(SystemExit, 'JDBC target must match the owned fixture container port'):
                    f12.prepare()
                self.assertEqual(1, sql.call_count)
                self.assertTrue(sql.call_args.args[0].startswith('SELECT '))
                export.assert_not_called()

    def test_given_unowned_fixture__when_seeding__then_no_business_mutation(self):
        with patch.object(f12, 'require_fixture', side_effect=SystemExit('unowned')), patch.object(f12.f3, 'sql') as sql:
            with self.assertRaisesRegex(SystemExit, 'unowned'):
                f12.prepare_business_rows()
            sql.assert_not_called()

    def test_given_readiness_failure__when_smoking__then_no_business_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'boot/build/libs/pawbridge-backend-app.jar'
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b'never executed')
            tokens = root / 'synthetic-tokens.json'
            tokens.write_text('{"F12_OTHER":"synthetic-extra"}')
            values = {'F3_TEST_TOKENS_PATH': str(tokens), 'F2_TEST_JDBC_URL': 'unused',
                      'F2_TEST_DB_USER': 'unused', 'F2_TEST_DB_PASSWORD': 'synthetic',
                      'F3_TEST_WRITER_PASSWORD': 'synthetic', 'F3_TEST_JWT_SECRET': 'synthetic'}
            with patch.dict(os.environ, values, clear=True), patch.object(f12, 'ROOT', root), \
                    patch.object(f12, 'EVIDENCE', root / 'evidence'), \
                    patch.object(f12, 'require_fixture', return_value='123-1'), \
                    patch.object(f12.f4, 'start_ready_children', side_effect=AssertionError('readiness failure')), \
                    patch.object(f12, 'prepare_business_rows') as rows, patch.object(f12.f3, 'sql') as sql:
                with self.assertRaisesRegex(AssertionError, 'readiness failure'):
                    f12.smoke()
                rows.assert_not_called()
                sql.assert_not_called()

    @staticmethod
    def reports(root, *, tests=1, failures=0, errors=0, skipped=0, classname=None, cases=None):
        classname = classname or f12.COMPATIBILITY_CLASS
        for task in ('profileCompatibilityTest', 'profileCompatibilitySeoulTest'):
            path = root / 'boot/build/test-results' / task / ('TEST-' + f12.COMPATIBILITY_CLASS + '.xml')
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
            with patch.dict(os.environ, HOSTED, clear=True), patch.object(f12, 'ROOT', root):
                self.assertEqual({'UTC': 2, 'Asia/Seoul': 2}, f12.verify_results())

    def test_given_missing_zero_failed_skipped_or_wrong_reports__when_verifying__then_refuse_not_skip(self):
        for settings in (None, {'tests': 0}, {'failures': 1}, {'errors': 1}, {'skipped': 1},
                         {'classname': 'OtherTest'}, {'tests': 2, 'cases': 1}):
            with self.subTest(settings=settings), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                if settings is not None:
                    self.reports(root, **settings)
                with patch.dict(os.environ, HOSTED, clear=True), patch.object(f12, 'ROOT', root):
                    with self.assertRaisesRegex(SystemExit, 'F12 compatibility report'):
                        f12.verify_results()
        for task in ('profileCompatibilityTest', 'profileCompatibilitySeoulTest'):
            for failure in ('missing', 'malformed'):
                with self.subTest(task=task, failure=failure), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    self.reports(root)
                    report = root / 'boot/build/test-results' / task / ('TEST-' + f12.COMPATIBILITY_CLASS + '.xml')
                    if failure == 'missing':
                        report.unlink()
                    else:
                        report.write_text('<testsuite')
                    with patch.dict(os.environ, HOSTED, clear=True), patch.object(f12, 'ROOT', root):
                        with self.assertRaisesRegex(SystemExit, 'F12 compatibility report'):
                            f12.verify_results()

    def test_given_actual_jar_filter__when_f12_artifacts_leak__then_reject_without_widening_validator_exception(self):
        workflow = (f12.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        pipeline = re.search(r'          if (grep -E .*?); then', workflow, re.DOTALL).group(1)
        allowed = 'BOOT-INF/lib/hibernate-validator-8.0.3.Final.jar\n'
        for forbidden in ('', 'BOOT-INF/classes/f12_fixture.sql', 'BOOT-INF/classes/f12LegacyOracle/Input.class',
                          'test/f12LegacyOracle/hibernate-validator-8.0.3.Final.jar'):
            with self.subTest(forbidden=forbidden), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / 'build/ci/boot-jar-contents.txt'
                target.parent.mkdir(parents=True)
                target.write_text(allowed + forbidden + '\n')
                actual = subprocess.run(['bash', '-o', 'pipefail', '-c', pipeline], cwd=directory,
                                        capture_output=True, text=True, timeout=3)
                self.assertEqual(0 if forbidden else 1, actual.returncode, actual.stderr)


if __name__ == '__main__':
    unittest.main()
