"""No-socket F13 ownership, staged ACL, pinned DDL, XML and token regressions."""
import base64
import inspect
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import f13_ci as f13
import prepare_f13_legacy_oracle as oracle
from test_f12_ci_guards import HOSTED as F12_HOSTED

HOSTED = dict(F12_HOSTED, F13_CI_OWNER='123-1')


class F13GuardTest(unittest.TestCase):
    def test_given_non_hosted_invocation__when_calling_any_action__then_refuse_before_resources(self):
        for action in (f13.prepare, f13.require_fixture, f13.prepare_business_rows, f13.smoke,
                       f13.verify_results, f13.verified_ddl):
            with self.subTest(action=action.__name__), patch.dict(os.environ, {}, clear=True), \
                    patch.object(f13.f3, 'sql') as sql, patch.object(f13.f2, 'command') as command, \
                    patch.object(f13.subprocess, 'check_output') as git, patch.object(f13.f4, 'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit, 'restricted to this repository on GitHub-hosted Linux'):
                    action()
                sql.assert_not_called(); command.assert_not_called(); git.assert_not_called(); start.assert_not_called()

    def test_given_invalid_original_f12_acl__when_preparing__then_no_ddl_export_or_resource_call(self):
        with patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f13.f12, 'require_fixture', side_effect=SystemExit('F12 strict mismatch')), \
                patch.object(f13, 'verified_ddl') as ddl, patch.object(f13.f3, 'sql') as sql, \
                patch.object(f13.f2, 'export_environment') as export:
            with self.assertRaisesRegex(SystemExit, 'F12 strict mismatch'):
                f13.prepare()
            ddl.assert_not_called(); sql.assert_not_called(); export.assert_not_called()

    def test_given_owned_original_fixture__when_preparing__then_compare_fixed_ddl_and_preserve_identities(self):
        events = []
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f13, 'EVIDENCE', Path(directory)), \
                patch.object(f13.f12, 'require_fixture', side_effect=lambda: events.append('F12') or '123-1'), \
                patch.object(f13, 'verified_ddl', side_effect=lambda: events.append('source') or ('DDL', 'hashes')), \
                patch.object(f13.f2, 'export_environment', side_effect=lambda value: events.append('owner')) as export, \
                patch.object(f13.f3, 'sql', side_effect=lambda query='', input_text=None: events.append('DDL' if input_text else 'identities') or 'same'), \
                patch.object(f13, 'require_fixture', side_effect=lambda: events.append('F13') or '123-1'):
            f13.prepare()
            self.assertEqual(['F12', 'source', 'owner', 'identities', 'DDL', 'identities', 'F13'], events)
            self.assertEqual('hashes', (Path(directory) / 'f13-ddl-sha256.txt').read_text())
            export.assert_called_once_with({'F13_CI_OWNER': '123-1'})

    def test_given_changed_existing_identity__when_preparing__then_do_not_report_success(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f13.f12, 'require_fixture', return_value='123-1'), \
                patch.object(f13, 'verified_ddl', return_value=('DDL', 'hashes')), patch.object(f13.f2, 'export_environment'), \
                patch.object(f13.f3, 'sql', side_effect=['before', '', 'after']), patch.object(f13, 'require_fixture') as success:
            with self.assertRaisesRegex(SystemExit, 'changed existing User IDs or nicknames'):
                f13.prepare()
            success.assert_not_called()

    def test_given_pinned_v1_v2_sources__when_comparing_fixture__then_exact_eight_column_check_and_uniqueness_match(self):
        # Local Git object reads only; no sockets, containers or DB access.
        with patch.dict(os.environ, HOSTED, clear=True):
            ddl, evidence = f13.verified_ddl()
        self.assertIn('source_sha=' + f13.SOURCE, evidence)
        self.assertEqual(8, ddl.count('COLLATE "C" !~'))
        self.assertIn('ADD CONSTRAINT users_nickname_key UNIQUE (nickname)', ddl)
        self.assertIn('uk_users_nickname_folded ON pawbridge_user.users (lower(nickname))', ddl)
        self.assertIn('GRANT SELECT(user_id,nickname),UPDATE(nickname,updated_at)', ddl)
        self.assertIn('GRANT USAGE ON SCHEMA pawbridge_user', ddl)
        self.assertIn('charset_definition', ddl)
        for forbidden in ('UPDATE pawbridge_user.users', 'INSERT INTO pawbridge_user.users', 'DELETE FROM',
                          'TRUNCATE', 'DROP ', 'CREATE ROLE', 'NOT VALID', 'GRANT SELECT ON', 'GRANT UPDATE ON', 'SEQUENCE'):
            self.assertNotIn(forbidden, ddl)

    def test_given_tampered_original_migration__when_comparing__then_refuse_before_fixture_read(self):
        with patch.dict(os.environ, HOSTED, clear=True), \
                patch.object(f13.subprocess, 'check_output', return_value=b'tampered'), patch.object(Path, 'read_text') as read:
            with self.assertRaisesRegex(SystemExit, 'original migration checksum mismatch'):
                f13.verified_ddl()
            read.assert_not_called()

    def test_given_tampered_fixture_charset__when_comparing_pinned_source__then_refuse(self):
        real = (f13.ROOT / 'scripts/f13_fixture.sql').read_text()
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True):
            root = Path(directory); (root / 'scripts').mkdir()
            (root / 'scripts/f13_fixture.sql').write_text(real.replace('AND password COLLATE', 'AND name COLLATE'))
            original = f13.subprocess.check_output
            repository = f13.ROOT.parent
            def git(args, **kwargs):
                return original(args, cwd=repository)
            with patch.object(f13, 'ROOT', root), patch.object(f13.subprocess, 'check_output', side_effect=git):
                with self.assertRaisesRegex(SystemExit, 'differs from pinned User uniqueness or eight-column charset DDL'):
                    f13.verified_ddl()

    def test_given_foreign_f12_or_f13_owner__when_checking__then_no_mutation(self):
        for stage in (12, 13):
            with self.subTest(stage=stage), patch.dict(os.environ, dict(HOSTED, **{'F' + str(stage) + '_CI_OWNER': 'foreign'}), clear=True), \
                    patch.object(f13.f11, 'require_fixture', return_value='123-1'), \
                    patch.object(f13.f3, 'sql', return_value=f13.f12.MARKER + ':123-1') as sql:
                with self.assertRaisesRegex(SystemExit, 'fixture owner mismatch'):
                    f13.require_fixture()
                self.assertTrue(all(call.args[0].startswith('SELECT ') for call in sql.call_args_list))

    def test_given_wrong_marker_shape_acl_role_or_constraint__when_checking__then_select_only_and_refuse(self):
        valid = [f13.f12.MARKER + ':123-1', f13.MARKER + ':123-1', 't', 't', 't', 't']
        for index in range(len(valid)):
            values = valid.copy(); values[index] = 'foreign' if index < 2 else 'f'
            with self.subTest(index=index), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f13.f11, 'require_fixture', return_value='123-1'), patch.object(f13.f3, 'sql', side_effect=values) as sql:
                with self.assertRaises(SystemExit):
                    f13.require_fixture()
                self.assertTrue(all(call.args[0].startswith('SELECT ') for call in sql.call_args_list))

    def test_given_exact_extended_fixture__when_checking__then_inherit_target_chain_and_exact_column_grants(self):
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f13.f11, 'require_fixture', return_value='123-1') as parent, \
                patch.object(f13.f12, 'require_fixture', side_effect=AssertionError('F12 must stay strict')), \
                patch.object(f13.f3, 'sql', side_effect=[f13.f12.MARKER + ':123-1', f13.MARKER + ':123-1', 't', 't', 't', 't']) as sql:
            self.assertEqual('123-1', f13.require_fixture()); parent.assert_called_once_with()
            acl = sql.call_args_list[3].args[0]
            for expected in ("column_name IN ('user_id','nickname')", "column_name IN ('nickname','updated_at')",
                             "column_name IN ('user_id','email','name','nickname','provider','role','created_at')",
                             'has_sequence_privilege', 'TRUNCATE,REFERENCES,TRIGGER', "'pawbridge_user','USAGE'", "'pawbridge_user','CREATE'"):
                self.assertIn(expected, acl)
            constraints = sql.call_args_list[-1].args[0]
            for expected in ('convalidated', "contype='u'", 'conkey=ARRAY', 'i.indisunique', 'i.indisvalid',
                             'i.indisready', 'i.indpred IS NULL', 'cardinality(conkey)=8', 'charset_definition'):
                self.assertIn(expected, constraints)

    def test_given_wrong_target_or_reader__when_preparing__then_no_command(self):
        for changed in ({'F2_TEST_JDBC_URL': 'jdbc:postgresql://production:5432/pawbridge_f2_ci'},
                        {'F2_TEST_DB_USER': 'postgres'}):
            with self.subTest(changed=changed), patch.dict(os.environ, dict(HOSTED, **changed), clear=True), \
                    patch.object(f13.f2, 'command') as command:
                with self.assertRaises(SystemExit):
                    f13.prepare()
                command.assert_not_called()

    def test_given_extended_phase__when_seeding_and_smoking__then_never_reuse_f12_strict_helpers_or_rename_original_user(self):
        for function in (f13.require_fixture, f13.prepare_business_rows, f13.smoke):
            source = inspect.getsource(function)
            for forbidden in ('f12.require_fixture(', 'f12.prepare_business_rows(', 'f12.smoke('):
                self.assertNotIn(forbidden, source)
        with patch.object(f13, 'require_fixture', return_value='123-1'), patch.object(f13.f3, 'sql', side_effect=['1', '']) as sql:
            f13.prepare_business_rows()
        setup = sql.call_args_list[-1].args[0]
        self.assertNotIn('UPDATE', setup); self.assertNotIn('ON CONFLICT', setup)
        self.assertIn(str(f13.OTHER), setup); self.assertIn(str(f13.POST), setup)

    def test_given_expired_or_missing_tokens__when_loading__then_refuse_before_listener_start(self):
        required = {'ROLE_USER', 'ROLE_ADMIN', 'ROLE_SHELTER', 'F13_OTHER', 'expired', 'forged', 'refresh',
                    'missingId', 'zeroId', 'negativeId', 'stringId', 'missingRole', 'numericRole'}
        def token(expiry):
            encoded = base64.urlsafe_b64encode(json.dumps({'exp': expiry}).encode()).decode().rstrip('=')
            return 'test.' + encoded + '.signature'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'tokens.json'
            with patch.dict(os.environ, {'F3_TEST_TOKENS_PATH': str(path)}, clear=True):
                for content in ({}, {key: token(time.time() - 1) for key in required}):
                    path.write_text(json.dumps(content)); path.chmod(0o600)
                    with self.assertRaises(SystemExit): f13.load_tokens()
                content = {key: token(time.time() + 600) for key in required}
                path.write_text(json.dumps(content)); path.chmod(0o600)
                self.assertEqual(content, f13.load_tokens())
                path.chmod(0o644)
                with self.assertRaisesRegex(SystemExit, '0600'): f13.load_tokens()

    def test_given_missing_failed_skipped_empty_or_wrong_xml__when_reporting__then_refuse_each_invalid_zone(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, HOSTED, clear=True), patch.object(f13, 'ROOT', Path(directory)):
            for task in ('nicknameCompatibilityTest', 'nicknameCompatibilitySeoulTest'):
                target = Path(directory) / 'boot/build/test-results' / task / ('TEST-' + f13.COMPATIBILITY_CLASS + '.xml')
                target.parent.mkdir(parents=True)
                valid = '<testsuite name="' + f13.COMPATIBILITY_CLASS + '" tests="1" failures="0" errors="0" skipped="0"><testcase classname="' + f13.COMPATIBILITY_CLASS + '" name="contract"/></testsuite>'
                for content in ('broken', valid.replace('tests="1"', 'tests="0"'), valid.replace('failures="0"', 'failures="1"'),
                                valid.replace('skipped="0"', 'skipped="1"'), valid.replace('classname="' + f13.COMPATIBILITY_CLASS, 'classname="wrong')):
                    target.write_text(content)
                    with self.assertRaises(SystemExit): f13.verify_results()
                target.write_text(valid)
            self.assertEqual({'UTC': 1, 'Asia/Seoul': 1}, f13.verify_results())

    def test_given_declared_workflow__when_running_f13__then_all_original_regressions_finish_before_extension_and_cleanup_is_always(self):
        workflow = (f13.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('- name: Extend the owned fixture with pinned User constraints and exact F13 nickname permissions')
        self.assertLess(workflow.index('python3 scripts/f12_ci.py smoke'), boundary)
        self.assertLess(workflow.index('python3 scripts/f13_ci.py prepare', boundary), workflow.index(':boot:nicknameCompatibilityTest'))
        self.assertLess(workflow.index(':boot:nicknameCompatibilitySeoulTest'), workflow.index('python3 scripts/f13_ci.py results'))
        self.assertLess(workflow.index('python3 scripts/f13_ci.py results'), workflow.index('python3 scripts/f13_ci.py smoke'))
        cleanup = workflow.index('- name: Remove only the owned disposable fixture')
        self.assertLess(workflow.index('python3 scripts/f13_ci.py smoke'), cleanup)
        self.assertIn('if: always()', workflow[cleanup:cleanup + 150])
        self.assertIn('python3 scripts/f3_ci.py cleanup', workflow[cleanup:cleanup + 150])
        self.assertNotRegex(workflow[boundary:], r'python3 scripts/f(?:[2-9]|10|11|12)_ci\.py (?:prepare|smoke)')
        for expected in ('test_f13_ci_guards.py', 'f13-helper-refusal.txt', 'f13-oracle-sha256.txt', 'f13-ddl-sha256.txt',
                         'f13_fixture', 'f13LegacyOracle', 'backend-app/**/build/test-results/**/TEST-*.xml'):
            self.assertIn(expected, workflow)
        build = (f13.ROOT / 'boot/build.gradle').read_text()
        ancestor = build.split("['compatibilityTest':", 1)[1].split("['commentDeleteCompatibilityTest':", 1)[0]
        self.assertIn("filter.excludeTestsMatching '" + f13.COMPATIBILITY_CLASS + "'", ancestor)
        nickname = build.split("['nicknameCompatibilityTest':", 1)[1]
        for expected in ("includeTestsMatching '" + f13.COMPATIBILITY_CLASS + "'", 'failOnNoMatchingTests = true',
                         "systemProperty 'user.timezone', zone", "shouldRunAfter tasks.named('profileCompatibilitySeoulTest')"):
            self.assertIn(expected, nickname)


    def test_given_wrong_oracle_paths__when_extracting__then_refuse_before_git_or_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            output = root / 'backend-app/boot/build/generated/sources/f13LegacyOracle/java'
            evidence = root / 'backend-app/build/ci/f13-oracle-sha256.txt'
            for target, report in ((root / 'outside', evidence), (output, root / 'outside')):
                with self.subTest(target=target, report=report), patch.object(oracle.subprocess, 'check_output') as git, \
                        patch.object(Path, 'mkdir') as mkdir, patch.object(Path, 'write_bytes') as write, \
                        patch.object(Path, 'write_text') as report_write:
                    with self.assertRaisesRegex(SystemExit, 'Refusing non-dedicated F13 oracle output'):
                        oracle.extract(root, target, report)
                    git.assert_not_called(); mkdir.assert_not_called(); write.assert_not_called(); report_write.assert_not_called()

    def test_given_tampered_shared_source_or_generated_bytes__when_extracting__then_preserve_files_and_write_nothing(self):
        source = oracle.SHARED[0]
        for failure in ('hash', 'missing', 'differing-existing'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                output = root / 'backend-app/boot/build/generated/sources/f13LegacyOracle/java'
                evidence = root / 'backend-app/build/ci/f13-oracle-sha256.txt'
                shared = root / 'backend-app/boot/build/generated/sources/f3LegacyOracle/java' / source[len(oracle.PREFIX):]
                content = b'synthetic pinned shared bytes'
                existing = b'preserve these different generated bytes' if failure == 'differing-existing' else content
                if failure != 'missing':
                    shared.parent.mkdir(parents=True); shared.write_bytes(existing)
                digest = 'wrong' if failure == 'hash' else hashlib.sha256(content).hexdigest()
                with patch.object(oracle, 'SHARED', (source,)), patch.dict(oracle.f3.EXPECTED, {source: digest}, clear=True), \
                        patch.object(oracle.subprocess, 'check_output', return_value=content):
                    with self.assertRaisesRegex(SystemExit, 'Shared F3 source differs from fixed F13 input'):
                        oracle.extract(root, output, evidence)
                self.assertFalse(output.exists()); self.assertFalse(evidence.exists())
                if failure != 'missing': self.assertEqual(existing, shared.read_bytes())

    def test_given_tampered_new_dto_or_service_hash__when_extracting__then_write_no_generated_source_or_evidence(self):
        dto = oracle.PREFIX + 'com/pawbridge/userservice/dto/request/UpdateNicknameRequestDto.java'
        for source in (dto, oracle.SERVICE):
            with self.subTest(source=source), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                output = root / 'backend-app/boot/build/generated/sources/f13LegacyOracle/java'
                evidence = root / 'backend-app/build/ci/f13-oracle-sha256.txt'
                with patch.object(oracle, 'SHARED', ()), patch.dict(oracle.EXPECTED, {source: oracle.EXPECTED[source]}, clear=True), \
                        patch.object(oracle.subprocess, 'check_output', return_value=b'tampered input'), \
                        patch.object(oracle, 'service_wrapper') as wrapper:
                    with self.assertRaisesRegex(SystemExit, 'Legacy F13 input checksum mismatch'):
                        oracle.extract(root, output, evidence)
                    wrapper.assert_not_called()
                self.assertFalse(output.exists()); self.assertFalse(evidence.exists())

    def test_given_later_differing_existing_oracle_output__when_extracting__then_preserve_it_and_do_not_write_earlier_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            output = root / 'backend-app/boot/build/generated/sources/f13LegacyOracle/java'
            evidence = root / 'backend-app/build/ci/f13-oracle-sha256.txt'
            first, second = oracle.PREFIX + 'synthetic/First.java', oracle.PREFIX + 'synthetic/Second.java'
            content = b'synthetic fixed source'
            target = output / 'synthetic/Second.java'; target.parent.mkdir(parents=True)
            target.write_bytes(b'preserve existing source')
            expected = {first: hashlib.sha256(content).hexdigest(), second: hashlib.sha256(content).hexdigest()}
            with patch.object(oracle, 'SHARED', ()), patch.dict(oracle.EXPECTED, expected, clear=True), \
                    patch.object(oracle.subprocess, 'check_output', return_value=content):
                with self.assertRaisesRegex(SystemExit, 'Refusing to overwrite differing F13 oracle input'):
                    oracle.extract(root, output, evidence)
            self.assertEqual(b'preserve existing source', target.read_bytes())
            self.assertFalse((output / 'synthetic/First.java').exists()); self.assertFalse(evidence.exists())

    def test_given_actual_pinned_service_method__when_wrapping__then_preserve_annotations_and_inner_catch_bytes_verbatim(self):
        # Immutable local Git objects only; no sockets, DB, generated files or JVM.
        raw = oracle.subprocess.check_output(['git', 'show', oracle.SOURCE + ':' + oracle.SERVICE], cwd=f13.ROOT.parent)
        self.assertEqual(oracle.EXPECTED[oracle.SERVICE], hashlib.sha256(raw).hexdigest())
        signature = b'    @Override\n    @Transactional\n    public void updateNickname('
        start = raw.index(signature)
        end = raw.index(b'\n    @Override', start + len(signature))
        original = raw[start:end].rstrip() + b'\n'
        wrapper, digest = oracle.service_wrapper(raw)
        self.assertEqual(hashlib.sha256(original).hexdigest(), digest)
        self.assertEqual(1, wrapper.count(original))
        self.assertIn(b'catch (DataIntegrityViolationException e)', original)
        self.assertIn(b'userRepository.save(user)', original)
        self.assertLess(original.index(b'userRepository.save(user)'), original.index(b'catch (DataIntegrityViolationException e)'))
        self.assertNotIn(b'TransactionTemplate', wrapper)
        self.assertNotIn(b'userRepository.flush', wrapper)
        self.assertNotIn(b'saveAndFlush', wrapper)

    def test_given_valid_minimal_oracle_inputs__when_extracting__then_write_only_dedicated_source_and_hash_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            output = root / 'backend-app/boot/build/generated/sources/f13LegacyOracle/java'
            evidence = root / 'backend-app/build/ci/f13-oracle-sha256.txt'
            source, content = oracle.PREFIX + 'synthetic/Input.java', b'synthetic pinned source'
            digest = hashlib.sha256(content).hexdigest()
            with patch.object(oracle, 'SHARED', ()), patch.dict(oracle.EXPECTED, {source: digest}, clear=True), \
                    patch.object(oracle.subprocess, 'check_output', return_value=content):
                oracle.extract(root, output, evidence)
            self.assertEqual(content, (output / 'synthetic/Input.java').read_bytes())
            self.assertEqual('source_sha=' + oracle.SOURCE + '\n' + digest + '  ' + source + '\n', evidence.read_text())
            self.assertEqual({output / 'synthetic/Input.java', evidence}, {path for path in root.rglob('*') if path.is_file()})


    def test_given_mixed_relation_catalog__when_checking_sequence_acl__then_case_guards_the_sequence_only_function(self):
        # Structural regression only: actual PostgreSQL planner behavior is verified by hosted CI.
        with patch.dict(os.environ, HOSTED, clear=True), patch.object(f13.f11, 'require_fixture', return_value='123-1'), \
                patch.object(f13.f3, 'sql', side_effect=[f13.f12.MARKER + ':123-1', f13.MARKER + ':123-1', 't', 't', 't', 't']) as sql:
            f13.require_fixture()
        acl = sql.call_args_list[3].args[0]
        self.assertIn("CASE WHEN s.relkind='S' THEN has_sequence_privilege('pawbridge_f3_writer',s.oid,'USAGE,SELECT,UPDATE') ELSE false END", acl)
        self.assertNotIn("s.relkind='S' AND has_sequence_privilege", acl)

    def test_given_catalog_sql_failure__when_recording__then_preserve_only_bounded_masked_primary_error_without_command_or_details(self):
        for primary in ('ERROR: relation "users" is not a sequence', 'FATAL: fixture connection refused', None):
            with self.subTest(primary=primary), tempfile.TemporaryDirectory() as directory, \
                    patch.dict(os.environ, {'F2_TEST_ADMIN_PASSWORD': 'private-password', 'F3_TEST_JWT_SECRET': 'private-secret'}, clear=True), \
                    patch.object(f13, 'EVIDENCE', Path(directory)):
                stderr = None if primary is None else (primary + ' private-password private-secret \x1b[31m' + 'x' * 800
                         + '\nLINE 1: private-sql\nDETAIL: private-detail\nHINT: private-hint\nCONTEXT: private-context')
                failure = f13.subprocess.CalledProcessError(1, ['private-command', 'private-sql'],
                                                          output='private-stdout', stderr=stderr)
                with patch.object(f13.f3, 'sql', side_effect=failure):
                    with self.assertRaisesRegex(SystemExit, 'bounded f13-fixture-acl-error.txt') as raised:
                        f13.fixture_acl('SELECT static catalog metadata')
                evidence = (Path(directory) / 'f13-fixture-acl-error.txt').read_text()
                self.assertLessEqual(len(evidence), 600)
                self.assertIn('stage=f13-user-catalog-acl\nreturncode=1\nstderr=', evidence)
                for forbidden in ('private-password', 'private-secret', 'private-command', 'private-sql', 'private-stdout',
                                  'private-detail', 'private-hint', 'private-context', '\x1b', 'LINE 1:', 'DETAIL:', 'HINT:', 'CONTEXT:'):
                    self.assertNotIn(forbidden, evidence)
                    self.assertNotIn(forbidden, str(raised.exception))
                if primary is None:
                    self.assertIn('(no PostgreSQL primary error captured)', evidence)
                else:
                    self.assertIn(primary, evidence); self.assertIn('[redacted]', evidence)


class AdminUserUpdateGuardTest(unittest.TestCase):
    """New control/ownership branches only; no resource, Git command or original-business simulation."""

    def test_given_non_dedicated_admin_output__when_extracting__then_refuse_before_any_git_or_write(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(oracle.subprocess, 'check_output') as git, \
                patch.object(Path, 'write_bytes') as write:
            root = Path(directory).resolve()
            with self.assertRaisesRegex(SystemExit, 'non-dedicated'):
                oracle.extract_admin_update(root, root / 'other', root / 'receipt')
            git.assert_not_called(); write.assert_not_called()

    def test_given_a_wrong_admin_source_hash__when_extracting__then_refuse_before_any_output(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(oracle.subprocess, 'check_output', return_value=b'changed original'), \
                patch.object(Path, 'write_bytes') as write:
            root = Path(directory).resolve()
            with self.assertRaisesRegex(SystemExit, 'immutable input'):
                oracle.extract_admin_update(root, root / 'backend-app/boot/build/generated/sources/f13LegacyOracle/java',
                                            root / 'backend-app/build/ci/f13-admin-update-oracle-sha256.txt')
            write.assert_not_called()

    def test_given_a_conflicting_receipt_after_valid_controlled_inputs__when_extracting__then_preserve_every_existing_file_before_write(self):
        # Controlled byte inputs test write ordering, not actual legacy business/checksum equivalence.
        dto, shelter = oracle.PREFIX + oracle.ADMIN_OUTPUTS[0], oracle.PREFIX + oracle.ADMIN_OUTPUTS[1]
        contents = {dto: b'dto', shelter: b'shelter', oracle.SERVICE: b'service'}
        hashes = {p: hashlib.sha256(raw).hexdigest() for p, raw in contents.items()}
        generated = dict(zip(oracle.ADMIN_OUTPUTS, (b'dto', b'shelter', b'wrapper')))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); output = root / 'backend-app/boot/build/generated/sources/f13LegacyOracle/java'
            output.mkdir(parents=True); keep = output / 'Unrelated.java'; keep.write_bytes(b'keep')
            evidence = root / 'backend-app/build/ci/f13-admin-update-oracle-sha256.txt'; evidence.parent.mkdir(parents=True)
            evidence.write_bytes(b'old receipt')
            with patch.object(oracle, 'ADMIN_EXPECTED', hashes), patch.object(oracle, 'ADMIN_SHARED', {}), \
                    patch.object(oracle, 'ADMIN_GENERATED', {p: hashlib.sha256(raw).hexdigest() for p, raw in generated.items()}), \
                    patch.object(oracle, 'admin_update_wrapper', return_value=b'wrapper'), \
                    patch.object(oracle.subprocess, 'check_output', side_effect=lambda args, **kwargs: contents[args[-1].split(':', 1)[1]]), \
                    patch.object(Path, 'write_bytes', side_effect=AssertionError('no writes before conflict rejection')):
                with self.assertRaisesRegex(SystemExit, 'oracle receipt'):
                    oracle.extract_admin_update(root, output, evidence)
            self.assertEqual(b'keep', keep.read_bytes()); self.assertEqual(b'old receipt', evidence.read_bytes())
            self.assertEqual([keep], list(output.iterdir()))

    def test_given_missing_or_tampered_admin_artifacts__when_preparing__then_no_grant_or_business_sql(self):
        import f29_ci as late
        # Exercise the actual late artifact checker with controlled hashes, never a fake successful checker.
        for defect in ('missing-shared', 'changed-generated', 'changed-receipt'):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as directory:
                repository = Path(directory).resolve(); root = repository / 'backend-app'; evidence = root / 'build/ci'
                evidence.mkdir(parents=True); shared = root / 'shared.java'; shared.write_bytes(b'shared')
                full = {'shared-source.java': hashlib.sha256(b'shared').hexdigest()}
                raw = dict(zip(oracle.ADMIN_OUTPUTS, (b'dto', b'shelter', b'wrapper')))
                for relative, content in raw.items():
                    target = root / 'boot/build/generated/sources/f13LegacyOracle/java' / relative
                    target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(content)
                with patch.object(oracle, 'ADMIN_EXPECTED', full), \
                        patch.object(oracle, 'ADMIN_SHARED', {'shared-source.java': 'backend-app/shared.java'}), \
                        patch.object(oracle, 'ADMIN_GENERATED', {p: hashlib.sha256(content).hexdigest() for p, content in raw.items()}), \
                        patch.object(late, 'ROOT', root), patch.object(late, 'EVIDENCE', evidence), \
                        patch.object(late.f2, 'require_hosted'), patch.object(late, 'member_require_fixture', return_value='123-1'), \
                        patch.object(late, 'owned_sql') as sql, patch.object(late, 'member_database_snapshot') as snapshot:
                    receipt = evidence / 'f13-admin-update-oracle-sha256.txt'; receipt.write_text(oracle.admin_update_receipt(raw))
                    if defect == 'missing-shared': shared.unlink()
                    elif defect == 'changed-generated': (root / 'boot/build/generated/sources/f13LegacyOracle/java' / oracle.ADMIN_OUTPUTS[-1]).write_bytes(b'changed')
                    else: receipt.write_text(receipt.read_text() + 'unexpected\n')
                    with self.assertRaisesRegex(SystemExit, 'receipt or source artifacts differ'): late.admin_update_prepare()
                    sql.assert_not_called(); snapshot.assert_not_called()

    def test_given_original_or_admin_stage__when_checking_user_acl__then_old_strict_remains_exact_and_new_rights_are_only_four(self):
        import f29_ci as late
        with patch.object(late, '_member_require_fixture_without_user_writer_acl', return_value='123-1'), \
                patch.object(late, '_member_require_user_writer_acl') as acl:
            self.assertEqual('123-1', late.member_require_fixture())
            acl.assert_called_once_with(('user_id','nickname'), ('user_id','nickname','updated_at'))
        with patch.object(late, 'admin_update_require_identity', return_value='123-1'), \
                patch.object(late, '_member_require_fixture_without_user_writer_acl'), \
                patch.object(late, '_member_require_user_writer_acl') as acl:
            self.assertEqual('123-1', late.admin_update_require_fixture())
            acl.assert_called_once_with(('user_id','nickname','role','care_reg_no'), ('user_id','nickname','updated_at','role','care_reg_no'))
        self.assertEqual('GRANT SELECT(role,care_reg_no),UPDATE(role,care_reg_no) ON pawbridge_user.users TO pawbridge_f3_writer;', late.ADMIN_UPDATE_GRANTS)
        with patch.object(late, 'owned_sql', side_effect=['f']) as sql:
            with self.assertRaisesRegex(SystemExit, 'exact User writer columns'):
                late._member_require_user_writer_acl(('user_id','nickname'), ('user_id','nickname','updated_at'))
            self.assertIn('count(*)=11', sql.call_args.args[0])

    def test_given_a_denied_column_or_changed_owner__when_finalizing__then_restore_only_the_same_owner_and_never_reenter_old_strict(self):
        import f29_ci as late
        for owner in ('123-1', 'other-owner'):
            with self.subTest(owner=owner), patch.object(late, 'admin_update_require_fixture', return_value='123-1') as strict, \
                    patch.object(late, 'admin_update_require_identity', return_value=owner), \
                    patch.object(late, 'member_require_fixture', side_effect=AssertionError('old strict after grant')), \
                    patch.object(late, 'owned_sql') as sql:
                if owner == '123-1':
                    with self.assertRaisesRegex(RuntimeError, 'controlled failure'):
                        with late.admin_update_denied_privilege('role-write'): raise RuntimeError('controlled failure')
                    self.assertEqual(2, strict.call_count)
                    self.assertEqual(['REVOKE UPDATE(role) ON pawbridge_user.users FROM pawbridge_f3_writer',
                                      'GRANT UPDATE(role) ON pawbridge_user.users TO pawbridge_f3_writer'], [c.args[0] for c in sql.call_args_list])
                else:
                    with self.assertRaisesRegex(SystemExit, 'owner changed'):
                        with late.admin_update_denied_privilege('role-write'): pass
                    self.assertEqual(1, sql.call_count); self.assertEqual(1, strict.call_count)


if __name__ == '__main__':
    unittest.main()
