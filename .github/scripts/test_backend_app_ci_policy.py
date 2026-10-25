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

    def select(self, paths, *, pilot=False, raw=False):
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
                    contextlib.redirect_stdout(output):
                exec(compile(program, str(WORKFLOW), 'exec'), {})
        value = output.getvalue()
        return value if raw else value.splitlines()[0] + '\n'

    def gate(self, selection='success', selected='true', check='success', package=None,
             proof=None, mode=None, event='push'):
        return subprocess.run(
            ['bash', '-euc', self.script('ci-result')],
            env={'PATH': os.environ.get('PATH', ''), 'EVENT_NAME': event, 'SELECTION_RESULT': selection,
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
        self.assertIn("def legacyClass = 'com.pawbridge.backend.OwnedPostgresqlAnimalMemberReadTest'", guard)
        self.assertIn('filter.getCommandLineIncludePatterns()', guard)
        self.assertIn('commandLineIncludes.size() == 1 && filter.includePatterns.isEmpty()', guard)
        self.assertIn('filter.excludePatterns.isEmpty() && commandLineIncludes.every', guard)
        self.assertIn("name == legacyClass || (name.startsWith(legacyClass + '.')", guard)
        self.assertIn('name.substring(legacyClass.length() + 1) ==~ /[A-Za-z_$][A-Za-z0-9_$]*/', guard)
        self.assertIn("System.getenv('PAWBRIDGE_ANIMAL_MEMBER_PG_ENABLED') == 'owned-fixture-only' && legacySelection", guard)
        self.assertIn('if (!legacyFixture)', guard)
        self.assertNotIn("PAWBRIDGE_ANIMAL_MEMBER_PG_ENABLED') !=", guard)
        self.assertNotRegex(guard, r"System\.getenv\('P[1-9][0-9]*_PG_SETTINGS'\)")
        self.assertNotIn('systemProperty', guard)
        self.assertNotIn('environment(', guard)
        self.assertIn("filter { failOnNoMatchingTests = true }", task)
        self.assertIn('result.testCount == 0 || result.skippedTestCount > 0', task)
        self.assertIn('outputs.upToDateWhen { false }', task)
        for file, env in [('OwnedPostgresqlStoreOrderAdministrationTest.kt', 'P13_PG_SETTINGS'),
                          ('OwnedPostgresqlAnimalTravelTest.kt', 'P14_PG_SETTINGS')]:
            source = (ROOT / 'backend-app/boot/src/test/kotlin/com/pawbridge/backend/boot/configuration' / file).read_text()
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

    def test_given_pull_request__when_gating__then_require_package_and_the_intentional_contract_skip(self):
        actual = self.gate(event='pull_request', check='skipped')
        self.assertEqual(0, actual.returncode, actual.stderr)
        self.assertEqual(0, self.gate(event='pull_request', selected='false', check='skipped').returncode)
        for package in ('failure', 'cancelled', 'skipped', ''):
            with self.subTest(package=package):
                self.assertNotEqual(0, self.gate(event='pull_request', check='skipped', package=package).returncode)
        for check in ('success', 'failure', 'cancelled', ''):
            with self.subTest(check=check):
                self.assertNotEqual(0, self.gate(event='pull_request', check=check).returncode)
        for key, value in (('selection', 'failure'), ('proof', 'failure'), ('proof', 'cancelled'), ('mode', '')):
            with self.subTest(key=key, value=value):
                self.assertNotEqual(0, self.gate(event='pull_request', check='skipped', **{key: value}).returncode)

    def test_given_dev_push__when_gating__then_still_require_the_executed_postgresql_contracts(self):
        for check in ('skipped', 'failure', 'cancelled', ''):
            with self.subTest(check=check):
                self.assertNotEqual(0, self.gate(event='push', check=check).returncode)

    def test_given_pull_request_without_contracts__when_packaging__then_keep_the_same_jar_isolation_guard(self):
        def isolation(job, name):
            step = job.split('      - name: ' + name + '\n', 1)[1].split('\n      - name:', 1)[0]
            return re.search(r"if grep -E '[^\n]+\n[^\n]+", step).group(0).replace('package-jar-contents', 'boot-jar-contents')
        package = self.job('package')
        self.assertNotIn('if:', package.split('      - name: Verify production JAR isolation before sharing the candidate\n', 1)[1].split('\n      - name:', 1)[0])
        self.assertEqual(isolation(self.job('check'), 'Verify production JAR isolation'),
                         isolation(package, 'Verify production JAR isolation before sharing the candidate'))
        self.assertLess(package.index('Verify production JAR isolation before sharing the candidate'),
                        package.index("Share only this run's exact candidate JAR"))

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
        self.assertIn("if: ${{ github.event_name != 'pull_request' && needs.selection.outputs.selected == 'true' && needs.proof.outputs.mode == 'execute' }}", check)
        gate = self.job('ci-result')
        self.assertIn('needs: [selection, proof, package, check]', gate)
        self.assertIn('EVENT_NAME: ${{ github.event_name }}', gate)
        self.assertIn('SELECTION_RESULT: ${{ needs.selection.result }}', gate)
        self.assertIn('MODULE_SELECTED: ${{ needs.selection.outputs.selected }}', gate)
        self.assertIn('MODULE_RESULT: ${{ needs.check.result }}', gate)
        self.assertIn('PACKAGE_RESULT: ${{ needs.package.result }}', gate)
        self.assertIn('PROOF_RESULT: ${{ needs.proof.result }}', gate)
        self.assertIn('PROOF_MODE: ${{ needs.proof.outputs.mode }}', gate)

    def test_given_application_checks_are_selected__when_running__then_preserve_the_full_runtime_contract(self):
        check = self.job('check')
        self.assertIn('bash ./gradlew projects check :boot:bootJar', self.job('package'))
        for command in ('bash ./gradlew :boot:authSessionCompatibilityTest :boot:authSessionCompatibilitySeoulTest',
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

    def test_given_removed_old_service_sources__when_packaging__then_compile_and_check_out_only_the_application(self):
        build = (ROOT / 'backend-app/boot/build.gradle').read_text()
        self.assertNotIn("tasks.named('compileTestJava', JavaCompile)", build)
        self.assertNotIn('../store-service/', build)
        self.assertNotIn('LegacyOracle', build)
        package = self.job('package')
        sparse = re.search(r'sparse-checkout: \|\n(.*?)\n\s+sparse-checkout-cone-mode:', package, re.DOTALL).group(1)
        checked_out = {line.strip() for line in sparse.splitlines() if line.strip()}
        self.assertEqual({'backend-app', '.github/workflows/backend-app-check.yml',
                          '.github/scripts/backend_contract_profile.py'}, checked_out)
        self.assertIn('bash ./gradlew projects check :boot:bootJar', package)
        self.assertIn('bash ./gradlew :boot:compileCompatibilityTestJava', package)
        self.assertNotIn('-x :boot:architectureTest', package)
        self.assertNotIn('verify_architecture_selftest_inputs', package)
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
        shards = sorted(backend_contract_profile.SHARDS)
        self.assertIn('        shard: [' + ', '.join(str(shard) for shard in shards) + ']\n', check)
        self.assertIn('(shard ${{ matrix.shard }}/' + str(len(shards)) + ')', check)
        self.assertIn('CONTRACT_SHARD: ${{ matrix.shard }}', check)
        self.assertEqual(1, WORKFLOW.read_text().count('bash ./gradlew projects check :boot:bootJar'))
        self.assertNotIn('continue-on-error', check + package)
        self.assertIn('jar_sha256: ${{ steps.package.outputs.jar_sha256 }}', package)
        self.assertIn('EXPECTED_CANDIDATE_JAR_SHA256: ${{ needs.package.outputs.jar_sha256 }}', check)
        self.assertEqual(3, check.count('= "$EXPECTED_CANDIDATE_JAR_SHA256"'))
        self.assertIn('digest-mismatch: error', check)
        self.assertNotIn('github-token:', check)
        self.assertIn("name: backend-app-candidate-${{ github.run_id }}-${{ github.run_attempt }}", package)
        self.assertIn("name: backend-app-candidate-${{ github.run_id }}-${{ github.run_attempt }}", check)
        self.assertIn('name: backend-app-check-${{ github.run_id }}-${{ github.run_attempt }}-${{ matrix.profile }}'
                      '-shard-${{ matrix.shard }}\n', check)
        evidence = package.split('      - name: Preserve shared unit and architecture evidence even on failure', 1)[1]
        self.assertIn('if: always()', evidence)
        self.assertIn('TEST-*.xml', evidence)

    def test_given_runtime_stage_conditions__when_selecting_profiles__then_execute_exact_known_jars_and_report_every_skip(self):
        check = self.job('check')
        parts = re.split(r'(?=^      - name: )', check, flags=re.MULTILINE)
        found, conditional = [], []
        modes = (backend_contract_profile.FULL, backend_contract_profile.PILOT)
        shards = backend_contract_profile.SHARDS
        last = str(backend_contract_profile.CONTRACT_SHARD)
        selected = {(mode, shard): [] for mode in modes for shard in shards}
        smoke_conditions, token_conditions = {}, {}
        reproduced = []
        full_only = "matrix.profile == 'full'"

        def shard_of(key):
            return next(shard for shard, keys in shards.items() if key in keys)

        def runs_from(shard):
            # A shard re-provisions every fixture stage up to its last smoke, so a stage's
            # preparation runs in its own shard and in every later one.
            return [] if shard == 1 else ['${{ matrix.shard >= ' + str(shard) + ' }}']

        allowed_conditions = {
            'Add pinned User and installed chat tables only after fixed source preparation':
                '${{ matrix.shard >= ' + last + ' }}',
            'Add only administrator role and care registration column rights after member fixture preparation':
                '${{ matrix.shard >= ' + last + ' }}',
            'Add only refresh logout column rights on the exact administrator fixture':
                '${{ ' + full_only + ' && matrix.shard >= ' + last + ' }}',
            'Compare refresh logout transactions and one Seoul expiry storage boundary':
                '${{ ' + full_only + ' && matrix.shard >= ' + last + ' }}',
            'Always restore only auth column rights and verify the prior administrator fixture':
                "${{ always() && matrix.profile == 'full' && (steps.fixture_auth_sessions.outcome == 'success' "
                "|| steps.fixture_auth_sessions.outcome == 'failure') }}",
            'Remove only the owned disposable fixture': 'always()',
            'Reject missing, failed or falsely skipped runtime contracts': 'always()',
            'Preserve tests and bounded runtime evidence': 'always()',
            'Preserve the verified executable JAR':
                "${{ success() && matrix.profile == 'full' && matrix.shard == " + last + " }}",
        }
        for part in parts[1:]:
            name = part.splitlines()[0].removeprefix('      - name: ')
            conditions = re.findall(r'^        if: (.+)$', part, re.MULTILINE)
            smoke = re.findall(r'^\s*(?:run: )?python3 scripts/(f[0-9]+)_ci.py smoke\s*$', part, re.MULTILINE)
            member = re.findall(r'^\s*(?:run: )?python3 scripts/f29_ci.py member-cleanup-smoke\s*$', part, re.MULTILINE)
            prepare = re.search(r'^        id: fixture_post_unrelated_f([0-9]+)$', part, re.MULTILINE)
            state = re.search(r'^        id: (' + '|'.join(backend_contract_profile.STATE_STEPS.values()) + ')$',
                              part, re.MULTILINE)
            token = re.fullmatch(r'Issue synthetic JWTs for (F[0-9]+|member cleanup)', name)
            if smoke or member:
                self.assertEqual(1, len(smoke) + len(member))
                key = 'jar_f31' if member else 'jar_' + smoke[0]
                found.append(key)
                self.assertIn('        id: ' + key + '\n', part)
                shard = shard_of(key)
                every_profile = '${{ matrix.shard == ' + str(shard) + ' }}'
                pilot_skip = '${{ ' + full_only + ' && matrix.shard == ' + str(shard) + ' }}'
                self.assertIn(conditions, ([every_profile], [pilot_skip]),
                              'Every JAR runs once in its own shard and only declared pilot skips.')
                if conditions == [pilot_skip]:
                    conditional.append(key)
                for mode in modes if conditions == [every_profile] else (backend_contract_profile.FULL,):
                    selected[(mode, shard)].append(key)
                smoke_conditions[key] = conditions
            elif token:
                # A token step precedes its smoke and runs exactly when that smoke runs.
                stage = token.group(1)
                key = 'jar_f31' if stage == 'member cleanup' else 'jar_' + stage.lower()
                token_conditions[key] = conditions
            elif prepare:
                key = 'jar_f' + prepare.group(1)
                self.assertEqual(runs_from(shard_of(key)), conditions, name)
            elif state:
                # A smoke that leaves state for later steps is followed directly by the step that
                # reproduces that state in every shard after the one that runs the smoke.
                key = next(smoke for smoke, step in backend_contract_profile.STATE_STEPS.items()
                           if step == state.group(1))
                self.assertEqual(key, found[-1], name)
                self.assertEqual(['${{ matrix.shard >= ' + str(shard_of(key) + 1) + ' }}'], conditions, name)
                reproduced.append(key)
            elif conditions:
                self.assertEqual([allowed_conditions.get(name)], conditions, name)
        self.assertEqual(list(backend_contract_profile.ALL_SMOKES), found)
        self.assertEqual(list(backend_contract_profile.PILOT_SKIPS), conditional)
        self.assertEqual(sorted(backend_contract_profile.STATE_STEPS), sorted(reproduced))
        shape = next(part for part in parts if '        id: shape_f4\n' in part)
        self.assertIn('run: python3 scripts/f4_ci.py prepare-shape\n', shape)
        for (mode, shard), actual in selected.items():
            self.assertEqual(list(backend_contract_profile.expected_smokes(mode, shard)), actual)
        self.assertEqual(['jar_f' + str(n) for n in (12, 13, 14) + tuple(range(17, 30))] + ['jar_f31'],
                         sorted(token_conditions, key=backend_contract_profile.ALL_SMOKES.index))
        for key, conditions in token_conditions.items():
            self.assertEqual(smoke_conditions[key], conditions, key)
        base_token = check.split('      - name: Issue synthetic JWTs for the base F3 role and rejection set\n', 1)[1]
        self.assertNotIn('        if:', base_token.split('      - name:', 1)[0])
        audit = check.split('      - name: Reject missing, failed or falsely skipped runtime contracts', 1)[1]
        audit = audit.split('      - name:', 1)[0]
        self.assertIn('if: always()', audit)
        self.assertIn('CONTRACT_STEPS_JSON: ${{ toJSON(steps) }}', audit)
        self.assertIn('--mode audit --profile "$CONTRACT_PROFILE"', audit)
        self.assertIn('--shard "$CONTRACT_SHARD"', audit)
        plan = check.split('      - name: Bind the execution plan to this profile and candidate', 1)[1]
        plan = plan.split('      - name:', 1)[0]
        self.assertIn('--mode plan --profile "$CONTRACT_PROFILE"', plan)
        self.assertIn('--shard "$CONTRACT_SHARD"', plan)
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
            'mode': ('', 'reuse', 'full', 'reused-scoped'),
            'package': ('success', 'failure', 'cancelled', ''),
            'check': ('success', 'failure', 'cancelled', ''),
        }.items():
            for value in values:
                with self.subTest(key=key, value=value):
                    self.assertNotEqual(0, self.gate(**dict(valid, **{key: value})).returncode)

    def test_given_late_member_and_auth_fixtures__when_wiring__then_keep_order_contracts_and_always_restore(self):
        check = self.job('check')
        keys = ('jar_f29', 'fixture_member_cleanup', 'jar_f31', 'fixture_admin_update',
                'fixture_auth_sessions', 'pg_auth_sessions', 'restore_auth_sessions', 'owned_cleanup')
        for first, second in zip(keys, keys[1:]):
            self.assertLess(check.index('id: ' + first + '\n'), check.index('id: ' + second + '\n'))
        parts = re.split(r'(?=^      - name: )', check, flags=re.MULTILINE)[1:]
        for key, command in (
                ('fixture_member_cleanup', 'python3 scripts/f29_ci.py member-cleanup-prepare'),
                ('jar_f31', 'python3 scripts/f29_ci.py member-cleanup-smoke'),
                ('fixture_admin_update', 'python3 scripts/f29_ci.py admin-update-prepare'),
                ('fixture_auth_sessions', 'python3 scripts/f29_ci.py auth-sessions-prepare'),
                ('pg_auth_sessions', ':boot:authSessionCompatibilityTest :boot:authSessionCompatibilitySeoulTest'),
                ('restore_auth_sessions', 'python3 scripts/f29_ci.py auth-sessions-restore')):
            step = next(part for part in parts if 'id: ' + key + '\n' in part)
            self.assertIn(command, step)
        pg = next(part for part in parts if 'id: pg_auth_sessions\n' in part)
        self.assertIn('verify_auth_sessions_report(', pg)
        self.assertIn('test -s build/ci/auth-sessions-fixture-write.txt', pg)
        self.assertIn("result['request_checks'] == result['delete_checks'] + result['get_checks'] == 9", check)
        self.assertIn("result['child_log_evidence'] == 'safe-copied'", check)
        self.assertEqual(list(backend_contract_profile.FULL_CONTRACTS),
                         ['fixture_auth_sessions', 'pg_auth_sessions', 'restore_auth_sessions'])
        self.assertNotRegex(check, r'oracle-sha256|LegacyOracle|results\n')

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
        self.assertIn("if: ${{ needs.selection.outputs.selected == 'true' && needs.proof.outputs.mode == 'execute' }}", package)
        self.assertIn("if: ${{ github.event_name != 'pull_request' && needs.selection.outputs.selected == 'true' && needs.proof.outputs.mode == 'execute' }}", check)
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
