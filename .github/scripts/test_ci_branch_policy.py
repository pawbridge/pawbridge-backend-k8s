"""Exercise check-only CI selection and gates without network or production writes."""
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import ci_branch_policy as policy

ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / '.github/scripts/ci_branch_policy.py'


class CliAssertions:
    def run_policy(self, *arguments, directory=None, variables=None):
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(('GITHUB_', 'PULL_REQUEST_', 'PUSH_', 'MANUAL_', 'NEEDS_'))}
        environment.update(variables or {})
        return subprocess.run([sys.executable, str(POLICY), *arguments],
                              cwd=directory or ROOT, env=environment,
                              capture_output=True, text=True, timeout=10)

    def assert_accepted(self, result):
        self.assertEqual(0, result.returncode, result.stderr or result.stdout)

    def assert_rejected(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout)

class PolicyCliTest(CliAssertions, unittest.TestCase):
    def test_given_publish_false__when_check_only_is_required__then_accept(self):
        self.assert_accepted(self.run_policy('require-check-only', '--publish-env', 'PUBLISH',
                                             variables={'PUBLISH': 'false'}))

    def test_given_missing_true_or_malformed_publish__when_check_only_is_required__then_reject(self):
        for value in ('', 'true', 'TRUE', '0', ' false '):
            with self.subTest(value=value):
                variables = {} if value == '' else {'PUBLISH': value}
                self.assert_rejected(self.run_policy('require-check-only', '--publish-env', 'PUBLISH',
                                                    variables=variables))

    def selection(self, selected=True, services=None):
        services = ['animal-service'] if services is None and selected else (services or [])
        return {'result': 'success', 'outputs': {
            'selected': str(selected).lower(), 'count': str(len(services)),
            'matrix': json.dumps({'service': services}, separators=(',', ':'))}}

    def gate(self, needs, required=False):
        arguments = ['gate', '--check', 'check']
        if required:
            arguments += ['--required', 'policy']
        return self.run_policy(*arguments, variables={'NEEDS_JSON': json.dumps(needs)})

    def test_given_selected_success_or_empty_expected_skip__when_gating__then_accept(self):
        for selected, check_result in ((True, 'success'), (False, 'skipped')):
            with self.subTest(selected=selected):
                self.assert_accepted(self.gate({'selection': self.selection(selected),
                                               'check': {'result': check_result}}))

    def test_given_required_job_failure_cancel_or_skip__when_gating__then_reject(self):
        for result in ('failure', 'cancelled', 'skipped'):
            with self.subTest(result=result):
                self.assert_rejected(self.gate({'selection': self.selection(),
                                               'check': {'result': 'success'},
                                               'policy': {'result': result}}, required=True))
        self.assert_accepted(self.gate({'selection': self.selection(), 'check': {'result': 'success'},
                                       'policy': {'result': 'success'}}, required=True))

    def test_given_failed_missing_or_unexpected_check__when_gating__then_reject(self):
        for selected, result in ((True, 'failure'), (True, 'cancelled'), (True, 'skipped'),
                                 (False, 'success'), (False, 'failure'), (False, 'cancelled')):
            with self.subTest(selected=selected, result=result):
                self.assert_rejected(self.gate({'selection': self.selection(selected),
                                               'check': {'result': result}}))
        for needs in ({}, {'selection': self.selection()}, {'check': {'result': 'success'}}):
            with self.subTest(needs=needs):
                self.assert_rejected(self.gate(needs))

    def test_given_failed_or_inconsistent_selection__when_gating__then_reject(self):
        mutations = [
            ('result', 'failure'), ('result', 'cancelled'), ('result', 'skipped'),
            ('selected', 'TRUE'), ('count', '0'), ('count', 'invalid'),
            ('matrix', '{"service":[]}'), ('matrix', '{"unexpected":["animal-service"]}'),
            ('matrix', 'invalid')]
        for key, value in mutations:
            with self.subTest(key=key, value=value):
                selection = self.selection()
                if key == 'result':
                    selection[key] = value
                else:
                    selection['outputs'][key] = value
                self.assert_rejected(self.gate({'selection': selection, 'check': {'result': 'success'}}))
        for key in ('selected', 'count', 'matrix'):
            with self.subTest(missing=key):
                selection = self.selection()
                del selection['outputs'][key]
                self.assert_rejected(self.gate({'selection': selection, 'check': {'result': 'success'}}))

    def test_given_malformed_needs_json__when_gating__then_reject(self):
        for value in ('', 'invalid', '[]', 'null'):
            with self.subTest(value=value):
                self.assert_rejected(self.run_policy('gate', '--check', 'check',
                                                    variables={'NEEDS_JSON': value}))


class ChangedPathsSelectionTest(CliAssertions, unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repository = Path(self.temporary.name)
        self.git('init', '--initial-branch=dev')
        self.write('README.md', 'initial\n')
        self.git('add', 'README.md')
        self.git('commit', '-m', 'initial fixture')
        self.base = self.git('rev-parse', 'HEAD')
        self.git('branch', 'main')

    def git(self, *arguments):
        result = subprocess.run(['git', '-c', 'user.name=CI fixture',
                                 '-c', 'user.email=ci-fixture@example.invalid', *arguments],
                                cwd=self.repository, capture_output=True, text=True,
                                check=True, timeout=10)
        return result.stdout.strip()

    def write(self, relative, text='changed\n'):
        path = self.repository / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def commit(self, *paths):
        for path in paths:
            self.write(path)
        self.git('add', '--', *paths)
        self.git('commit', '-m', 'changed fixture')
        return self.git('rev-parse', 'HEAD')

    def event(self, head, name='push', ref='refs/heads/dev', **overrides):
        variables = {'GITHUB_EVENT_NAME': name, 'GITHUB_REF': ref, 'GITHUB_SHA': head,
                     'PUSH_BEFORE_SHA': self.base, 'PULL_REQUEST_BASE_SHA': self.base,
                     'PULL_REQUEST_BASE_REF': 'dev', 'MANUAL_PUBLISH': ''}
        variables.update(overrides)
        return variables

    def select(self, group, variables):
        return self.run_policy('select', '--group', group, directory=self.repository,
                               variables=variables)

    def assert_selection(self, result, services):
        self.assert_accepted(result)
        outputs = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
        self.assertEqual(str(bool(services)).lower(), outputs.get('selected'))
        self.assertEqual(str(len(services)), outputs.get('count'))
        matrix = json.loads(outputs['matrix'])
        self.assertEqual({'service'}, set(matrix))
        self.assertCountEqual(services, matrix['service'])

    def test_given_changed_service__when_selecting_each_group__then_select_only_its_services(self):
        head = self.commit('api-gateway/src/main/example.txt', 'store-service/src/main/example.txt',
                           'community-service/src/main/example.txt', 'animal-service/src/main/example.txt')
        for group, expected in (('gateway', ['api-gateway']), ('store', ['store-service']),
                                ('remaining', ['animal-service', 'community-service'])):
            with self.subTest(group=group):
                self.assert_selection(self.select(group, self.event(head)), expected)

    def test_given_only_document_change__when_selecting__then_emit_consistent_empty_outputs(self):
        head = self.commit('docs/guide.md')
        for group in ('gateway', 'store', 'remaining'):
            with self.subTest(group=group):
                self.assert_selection(self.select(group, self.event(head)), [])

    def test_given_feature_head_and_main_or_dev_pr_target__when_selecting__then_accept(self):
        self.git('checkout', '-b', 'feature/check-contract')
        head = self.commit('api-gateway/src/main/example.txt')
        for target in ('main', 'dev'):
            with self.subTest(target=target):
                event = self.event(head, name='pull_request', ref='refs/pull/7/merge',
                                   PULL_REQUEST_BASE_REF=target)
                self.assert_selection(self.select('gateway', event), ['api-gateway'])

    def test_given_main_or_dev_push__when_selecting__then_accept(self):
        head = self.commit('api-gateway/src/main/example.txt')
        for branch in ('main', 'dev'):
            with self.subTest(branch=branch):
                self.assert_selection(self.select('gateway', self.event(head, ref='refs/heads/'+branch)),
                                      ['api-gateway'])

    def test_given_manual_false_and_service__when_selecting__then_select_requested_service(self):
        head = self.git('rev-parse', 'HEAD')
        for group, service in (('gateway', 'api-gateway'), ('store', 'store-service'),
                               ('remaining', 'payment-service')):
            with self.subTest(group=group):
                event = self.event(head, name='workflow_dispatch', MANUAL_PUBLISH='false',
                                   MANUAL_SERVICE=service)
                self.assert_selection(self.select(group, event), [service])

    def test_given_invalid_event_ref_or_publication__when_selecting__then_reject(self):
        head = self.commit('api-gateway/src/main/example.txt')
        cases = [dict(GITHUB_EVENT_NAME='schedule'), dict(GITHUB_REF='refs/heads/feature/other'),
                 dict(GITHUB_REF='refs/tags/v1'), dict(MANUAL_PUBLISH='true'),
                 dict(MANUAL_PUBLISH='FALSE'), dict(PULL_REQUEST_BASE_REF='other',
                      GITHUB_EVENT_NAME='pull_request', GITHUB_REF='refs/pull/7/merge')]
        for changes in cases:
            with self.subTest(changes=changes):
                event = self.event(head)
                event.update(changes)
                self.assert_rejected(self.select('gateway', event))
        for value in ('', 'true', 'FALSE', ' false '):
            with self.subTest(manual_publish=value):
                event = self.event(head, name='workflow_dispatch', MANUAL_PUBLISH=value,
                                   MANUAL_SERVICE='api-gateway')
                self.assert_rejected(self.select('gateway', event))

    def test_given_missing_malformed_or_stale_revision__when_selecting__then_reject(self):
        head = self.commit('api-gateway/src/main/example.txt')
        for changes in (dict(GITHUB_SHA=self.base), dict(GITHUB_SHA='short'),
                        dict(PUSH_BEFORE_SHA=''), dict(PUSH_BEFORE_SHA='short'),
                        dict(GITHUB_EVENT_NAME='pull_request', GITHUB_REF='refs/pull/7/merge',
                             PULL_REQUEST_BASE_SHA='')):
            with self.subTest(changes=changes):
                event = self.event(head)
                event.update(changes)
                self.assert_rejected(self.select('gateway', event))

    def test_given_new_branch_zero_before__when_selecting__then_compare_empty_tree(self):
        head = self.commit('api-gateway/src/main/example.txt')
        self.assert_selection(self.select('gateway', self.event(head, PUSH_BEFORE_SHA='0'*40)),
                              ['api-gateway'])

    def test_given_each_backend_only_ci_file__when_selecting_pr_or_push__then_skip_legacy_groups(self):
        for path in ('.github/scripts/backend_contract_profile.py',
                     '.github/scripts/test_backend_admin_post_reads_profile.py'):
            before = self.git('rev-parse', 'HEAD')
            head = self.commit(path)
            for event in ('pull_request', 'push'):
                for group in ('gateway', 'store', 'remaining'):
                    with self.subTest(path=path, event=event, group=group):
                        variables = self.event(head, name=event, PUSH_BEFORE_SHA=before,
                                               PULL_REQUEST_BASE_SHA=before)
                        self.assert_selection(self.select(group, variables), [])

    def test_given_backend_only_ci_and_user_change__when_selecting_pr_or_push__then_select_user_only(self):
        head = self.commit('.github/scripts/backend_contract_profile.py',
                           '.github/scripts/test_backend_admin_post_reads_profile.py',
                           'user-service/src/main/example.txt')
        for event in ('pull_request', 'push'):
            with self.subTest(event=event):
                self.assert_selection(self.select('remaining', self.event(head, name=event)),
                                      ['user-service'])

    def test_given_backend_only_ci_files__when_reading_pr_or_push_paths__then_preserve_application_inputs(self):
        paths = ['.github/scripts/backend_contract_profile.py',
                 '.github/scripts/test_backend_admin_post_reads_profile.py']
        head = self.commit(*paths)
        def fixture_git(*arguments):
            return subprocess.run(['git', *arguments], cwd=self.repository, check=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10).stdout
        for event in ('pull_request', 'push'):
            with self.subTest(event=event), patch.object(policy, 'git', side_effect=fixture_git):
                self.assertEqual(paths, policy.changed_paths(self.event(head, name=event), event))

    def test_given_backend_only_ci_and_common_selector__when_selecting_pr_or_push__then_keep_all_remaining_services(self):
        head = self.commit('.github/scripts/backend_contract_profile.py',
                           '.github/scripts/test_backend_admin_post_reads_profile.py',
                           '.github/scripts/ci_branch_policy.py')
        for event in ('pull_request', 'push'):
            with self.subTest(event=event):
                self.assert_selection(self.select('remaining', self.event(head, name=event)),
                                      ['animal-service', 'community-service',
                                       'payment-service', 'user-service'])

    def test_given_backend_app_or_other_shared_inputs__when_selecting__then_preserve_existing_path_boundaries(self):
        cases = (
            ('backend-app/boot/src/main/example.txt', []),
            ('.github/scripts/backend_contract_profile.py.extra',
             ['animal-service', 'community-service', 'payment-service', 'user-service']),
            ('.github/scripts/backend_postmerge_proof.py',
             ['animal-service', 'community-service', 'payment-service', 'user-service']),
            ('.github/scripts/update-service-image.py',
             ['animal-service', 'community-service', 'payment-service', 'user-service']),
            ('.github/workflows/_java-service-image.yml',
             ['animal-service', 'community-service', 'payment-service', 'user-service']),
            ('.github/workflows/remaining-services-image-ci.yml',
             ['animal-service', 'community-service', 'payment-service', 'user-service']),
            ('.github/migrations/example.py',
             ['animal-service', 'community-service', 'payment-service', 'user-service']))
        for path, expected in cases:
            before = self.git('rev-parse', 'HEAD')
            head = self.commit(path)
            with self.subTest(path=path):
                self.assert_selection(self.select('remaining', self.event(head, PUSH_BEFORE_SHA=before)),
                                      expected)

    def test_given_shared_ci_change__when_selecting__then_validate_all_groups(self):
        head = self.commit('.github/scripts/ci_branch_policy.py')
        for group, expected in (('gateway', ['api-gateway']), ('store', ['store-service']),
                                ('remaining', ['animal-service', 'community-service',
                                               'payment-service', 'user-service'])):
            with self.subTest(group=group):
                self.assert_selection(self.select(group, self.event(head)), expected)


class EnvironmentWorkflowPolicyTest(unittest.TestCase):
    def test_given_main_and_dev_events__when_policy_workflow_runs__then_discover_every_contract(self):
        text = (ROOT / '.github/workflows/environment-contract.yml').read_text()
        self.assertIn('pull_request:', text)
        self.assertIn('push:', text)
        self.assertGreaterEqual(text.count('branches: [dev, main]'), 2)
        self.assertNotIn('paths:', text)
        self.assertIn("python3 -m unittest discover -s .github/scripts -p 'test_*.py'", text)

    def test_given_failed_or_skipped_tests__when_final_policy_check_runs__then_require_success(self):
        text = (ROOT / '.github/workflows/environment-contract.yml').read_text()
        self.assertIn('name: Backend CI policy result', text)
        self.assertIn('if: ${{ always() }}', text)
        self.assertIn('needs: [test]', text)
        self.assertIn('POLICY_TEST_RESULT: ${{ needs.test.result }}', text)
        self.assertIn('test "$POLICY_TEST_RESULT" = success', text)
        self.assertNotIn('secrets.', text)
        self.assertIn('persist-credentials: false', text)


class RetiredServiceWorkflowTest(unittest.TestCase):
    """Old MSA service code is gone; only the required result checks remain."""

    REQUIRED = {
        'api-gateway-ci.yml': 'Gateway CI result',
        'store-service-ci.yml': 'Store CI result',
        'remaining-services-image-ci.yml': 'Remaining Java CI result',
    }

    def workflow(self, name):
        return (ROOT / '.github/workflows' / name).read_text()

    def test_given_branch_protection__when_retired_workflows_run__then_report_each_required_check(self):
        for name, check in self.REQUIRED.items():
            with self.subTest(workflow=name):
                text = self.workflow(name)
                self.assertIn('pull_request:', text)
                self.assertIn('push:', text)
                self.assertEqual(2, text.count('      - dev\n'))
                self.assertEqual(2, text.count('      - main\n'))
                self.assertNotIn('paths:', text)
                jobs = re.findall(r'^  ([A-Za-z0-9_-]+):$', text.split('\njobs:\n', 1)[1], re.M)
                self.assertEqual(['ci-result'], jobs)
                self.assertIn('    name: ' + check + '\n', text)

    def test_given_retired_workflows__when_running__then_no_secret_checkout_or_build_is_used(self):
        for name in self.REQUIRED:
            with self.subTest(workflow=name):
                text = self.workflow(name)
                self.assertNotIn('secrets', text)
                self.assertNotIn('uses:', text)
                self.assertNotIn('gradlew', text)
                self.assertIn('contents: read', text)

    def test_given_old_service_directories__when_listing__then_only_migration_sql_remains(self):
        listed = subprocess.run(['git', 'ls-files'], cwd=ROOT, capture_output=True,
                                text=True, check=True).stdout.split()
        tracked = [path for path in listed
                   if re.match(r'[a-z]+-service/', path) or path.startswith(('api-gateway/', 'integration-service/'))]
        self.assertTrue(tracked)
        for path in tracked:
            with self.subTest(path=path):
                self.assertRegex(path, r'^[a-z]+-service/src/migration/resources/db/(postgresql|migration)/V[0-9]+__[a-z0-9_]+\.sql$')


if __name__ == '__main__':
    unittest.main()
