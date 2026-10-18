"""Observe the dev-only application selection and final gate without running the app."""
import contextlib
import io
import os
from pathlib import Path
import re
import subprocess
import sys
import textwrap
import unittest
from unittest.mock import patch

import ci_branch_policy


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

    def select(self, paths):
        script = self.script('selection')
        marker = 'python3 - <<\'PY\' >> "$GITHUB_OUTPUT"\n'
        self.assertIn(marker, script)
        program = script.split(marker, 1)[1].rsplit('\nPY', 1)[0]
        output = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(sys, 'path', list(sys.path)), \
                patch.object(ci_branch_policy, 'validate_event', return_value='pull_request'), \
                patch.object(ci_branch_policy, 'changed_paths', return_value=paths), \
                contextlib.redirect_stdout(output):
            exec(compile(program, str(WORKFLOW), 'exec'), {})
        return output.getvalue()

    def gate(self, selection='success', selected='true', check='success'):
        return subprocess.run(
            ['bash', '-euc', self.script('ci-result')],
            env={'PATH': os.environ.get('PATH', ''), 'SELECTION_RESULT': selection,
                 'MODULE_SELECTED': selected, 'MODULE_RESULT': check},
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
                     '.github/scripts/ci_branch_policy.py'):
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
        self.assertIn('needs: selection', check)
        self.assertIn("if: ${{ needs.selection.outputs.selected == 'true' }}", check)
        gate = self.job('ci-result')
        self.assertIn('needs: [selection, check]', gate)
        self.assertIn('SELECTION_RESULT: ${{ needs.selection.result }}', gate)
        self.assertIn('MODULE_SELECTED: ${{ needs.selection.outputs.selected }}', gate)
        self.assertIn('MODULE_RESULT: ${{ needs.check.result }}', gate)

    def test_given_application_checks_are_selected__when_running__then_preserve_the_full_runtime_contract(self):
        check = self.job('check')
        for command in ('bash ./gradlew projects check :boot:bootJar',
                        'bash ./gradlew :boot:compatibilityTest :boot:compatibilitySeoulTest',
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


if __name__ == '__main__':
    unittest.main()
