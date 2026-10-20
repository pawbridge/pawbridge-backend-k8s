"""Observe the dev-only application selection and final gate without running the app."""
import contextlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import textwrap
import tempfile
import unittest
from unittest.mock import patch

import ci_branch_policy
import backend_contract_profile


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/backend-app-check.yml'


class BackendAppCiPolicyTest(unittest.TestCase):
    def job(self, name):
        marker = '\n  ' + name + ':\n'
        text = WORKFLOW.read_text()
        self.assertTrue(marker in text, 'Required workflow job is missing: ' + name)
        body = text.split(marker, 1)[1]
        next_job = re.search(r'^  [a-zA-Z_][\w-]*:\n', body, re.MULTILINE)
        return body[:next_job.start()] if next_job else body

    def script(self, name):
        marker = '        run: |\n'
        body = self.job(name)
        self.assertTrue(marker in body, 'Required workflow script is missing: ' + name)
        return textwrap.dedent(body.split(marker, 1)[1])

    def select(self, paths, *, pilot=False, raw=False, note=False, member=False, signup=False, admin=False, visit=False, auth=False, post=False, reads=False):
        script = self.script('selection')
        marker = 'python3 - <<\'PY\' >> "$GITHUB_OUTPUT"\n'
        self.assertIn(marker, script)
        program = script.split(marker, 1)[1].rsplit('\nPY', 1)[0]
        output = io.StringIO()
        payload = {'pull_request': {'head': {'repo': {'full_name': backend_contract_profile.REPOSITORY},
                                            'ref': backend_contract_profile.PILOT_REF if pilot else 'feat/ordinary'},
                                    'base': {'sha': backend_contract_profile.PILOT_BASE}}}
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / 'event.json'
            event.write_text(json.dumps(payload))
            env = {'GITHUB_EVENT_PATH': str(event), 'GITHUB_EVENT_NAME': 'pull_request',
                   'GITHUB_REPOSITORY': backend_contract_profile.REPOSITORY}
            with patch.dict(os.environ, env, clear=True), \
                    patch.object(sys, 'path', list(sys.path)), \
                    patch.object(ci_branch_policy, 'validate_event', return_value='pull_request'), \
                    patch.object(ci_branch_policy, 'changed_paths', return_value=paths), \
                    patch.object(backend_contract_profile, 'verify_note_expiry_inputs', return_value=note), \
                    patch.object(backend_contract_profile, 'verify_member_management_inputs', return_value=member), \
                    patch.object(backend_contract_profile, 'verify_signup_statistics_inputs', return_value=signup), \
                    patch.object(backend_contract_profile, 'verify_admin_member_update_inputs', return_value=admin), \
                    patch.object(backend_contract_profile, 'verify_visit_collection_inputs', return_value=visit), \
                    patch.object(backend_contract_profile, 'verify_auth_sessions_inputs', return_value=auth), \
                    patch.object(backend_contract_profile, 'verify_post_statistics_inputs', return_value=post), patch.object(backend_contract_profile, 'verify_post_reads_inputs', return_value=reads), \
                    contextlib.redirect_stdout(output):
                exec(compile(program, str(WORKFLOW), 'exec'), {})
        value = output.getvalue()
        return value if raw else value.splitlines()[0] + '\n'

    def gate(self, selection='success', selected='true', check='success', package=None,
             proof=None, mode=None):
        return subprocess.run(
            ['bash', '-euc', self.script('ci-result')],
            env={'PATH': os.environ.get('PATH', ''), 'SELECTION_RESULT': selection,
                 'MODULE_SELECTED': selected, 'MODULE_RESULT': check,
                 'PACKAGE_RESULT': package if package is not None else ('skipped' if selected == 'false' else 'success'),
                 'PROOF_RESULT': proof if proof is not None else ('skipped' if selected == 'false' else 'success'),
                 'PROOF_MODE': mode if mode is not None else ('' if selected == 'false' else 'execute')},
            capture_output=True, text=True, timeout=3,
        )

    def test_given_owned_pg_task__when_selecting_settings__then_keep_explicit_path_and_class_owned_guards(self):
        boot = (ROOT / 'backend-app/boot/build.gradle').read_text()
        task = boot.split("tasks.register('ownedPostgresqlTest', Test) {", 1)[1]
        guard = task.split('    doFirst {', 1)[1].split('    afterSuite {', 1)[0]
        self.assertIn("System.getenv('PAWBRIDGE_OWNED_SETTINGS_ENV')", guard)
        self.assertIn('settingsEnvironment ==~ /P[1-9][0-9]*_PG_SETTINGS/', guard)
        self.assertIn('System.getenv(settingsEnvironment) : null', guard)
        self.assertIn('settingsPath != null && !settingsPath.isBlank()', guard)
        self.assertIn('selected.isAbsolute() && selected.normalize() == selected', guard)
        self.assertIn('java.nio.file.Files.isRegularFile(selected, java.nio.file.LinkOption.NOFOLLOW_LINKS)', guard)
        self.assertIn("throw new GradleException('An explicit owned PostgreSQL settings file is required')", guard)
        self.assertIn("System.getenv('PAWBRIDGE_ANIMAL_MEMBER_PG_ENABLED') != 'owned-fixture-only'", guard)
        self.assertNotRegex(guard, r"System\.getenv\('P[1-9][0-9]*_PG_SETTINGS'\)")
        self.assertNotIn('systemProperty', guard)
        self.assertNotIn('environment(', guard)
        self.assertIn("filter { failOnNoMatchingTests = true }", task)
        self.assertIn('result.testCount == 0 || result.skippedTestCount > 0', task)
        self.assertIn('outputs.upToDateWhen { false }', task)
        for file, env in [('OwnedPostgresqlStoreOrderAdministrationTest.java', 'P13_PG_SETTINGS'),
                          ('OwnedPostgresqlAnimalTravelTest.java', 'P14_PG_SETTINGS')]:
            source = (ROOT / 'backend-app/boot/src/test/java/com/pawbridge/backend/boot/configuration' / file).read_text()
            self.assertIn('System.getenv("' + env + '")', source)
            self.assertNotIn('PAWBRIDGE_OWNED_SETTINGS_ENV', source)
            self.assertNotIn('assumeTrue', source)

    def test_given_dev_pr_or_push__when_workflow_is_registered__then_always_create_a_result(self):
        header = WORKFLOW.read_text().split('\npermissions:', 1)[0]
        self.assertIn('  push:\n    branches: [dev]', header)
        self.assertIn('  pull_request:\n    branches: [dev]', header)
        self.assertNotIn('paths:', header)
        self.assertNotIn('main', header)
        self.assertIn('name: Backend app CI result', self.job('ci-result'))
        self.assertIn('if: ${{ always() }}', self.job('ci-result'))

    def test_given_module_or_its_policy_change__when_selecting__then_require_the_application_check(self):
        for path in ('backend-app/boot/src/main/java/Example.java',
                     '.github/workflows/backend-app-check.yml',
                     '.github/scripts/test_backend_app_ci_policy.py',
                     '.github/scripts/ci_branch_policy.py',
                     '.github/scripts/backend_contract_profile.py',
                     '.github/scripts/test_backend_contract_profile.py',
                     '.github/scripts/test_backend_note_expiry_profile.py',
                     '.github/scripts/test_backend_member_management_profile.py',
                     '.github/scripts/test_backend_signup_statistics_profile.py',
                     '.github/scripts/test_backend_admin_member_update_profile.py',
                     '.github/scripts/test_backend_visit_collection_profile.py',
                     '.github/scripts/test_backend_auth_sessions_profile.py',
                  '.github/scripts/test_backend_admin_post_statistics_profile.py',
                     '.github/scripts/backend_postmerge_proof.py',
                     '.github/scripts/test_backend_postmerge_proof.py'):
            with self.subTest(path=path):
                self.assertEqual('selected=true\n', self.select([path]))

    def test_given_only_unrelated_changes__when_selecting__then_explicitly_select_no_application_check(self):
        for paths in ([], ['README.md'], ['animal-service/src/main/java/Example.java']):
            with self.subTest(paths=paths):
                self.assertEqual('selected=false\n', self.select(paths))

    def test_given_source_selection_failure__when_selecting__then_fail_without_an_empty_success_output(self):
        script = self.script('selection')
        program = script.split('python3 - <<\'PY\' >> "$GITHUB_OUTPUT"\n', 1)[1].rsplit('\nPY', 1)[0]
        for function in ('validate_event', 'changed_paths'):
            with self.subTest(function=function), \
                    patch.dict(os.environ, {}, clear=True), \
                    patch.object(sys, 'path', list(sys.path)), \
                    patch.object(ci_branch_policy, 'validate_event', return_value='pull_request'), \
                    patch.object(ci_branch_policy, 'changed_paths', return_value=[]), \
                    patch.object(ci_branch_policy, function, side_effect=ci_branch_policy.PolicyBlocked('Selection failed.')):
                output = io.StringIO()
                with contextlib.redirect_stdout(output), self.assertRaises(ci_branch_policy.PolicyBlocked):
                    exec(compile(program, str(WORKFLOW), 'exec'), {})
                self.assertEqual('', output.getvalue())

    def test_given_selected_success_or_expected_skip__when_gating__then_accept(self):
        for selected, result in (('true', 'success'), ('false', 'skipped')):
            with self.subTest(selected=selected):
                actual = self.gate(selected=selected, check=result)
                self.assertEqual(0, actual.returncode, actual.stderr)

    def test_given_failed_or_missing_selection__when_gating__then_reject_even_a_successful_check(self):
        for result in ('failure', 'cancelled', 'skipped', ''):
            with self.subTest(result=result):
                self.assertNotEqual(0, self.gate(selection=result).returncode)

    def test_given_failed_missing_or_unexpected_check__when_gating__then_reject(self):
        for selected, result in (('true', 'failure'), ('true', 'cancelled'), ('true', 'skipped'),
                                 ('true', ''), ('false', 'success'), ('false', 'failure'),
                                 ('false', 'cancelled'), ('false', ''), ('', 'skipped'), ('TRUE', 'success')):
            with self.subTest(selected=selected, result=result):
                self.assertNotEqual(0, self.gate(selected=selected, check=result).returncode)

    def test_given_workflow_dependencies__when_selecting_and_gating__then_wire_the_actual_results(self):
        selection = self.job('selection')
        self.assertIn('selected: ${{ steps.selection.outputs.selected }}', selection)
        self.assertIn('fetch-depth: 0', selection)
        self.assertIn('persist-credentials: false', selection)
        self.assertIn('PULL_REQUEST_BASE_SHA: ${{ github.event.pull_request.base.sha }}', selection)
        self.assertIn('PULL_REQUEST_BASE_REF: ${{ github.base_ref }}', selection)
        self.assertIn('PUSH_BEFORE_SHA: ${{ github.event.before }}', selection)
        check = self.job('check')
        self.assertIn('needs: [selection, proof, package]', check)
        self.assertIn("if: ${{ needs.selection.outputs.selected == 'true' && needs.proof.outputs.mode == 'execute' }}", check)
        gate = self.job('ci-result')
        self.assertIn('needs: [selection, proof, package, check]', gate)
        self.assertIn('SELECTION_RESULT: ${{ needs.selection.result }}', gate)
        self.assertIn('MODULE_SELECTED: ${{ needs.selection.outputs.selected }}', gate)
        self.assertIn('MODULE_RESULT: ${{ needs.check.result }}', gate)
        self.assertIn('PACKAGE_RESULT: ${{ needs.package.result }}', gate)
        self.assertIn('PROOF_RESULT: ${{ needs.proof.result }}', gate)
        self.assertIn('PROOF_MODE: ${{ needs.proof.outputs.mode }}', gate)

    def test_given_application_checks_are_selected__when_running__then_preserve_the_full_runtime_contract(self):
        check = self.job('check')
        self.assertIn('bash ./gradlew projects check :boot:bootJar', self.job('package'))
        for command in ('bash ./gradlew :boot:compatibilityTest :boot:compatibilitySeoulTest',
                        'python3 scripts/f2_ci.py prepare', 'python3 scripts/f3_ci.py prepare',
                        'python3 scripts/f3_ci.py smoke', 'python3 scripts/f3_ci.py cleanup'):
            with self.subTest(command=command):
                self.assertIn(command, check)
        self.assertIn('name: Verify production JAR isolation', check)
        self.assertIn('name: Preserve tests and bounded runtime evidence', check)
        self.assertIn('name: Preserve the verified executable JAR', check)
        cleanup = check.split('      - name: Remove only the owned disposable fixture\n', 1)[1]
        cleanup = cleanup.split('      - name:', 1)[0]
        self.assertIn('        if: always()', cleanup)
        self.assertNotRegex(check, r'--tests\b|--exclude-task\b|(?:^|\s)-x(?:\s|$)')

    def test_given_fixed_original_test_sources__when_packaging__then_sparse_checkout_contains_every_declared_input(self):
        build = (ROOT / 'backend-app/boot/build.gradle').read_text()
        block = build.split("tasks.named('compileTestJava', JavaCompile) {", 1)[1].split('\n}', 1)[0]
        fixed = re.findall(r"rootProject\.file\('\.\./([^']+)'\)", block)
        self.assertEqual(17, len(fixed))
        self.assertEqual(len(fixed), len(set(fixed)))
        package = self.job('package')
        sparse = re.search(r'sparse-checkout: \|\n(.*?)\n\s+sparse-checkout-cone-mode:', package, re.DOTALL).group(1)
        checked_out = {line.strip() for line in sparse.splitlines() if line.strip()}
        self.assertEqual(set(fixed), {path for path in checked_out if path.startswith('store-service')})
        for path in fixed:
            with self.subTest(path=path):
                self.assertTrue(path.startswith('store-service/src/main/java/') and path.endswith('.java'))
                self.assertIn(path, checked_out)
        self.assertIn('bash ./gradlew projects check :boot:bootJar', package)
        self.assertNotRegex(self.job('check'), r'./gradlew[^\n]*(?:\bcheck\b|:boot:test\b|:boot:ownedPostgresqlTest\b|:boot:compileTestJava\b)')

    def test_given_verification_workflow__when_running__then_do_not_inject_publication_credentials(self):
        text = WORKFLOW.read_text()
        self.assertIn('permissions:\n  contents: read', text)
        for forbidden in ('secrets.', 'secrets: inherit', 'docker/login-action',
                          'docker/build-push-action', 'pawbridge-infra-k8s', 'gh pr '):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, text)


    def test_given_fixed_pilot_ci_pr__when_selecting__then_add_only_one_bounded_comparison(self):
        path = '.github/workflows/backend-app-check.yml'
        actual = self.select([path], pilot=True, raw=True)
        self.assertEqual({'selected': 'true', 'profiles': '["full","f25-six-smoke-pilot"]'},
                         dict(line.split('=', 1) for line in actual.splitlines()))
        actual = self.select([path, 'backend-app/boot/build.gradle'], pilot=True, raw=True)
        self.assertIn('profiles=["full"]', actual)

    def test_given_failed_missing_or_unexpected_package__when_gating__then_reject_even_successful_runtime(self):
        for selected, package in (('true', 'failure'), ('true', 'cancelled'), ('true', 'skipped'),
                                  ('true', ''), ('false', 'success'), ('false', 'failure')):
            with self.subTest(selected=selected, package=package):
                self.assertNotEqual(0, self.gate(selected=selected,
                    check='skipped' if selected == 'false' else 'success', package=package).returncode)

    def test_given_profile_matrix__when_running__then_share_one_binary_without_cancelling_full(self):
        check, package = self.job('check'), self.job('package')
        self.assertIn('fail-fast: false', check)
        self.assertIn('profile: ${{ fromJSON(needs.selection.outputs.profiles) }}', check)
        self.assertIn('Java 21 build and isolated PostgreSQL/S3 HTTP contracts', check)
        self.assertEqual(1, WORKFLOW.read_text().count('bash ./gradlew projects check :boot:bootJar'))
        self.assertNotIn('continue-on-error', check + package)
        self.assertIn('jar_sha256: ${{ steps.package.outputs.jar_sha256 }}', package)
        self.assertIn('EXPECTED_CANDIDATE_JAR_SHA256: ${{ needs.package.outputs.jar_sha256 }}', check)
        self.assertEqual(3, check.count('= "$EXPECTED_CANDIDATE_JAR_SHA256"'))
        self.assertIn('digest-mismatch: error', check)
        self.assertNotIn('github-token:', check)
        self.assertIn("name: backend-app-candidate-${{ github.run_id }}-${{ github.run_attempt }}", package)
        self.assertIn("name: backend-app-candidate-${{ github.run_id }}-${{ github.run_attempt }}", check)
        self.assertIn('name: backend-app-check-${{ github.run_id }}-${{ github.run_attempt }}-${{ matrix.profile }}', check)
        evidence = package.split('      - name: Preserve shared unit and architecture evidence even on failure', 1)[1]
        self.assertIn('if: always()', evidence)
        self.assertIn('TEST-*.xml', evidence)

    def test_given_runtime_stage_conditions__when_selecting_profiles__then_execute_exact_known_jars_and_report_every_skip(self):
        check = self.job('check')
        parts = re.split(r'(?=^      - name: )', check, flags=re.MULTILINE)
        found, conditional = [], []
        modes = (backend_contract_profile.FULL, backend_contract_profile.PILOT,
                 backend_contract_profile.NOTE_EXPIRY, backend_contract_profile.MEMBER_MANAGEMENT,
                 backend_contract_profile.SIGNUP_STATISTICS, backend_contract_profile.ADMIN_MEMBER_UPDATE,
                 backend_contract_profile.VISIT_COLLECTION, backend_contract_profile.AUTH_SESSIONS, backend_contract_profile.POST_STATISTICS, backend_contract_profile.POST_READS)
        selected = {mode: [] for mode in modes}
        wanted_conditions = {
            "${{ matrix.profile == 'full' }}": {backend_contract_profile.FULL},
            "${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != 'signup-statistics' && matrix.profile != 'admin-member-update' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile != 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}":
                {backend_contract_profile.FULL, backend_contract_profile.PILOT},
            "${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' && matrix.profile != 'admin-member-update' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile != 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}":
                {backend_contract_profile.FULL, backend_contract_profile.PILOT, backend_contract_profile.MEMBER_MANAGEMENT},
        }
        allowed_conditions = {'Extend the owned fixture with only the comment table and reader columns','Extend the owned fixture with minimal F9 comment INSERT permissions','Extend the owned fixture with exact F10 comment deletion permissions','Extend the owned fixture with exact F11 comment content update permission','Extend the owned fixture with exact F12 profile columns and reader permissions','Extend the owned fixture with pinned User constraints and exact F13 nickname permissions','Extend the owned fixture with exact F14 favorites schema and reader-only columns','Extend the owned fixture with pinned F15 board and videos and reader-only columns','Extend the owned fixture with only pinned F16 daily totals and reader columns','Extend the owned fixture with only F17 pending and block tables and three reader columns','Verify the unchanged F17 fixture for F18 without new schema or grants','Reuse the unchanged F18 and F17 fixture for F19 without schema or grants','Add only the existing block timestamp SELECT after the F19 regressions','Extend the owned fixture with only original notes and mailboxes after F20','Extend only five existing reader columns after the full F21 stage','Reuse the exact F22 fixture for F23 without new DDL or privileges','Extend only the existing member lock and outgoing block writer privileges for F24','Extend only the outgoing block INSERT columns for F25','Extend only the owned note read and lock writer columns after the complete F25 stage','Extend only mailbox favorite UPDATE after the complete F26 stage','Add only the original requests table and two DELETE grants after the complete F27 stage','Add only pinned report context and exact send columns after the complete F28 stage',
                              'Verify administrator post statistics oracle guards without resources',
                              'Compile pinned administrator post statistics source without database tests',
                              'Compile pinned User Community cohort source without database tests',
                              'Remove only the owned disposable fixture',
                              'Reject missing, failed or falsely skipped runtime contracts',
                              'Preserve tests and bounded runtime evidence',
                              'Preserve the verified executable JAR',
                              'Verify late member cleanup runtime controls without resources',
                              'Add pinned User and installed chat tables only after fixed source preparation',
                              'Compile pinned administrator update and member source closure without database tests',
                              'Compile pinned visit collection and member source closure without database tests',
                              'Add visit collection tables and exact writer rights on the member-only fixture',
                              'Add visit collection tables after full administrator PostgreSQL verification',
                              'Compile pinned refresh logout and member source closure without database tests',
                              'Add only refresh logout column rights on the exact administrator fixture',
                              'Always restore only auth column rights and verify the prior administrator fixture',
                              'Add only administrator role and care registration column rights after member fixture preparation',
                              'Verify owned note expiry, original limits, rollback and active scheduler shutdown in UTC'}
        for part in parts[1:]:
            name = part.splitlines()[0].removeprefix('      - name: ')
            smoke = re.findall(r'^\s*(?:run: )?python3 scripts/(f[0-9]+)_ci.py smoke\s*$', part, re.MULTILINE)
            member = re.findall(r'^\s*(?:run: )?python3 scripts/f29_ci.py member-cleanup-smoke\s*$', part, re.MULTILINE)
            if smoke or member:
                self.assertEqual(1, len(smoke) + len(member))
                key = 'jar_f31' if member else 'jar_' + smoke[0]
                found.append(key)
                self.assertIn('        id: ' + key + '\n', part)
                conditions = re.findall(r'^        if: (.+)$', part, re.MULTILINE)
                self.assertEqual(1, len(conditions), 'Every JAR must declare its selected coverage.')
                self.assertIn(conditions[0], wanted_conditions)
                if conditions[0] == "${{ matrix.profile == 'full' }}":
                    conditional.append(key)
                for mode in wanted_conditions[conditions[0]]:
                    selected[mode].append(key)
            elif '        if:' in part and name not in allowed_conditions:
                self.assertTrue(name.startswith('Compare ') or name.startswith('Require exact F')
                                or name == 'Prepare only the owned F4 dependent table shape for scoped expiry')
        self.assertEqual(list(backend_contract_profile.ALL_SMOKES), found)
        self.assertEqual(list(backend_contract_profile.PILOT_SKIPS), conditional)
        for mode, actual in selected.items():
            self.assertEqual(list(backend_contract_profile.expected_smokes(mode)), actual)
        audit = check.split('      - name: Reject missing, failed or falsely skipped runtime contracts', 1)[1]
        audit = audit.split('      - name:', 1)[0]
        self.assertIn('if: always()', audit)
        self.assertIn('CONTRACT_STEPS_JSON: ${{ toJSON(steps) }}', audit)
        self.assertIn('--mode audit --profile "$CONTRACT_PROFILE"', audit)
        self.assertLess(check.index('id: owned_cleanup'), check.index('--mode audit'))
        for path in ('.github/scripts/backend_contract_profile.py', '.github/scripts/ci_branch_policy.py'):
            self.assertIn('            ' + path + '\n', check)




    def test_given_verified_reuse__when_gating__then_accept_only_two_expected_skips(self):
        valid = dict(selected='true', proof='success', mode='reused-full',
                     package='skipped', check='skipped')
        actual = self.gate(**valid)
        self.assertEqual(0, actual.returncode, actual.stderr)
        for key, values in {
            'proof': ('failure', 'cancelled', 'skipped', ''),
            'mode': ('', 'reuse', 'full'),
            'package': ('success', 'failure', 'cancelled', ''),
            'check': ('success', 'failure', 'cancelled', ''),
        }.items():
            for value in values:
                with self.subTest(key=key, value=value):
                    self.assertNotEqual(0, self.gate(**dict(valid, **{key: value})).returncode)

    def test_given_verified_scoped_reuse__when_gating__then_accept_only_verified_reuse_and_both_expected_skips(self):
        valid = dict(selected='true', proof='success', mode='reused-scoped', package='skipped', check='skipped')
        self.assertEqual(0, self.gate(**valid).returncode)
        for key, values in {'proof': ('failure', 'cancelled', 'skipped', ''),
                            'mode': ('', 'scoped', 'reuse'),
                            'package': ('success', 'failure', 'cancelled', ''),
                            'check': ('success', 'failure', 'cancelled', '')}.items():
            for value in values:
                with self.subTest(key=key, value=value):
                    self.assertNotEqual(0, self.gate(**dict(valid, **{key: value})).returncode)

    def test_given_scoped_expiry__when_wiring__then_keep_preparation_pg_and_cleanup_with_explicit_noncoverage(self):
        check = self.job('check')
        parts = re.split(r'(?=^      - name: )', check, flags=re.MULTILINE)[1:]
        for part in parts:
            prepared = re.search(r'run: python3 scripts/f([0-9]+)_ci.py prepare\n', part)
            if prepared:
                if 8 <= int(prepared.group(1)) <= 29:
                    self.assertIn("if: ${{ matrix.profile != 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}", part)
                    self.assertEqual(1, part.count('        if:'))
                else:
                    self.assertNotIn('        if:', part)
        shape = next(part for part in parts if 'id: fixture_f4_shape\n' in part)
        self.assertIn("if: ${{ matrix.profile == 'note-expiry' || matrix.profile == 'member-management' || matrix.profile == 'signup-statistics' || matrix.profile == 'admin-member-update' || matrix.profile == 'visit-collection' || matrix.profile == 'auth-sessions' || matrix.profile == 'admin-post-statistics' || matrix.profile == 'user-community-integration' }}", shape)
        self.assertIn('python3 scripts/f4_ci.py prepare-shape', shape)
        self.assertLess(check.index('id: jar_f3\n'), check.index('id: fixture_f4_shape\n'))
        self.assertLess(check.index('id: fixture_f4_shape\n'), check.index('id: jar_f4\n'))
        pg = next(part for part in parts if 'id: pg_note_expiry\n' in part)
        self.assertIn("if: ${{ matrix.profile != 'signup-statistics' && matrix.profile != 'admin-member-update' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile != 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}", pg)
        self.assertIn(':boot:noteExpiryCompatibilityTest', pg)
        self.assertIn('verify_note_expiry_report', pg)
        self.assertIn('note_expiry_scope_verified', self.job('selection'))
        self.assertIn('verify_note_expiry_inputs()', self.job('selection'))

    def test_given_verified_member_scope__when_wiring__then_keep_expiry_before_fixture_pg_and_the_nine_request_runtime(self):
        actual = self.select(['.github/workflows/backend-app-check.yml'], member=True, raw=True)
        self.assertEqual({'selected': 'true', 'profiles': '["member-management"]'},
                         dict(line.split('=', 1) for line in actual.splitlines()))
        check = self.job('check')
        self.assertLess(check.index('id: pg_note_expiry'), check.index('id: fixture_member_cleanup'))
        self.assertLess(check.index('id: fixture_member_cleanup'), check.index('id: pg_member_cleanup'))
        self.assertLess(check.index('id: pg_member_cleanup'), check.index('id: jar_f31'))
        self.assertLess(check.index('id: jar_f31'), check.index('id: owned_cleanup'))
        parts = re.split(r'(?=^      - name: )', check, flags=re.MULTILINE)[1:]
        for key, command in (
                ('fixture_member_cleanup', 'python3 scripts/f29_ci.py member-cleanup-prepare'),
                ('pg_member_cleanup', ':boot:memberCleanupCompatibilityTest'),
                ('jar_f31', 'python3 scripts/f29_ci.py member-cleanup-smoke')):
            step = next(part for part in parts if 'id: ' + key + '\n' in part)
            condition = ("if: ${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' && matrix.profile != 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}"
                         if key == 'fixture_member_cleanup' else
                         "if: ${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' && matrix.profile != 'admin-member-update' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile != 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}")
            self.assertIn(condition, step)
            self.assertIn(command, step)
        pg = next(part for part in parts if 'id: pg_member_cleanup\n' in part)
        self.assertIn('f29_ci.member_require_runtime_results()', pg)
        self.assertIn('member_management_scope_verified', self.job('selection'))
        self.assertIn('verify_member_management_inputs()', self.job('selection'))
        self.assertIn("result['request_checks'] == result['delete_checks'] + result['get_checks'] == 9", check)
        self.assertIn("result['child_log_evidence'] == 'safe-copied'", check)

    def test_given_verified_signup_scope__when_wiring__then_keep_exact_two_zone_pg_after_f19_and_skip_unrelated_late_contracts(self):
        actual = self.select(['.github/workflows/backend-app-check.yml'], signup=True, raw=True)
        self.assertEqual({'selected': 'true', 'profiles': '["signup-statistics"]'},
                         dict(line.split('=', 1) for line in actual.splitlines()))
        check = self.job('check')
        parts = re.split(r'(?=^      - name: )', check, flags=re.MULTILINE)[1:]
        pg = next(part for part in parts if 'id: pg_signup_statistics\n' in part)
        self.assertLess(check.index('python3 scripts/f19_ci.py prepare'), check.index('id: pg_signup_statistics'))
        self.assertLess(check.index('id: pg_signup_statistics'), check.index('      - name: Add only the existing block timestamp SELECT after the F19 regressions'))
        self.assertIn("if: ${{ matrix.profile != 'note-expiry' && matrix.profile != 'member-management' && matrix.profile != 'admin-member-update' && matrix.profile != 'visit-collection' && matrix.profile != 'auth-sessions' && matrix.profile != 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}", pg)
        self.assertIn(':boot:signupStatisticsCompatibilityTest :boot:signupStatisticsCompatibilitySeoulTest', pg)
        self.assertIn('test -s build/ci/f19-oracle-sha256.txt', pg)
        self.assertIn('test -s build/ci/f19-signup-statistics-oracle-sha256.txt', pg)
        self.assertIn('verify_signup_statistics_report(', pg)
        self.assertIn("Path('boot/build/test-results/signupStatisticsCompatibilityTest')", pg)
        self.assertIn("Path('boot/build/test-results/signupStatisticsCompatibilitySeoulTest')", pg)
        for key in ('pg_note_expiry', 'fixture_member_cleanup', 'pg_member_cleanup', 'jar_f31'):
            part = next(part for part in parts if 'id: ' + key + '\n' in part)
            self.assertIn("matrix.profile != 'signup-statistics'", part)
        for part in parts:
            prepared = re.search(r'run: python3 scripts/f([0-9]+)_ci.py prepare\n', part)
            if prepared:
                if 8 <= int(prepared.group(1)) <= 29:
                    self.assertIn("if: ${{ matrix.profile != 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}", part)
                    self.assertEqual(1, part.count('        if:'))
                else:
                    self.assertNotIn('        if:', part)
        self.assertIn('signup_statistics_scope_verified', self.job('selection'))
        self.assertIn('verify_signup_statistics_inputs()', self.job('selection'))
        self.assertIn(':boot:bootJar', self.job('package'))

    def test_given_verified_admin_update_scope__when_wiring__then_compile_before_member_shape_and_run_only_the_exact_new_pg(self):
        actual = self.select(['.github/workflows/backend-app-check.yml'], admin=True, raw=True)
        self.assertEqual({'selected': 'true', 'profiles': '["admin-member-update"]'},
                         dict(line.split('=', 1) for line in actual.splitlines()))
        check = self.job('check')
        parts = re.split(r'(?=^      - name: )', check, flags=re.MULTILINE)[1:]
        keys = ('compile_admin_update', 'fixture_member_cleanup', 'fixture_admin_update', 'pg_admin_update', 'owned_cleanup')
        for first, second in zip(keys, keys[1:]):
            self.assertLess(check.index('id: ' + first + '\n'), check.index('id: ' + second + '\n'))
        compile_step = next(p for p in parts if 'id: compile_admin_update\n' in p)
        self.assertIn("if: ${{ matrix.profile == 'admin-member-update' }}", compile_step)
        self.assertIn(':boot:compileCompatibilityTestJava', compile_step)
        self.assertNotIn(':boot:memberCleanupCompatibilityTest', compile_step)
        member = next(p for p in parts if 'id: fixture_member_cleanup\n' in p)
        self.assertIn("if: ${{ matrix.profile != 'note-expiry' && matrix.profile != 'signup-statistics' && matrix.profile != 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}", member)
        fixture = next(p for p in parts if 'id: fixture_admin_update\n' in p)
        self.assertIn('python3 scripts/f29_ci.py admin-update-prepare', fixture)
        pg = next(p for p in parts if 'id: pg_admin_update\n' in p)
        self.assertIn(':boot:adminUserUpdateCompatibilityTest', pg)
        self.assertIn('verify_admin_member_update_report(', pg)
        self.assertIn('test -s build/ci/f13-admin-update-oracle-sha256.txt', pg)
        self.assertIn('test -s build/ci/admin-user-update-fixture-write.txt', pg)
        for part in parts:
            if 'bash ./gradlew' in part and 'CompatibilityTest' in part and part is not pg and part is not compile_step:
                self.assertTrue("matrix.profile != 'admin-member-update'" in part or "matrix.profile == 'full'" in part or "matrix.profile == 'visit-collection'" in part,
                                'An unrelated PostgreSQL task must not execute in the administrator lane.')
        self.assertLess(check.index('id: jar_f31\n'), check.index('id: fixture_admin_update\n'))
        self.assertIn('verify_admin_member_update_inputs()', self.job('selection'))
        self.assertIn('admin_member_update_scope_verified', self.job('selection'))

    def test_given_unknown_or_failed_proof__when_gating__then_do_not_accept_full_success(self):
        for proof, mode in (('failure', 'full'), ('cancelled', 'full'),
                            ('skipped', 'full'), ('', 'full'),
                            ('success', ''), ('success', 'reused-full'),
                            ('success', 'FULL')):
            with self.subTest(proof=proof, mode=mode):
                self.assertNotEqual(0, self.gate(proof=proof, mode=mode).returncode)
        for proof, mode in (('success', ''), ('skipped', 'full'), ('skipped', 'reused-full')):
            with self.subTest(unselected=True, proof=proof, mode=mode):
                self.assertNotEqual(0, self.gate(selected='false', package='skipped',
                    check='skipped', proof=proof, mode=mode).returncode)

    def test_given_proof_stage__when_wiring__then_decide_before_any_new_package_with_read_only_permissions(self):
        text = WORKFLOW.read_text()
        proof, package, check = self.job('proof'), self.job('package'), self.job('check')
        self.assertLess(text.index('  proof:\n'), text.index('  package:\n'))
        self.assertIn('needs: [selection, proof]', package)
        for stage in (package, check):
            self.assertIn("if: ${{ needs.selection.outputs.selected == 'true' && needs.proof.outputs.mode == 'execute' }}", stage)
        self.assertIn('permissions:\n      contents: read\n      actions: read\n      pull-requests: read', proof)
        self.assertIn('persist-credentials: false', proof)
        self.assertIn('fetch-depth: 0', proof)
        self.assertIn('GH_TOKEN: ${{ github.token }}', proof)
        self.assertIn('PULL_REQUEST_BASE_SHA: ${{ github.event.pull_request.base.sha }}', proof)
        self.assertIn('PULL_REQUEST_BASE_REF: ${{ github.base_ref }}', proof)
        self.assertIn('PUSH_BEFORE_SHA: ${{ github.event.before }}', proof)
        self.assertIn('mode: ${{ steps.proof.outputs.mode }}', proof)
        self.assertIn("python3 -B -m unittest discover -s .github/scripts -p 'test_backend_postmerge_proof.py'", proof)
        self.assertIn('backend_postmerge_proof.py --output build/ci/postmerge-proof.txt >> "$GITHUB_OUTPUT"', proof)
        self.assertIn('name: backend-app-postmerge-proof-${{ github.run_id }}-${{ github.run_attempt }}', proof)
        self.assertIn('path: build/ci/postmerge-proof.txt', proof)
        self.assertIn('if-no-files-found: error', proof)
        self.assertIn('if: always()', proof)
        for forbidden in ('setup-java@', 'gradlew', 'docker ', 'secrets.', ': write', 'continue-on-error'):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, proof)
        for stage in (self.job('selection'), package, check, self.job('ci-result')):
            self.assertNotIn('GH_TOKEN:', stage)
            self.assertNotIn('actions: read', stage)
            self.assertNotIn('pull-requests: read', stage)

    def test_given_original_runtime_evidence__when_preserving__then_bind_actual_workflow_identity(self):
        for name in ('package', 'check'):
            with self.subTest(job=name):
                stage = self.job(name)
                self.assertIn('printf \'workflow_sha=%s\\n\' "$GITHUB_WORKFLOW_SHA"', stage)
                self.assertIn('printf \'workflow_ref=%s\\n\' "$GITHUB_WORKFLOW_REF"', stage)


if __name__ == '__main__':
    unittest.main()
