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

    def test_given_runtime_stage_conditions__when_selecting_pilot__then_only_the_six_smokes_can_be_skipped(self):
        check = self.job('check')
        parts = re.split(r'(?=^      - name: )', check, flags=re.MULTILINE)
        found, conditional = [], []
        selected = {mode: [] for mode in (backend_contract_profile.FULL,
                    backend_contract_profile.PILOT, backend_contract_profile.NOTE_EXPIRY)}
        allowed_conditions = {'Remove only the owned disposable fixture',
                              'Reject missing, failed or falsely skipped runtime contracts',
                              'Preserve tests and bounded runtime evidence',
                              'Preserve the verified executable JAR'}
        for part in parts[1:]:
            name = part.splitlines()[0].removeprefix('      - name: ')
            smoke = re.findall(r'^\s*(?:run: )?python3 scripts/(f[0-9]+)_ci.py smoke\s*$', part, re.MULTILINE)
            if smoke:
                self.assertEqual(1, len(smoke))
                key = 'jar_' + smoke[0]
                found.append(key)
                self.assertIn('        id: ' + key + '\n', part)
                if '        if:' in part:
                    condition = re.findall(r'^        if: (.+)$', part, re.MULTILINE)
                    self.assertEqual(1, len(condition))
                    if condition[0] == "${{ matrix.profile == 'full' }}":
                        conditional.append(key)
                    else:
                        self.assertEqual("${{ matrix.profile != 'note-expiry' }}", condition[0])
                    for mode in selected:
                        if (condition[0] == "${{ matrix.profile == 'full' }}" and mode == backend_contract_profile.FULL
                                or condition[0] == "${{ matrix.profile != 'note-expiry' }}" and mode != backend_contract_profile.NOTE_EXPIRY):
                            selected[mode].append(key)
                else:
                    self.fail('Every old JAR must declare its selected coverage.')
            elif '        if:' in part:
                if name not in allowed_conditions:
                    self.assertTrue(name.startswith('Compare ') or name.startswith('Require exact F')
                                    or name == 'Prepare only the owned F4 dependent table shape for scoped expiry')
        self.assertEqual(list(backend_contract_profile.ALL_SMOKES), found)
        self.assertEqual(list(backend_contract_profile.PILOT_SKIPS), conditional)
        for mode, actual in selected.items():
            self.assertEqual(list(backend_contract_profile.expected_smokes(mode)), actual)
        self.assertIn('id: owned_cleanup', check)
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

    def test_given_scoped_expiry__when_wiring__then_keep_preparation_pg_and_cleanup_with_explicit_noncoverage(self):
        check = self.job('check')
        parts = re.split(r'(?=^      - name: )', check, flags=re.MULTILINE)[1:]
        for part in parts:
            if re.search(r'run: python3 scripts/f[0-9]+_ci.py prepare\n', part):
                self.assertNotIn('        if:', part)
        shape = next(part for part in parts if 'id: fixture_f4_shape\n' in part)
        self.assertIn("if: ${{ matrix.profile == 'note-expiry' }}", shape)
        self.assertIn('python3 scripts/f4_ci.py prepare-shape', shape)
        self.assertLess(check.index('id: jar_f3\n'), check.index('id: fixture_f4_shape\n'))
        self.assertLess(check.index('id: fixture_f4_shape\n'), check.index('id: jar_f4\n'))
        pg = next(part for part in parts if 'id: pg_note_expiry\n' in part)
        self.assertNotIn('        if:', pg)
        self.assertIn(':boot:noteExpiryCompatibilityTest', pg)
        self.assertIn('verify_note_expiry_report', pg)
        self.assertIn('note_expiry_scope_verified', self.job('selection'))
        self.assertIn('verify_note_expiry_inputs()', self.job('selection'))

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
