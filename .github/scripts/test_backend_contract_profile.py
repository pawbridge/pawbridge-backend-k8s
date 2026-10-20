"""Pure selection/evidence tests: no Git, application, network or database execution."""

import copy
import contextlib
import io
import json
import types
import unittest
from unittest.mock import Mock, patch

import backend_contract_profile as profile


def context():
    return dict(event_name='pull_request', repository=profile.REPOSITORY,
                head_repository=profile.REPOSITORY, head_ref=profile.PILOT_REF,
                base_sha=profile.PILOT_BASE, head_sha='1' * 40, source_sha='2' * 40,
                tree_sha='3' * 40, run_id='12345', run_attempt='1')


PATHS = sorted(profile.PILOT_PATHS)
JAR = 'a' * 64


def steps(mode):
    required = set(profile.expected_smokes(mode))
    result = {key: {'outcome': 'success' if key in required else 'skipped',
                    'conclusion': 'success' if key in required else 'skipped'}
              for key in profile.ALL_SMOKES}
    if mode == profile.FULL:
        for key in profile.POST_STATISTICS_FULL_CONTRACTS + profile.AUTH_FULL_CONTRACTS + profile.VISIT_FULL_CONTRACTS:
            result[key] = {'outcome': 'success', 'conclusion': 'success'}
    result['owned_cleanup'] = {'outcome': 'success', 'conclusion': 'success'}
    return result


class ContractProfileTest(unittest.TestCase):
    def test_given_exact_same_repository_ci_pr__when_selecting__then_keep_full_and_add_fixed_pilot(self):
        minimal = {key: context()[key] for key in ('event_name', 'repository', 'head_repository', 'head_ref', 'base_sha')}
        for ctx in (context(), minimal):
            for paths in (PATHS, [PATHS[0]], list(reversed(PATHS))):
                with self.subTest(paths=paths, context_keys=len(ctx)):
                    self.assertEqual([profile.FULL, profile.PILOT], profile.select_profiles(ctx, paths))

    def test_given_fork_unknown_base_ref_event_or_path__when_selecting__then_default_to_full(self):
        cases = [({'head_repository': 'someone/fork'}, PATHS),
                 ({'repository': 'someone/fork'}, PATHS),
                 ({'head_ref': 'feat/new-api'}, PATHS),
                 ({'base_sha': 'b' * 40}, PATHS),
                 ({'event_name': 'push'}, PATHS),
                 ({'event_name': 'unknown'}, PATHS), ({}, []),
                 ({}, PATHS + ['backend-app/boot/build.gradle'])]
        for delta, paths in cases:
            with self.subTest(delta=delta, paths=paths):
                self.assertEqual([profile.FULL], profile.select_profiles(dict(context(), **delta), paths))
        self.assertEqual([profile.FULL], profile.select_profiles({}, PATHS))

    def test_given_explicit_ineligible_pilot__when_selecting__then_reject_instead_of_silently_downgrading(self):
        for delta, paths in (({'base_sha': 'b' * 40}, PATHS),
                             ({'head_repository': 'someone/fork'}, PATHS), ({}, []),
                             ({}, PATHS + ['backend-app/user/build.gradle'])):
            with self.subTest(delta=delta):
                with self.assertRaises(profile.ProfileBlocked):
                    profile.select_profiles(dict(context(), requested_profile=profile.PILOT, **delta), paths)

    def test_given_unknown_requested_profile_or_duplicate_paths__when_selecting__then_reject(self):
        for value in ('', 'FULL', 'f26-pilot'):
            with self.subTest(value=value):
                # Empty optional selection defaults full/pilot; an execution mode may never be empty.
                with self.assertRaises(profile.ProfileBlocked):
                    profile.expected_smokes(value)
        for requested in ('FULL', 'f26-pilot'):
            with self.assertRaises(profile.ProfileBlocked):
                profile.select_profiles(dict(context(), requested_profile=requested), PATHS)
        with self.assertRaises(profile.ProfileBlocked):
            profile.select_profiles(context(), PATHS + [PATHS[0]])

    def test_given_fixed_profiles__when_planning__then_require_exact_twenty_eight_or_twenty_two_smokes(self):
        self.assertEqual(tuple('jar_f' + str(n) for n in range(3, 30)) + ('jar_f31',), profile.expected_smokes(profile.FULL))
        expected = tuple('jar_f' + str(n) for n in range(3, 30) if n not in (16, 17, 19, 21, 22, 23)) + ('jar_f31',)
        self.assertEqual(expected, profile.expected_smokes(profile.PILOT))
        plan = profile.make_plan(context(), PATHS, profile.PILOT, JAR)
        self.assertEqual(22, len(plan['required_smokes']))
        self.assertEqual(list(profile.PILOT_SKIPS), plan['skipped_smokes'])

    def test_given_full_default_scope__when_planning__then_accept_nonpilot_changes(self):
        ctx = dict(context(), head_ref='feat/ordinary-api', base_sha='b' * 40)
        plan = profile.make_plan(ctx, ['backend-app/community/src/main/java/Changed.java'], profile.FULL, JAR)
        self.assertEqual([], plan['skipped_smokes'])
        self.assertEqual(28, len(plan['required_smokes']))

    def test_given_incomplete_or_invalid_identity_and_jar__when_planning__then_reject(self):
        for key in profile.IDENTITY_KEYS:
            with self.subTest(missing=key):
                ctx = context(); del ctx[key]
                with self.assertRaises(profile.ProfileBlocked):
                    profile.make_plan(ctx, PATHS, profile.FULL, JAR)
        for key, value in (('source_sha', 'not-a-sha'), ('tree_sha', '0' * 40),
                           ('run_id', '0'), ('run_attempt', 'abc')):
            with self.subTest(key=key):
                with self.assertRaises(profile.ProfileBlocked):
                    profile.make_plan(dict(context(), **{key: value}), PATHS, profile.FULL, JAR)
        for jar in ('', 'a' * 63, 'A' * 64):
            with self.assertRaises(profile.ProfileBlocked):
                profile.make_plan(context(), PATHS, profile.FULL, jar)

    def test_given_valid_plan_and_actual_results__when_auditing__then_accept_only_its_mode_and_candidate(self):
        for mode in (profile.FULL, profile.PILOT):
            with self.subTest(mode=mode):
                plan = profile.make_plan(context(), PATHS, mode, JAR)
                self.assertEqual(plan, profile.make_plan(context(), list(reversed(PATHS)), mode, JAR))
                result = profile.audit_execution(plan, context(), PATHS, mode, JAR, steps(mode))
                self.assertEqual('passed', result['status'])
                self.assertEqual(list(profile.expected_smokes(mode)), result['executed_smokes'])
                self.assertEqual(context(), result['identity'])
                self.assertEqual(29 + (8 if mode == profile.FULL else 0), len(result['step_results']))

    def test_given_other_source_base_head_tree_run_or_jar__when_auditing__then_reject_stale_evidence(self):
        plan = profile.make_plan(context(), PATHS, profile.FULL, JAR)
        for key in ('source_sha', 'base_sha', 'head_sha', 'tree_sha', 'run_id', 'run_attempt'):
            value = 'b' * 40 if key.endswith('_sha') else '2'
            with self.subTest(key=key):
                with self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan, dict(context(), **{key: value}), PATHS, profile.FULL, JAR, steps(profile.FULL))
        with self.assertRaises(profile.ProfileBlocked):
            profile.audit_execution(plan, context(), PATHS, profile.FULL, 'b' * 64, steps(profile.FULL))
        with self.assertRaises(profile.ProfileBlocked):
            profile.audit_execution(plan, context(), [PATHS[0]], profile.FULL, JAR, steps(profile.FULL))
        with self.assertRaises(profile.ProfileBlocked):
            profile.audit_execution(plan, context(), PATHS, profile.PILOT, JAR, steps(profile.PILOT))

    def test_given_missing_new_or_misnamed_packaged_steps__when_auditing__then_require_profile_review(self):
        plan = profile.make_plan(context(), PATHS, profile.FULL, JAR)
        for operation in ('missing', 'missing-f27', 'missing-f28', 'missing-f29', 'missing-f31', 'new-f30', 'misnamed'):
            actual = steps(profile.FULL)
            if operation == 'missing': del actual['jar_f13']
            elif operation == 'missing-f27': del actual['jar_f27']
            elif operation == 'missing-f28': del actual['jar_f28']
            elif operation == 'missing-f29': del actual['jar_f29']
            elif operation == 'missing-f31': del actual['jar_f31']
            elif operation == 'new-f30': actual['jar_f30'] = {'outcome': 'success', 'conclusion': 'success'}
            else: actual['jar_f03'] = actual.pop('jar_f3')
            with self.subTest(operation=operation), self.assertRaises(profile.ProfileBlocked):
                profile.audit_execution(plan, context(), PATHS, profile.FULL, JAR, actual)

    def test_given_failed_cancelled_skipped_or_continue_on_error_required_step__when_auditing__then_reject(self):
        plan = profile.make_plan(context(), PATHS, profile.PILOT, JAR)
        for outcome, conclusion in (('failure', 'failure'), ('failure', 'success'),
                                    ('cancelled', 'cancelled'), ('skipped', 'skipped'), ('success', 'failure')):
            for key in ('jar_f12', 'jar_f27', 'jar_f28', 'jar_f29', 'jar_f31'):
                actual = steps(profile.PILOT); actual[key] = dict(outcome=outcome, conclusion=conclusion)
                with self.subTest(step=key, outcome=outcome, conclusion=conclusion), self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan, context(), PATHS, profile.PILOT, JAR, actual)

    def test_given_planned_skip_was_run_failed_or_half_skipped__when_auditing__then_reject(self):
        plan = profile.make_plan(context(), PATHS, profile.PILOT, JAR)
        for state in ({'outcome': 'success', 'conclusion': 'success'},
                      {'outcome': 'failure', 'conclusion': 'failure'},
                      {'outcome': 'skipped', 'conclusion': 'success'}):
            for key in profile.PILOT_SKIPS:
                actual = steps(profile.PILOT); actual[key] = state
                with self.subTest(key=key, state=state), self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan, context(), PATHS, profile.PILOT, JAR, actual)

    def test_given_missing_failed_or_masked_cleanup__when_auditing__then_reject(self):
        plan = profile.make_plan(context(), PATHS, profile.FULL, JAR)
        for state in (None, {'outcome': 'failure', 'conclusion': 'success'},
                      {'outcome': 'skipped', 'conclusion': 'skipped'}):
            actual = steps(profile.FULL)
            if state is None: del actual['owned_cleanup']
            else: actual['owned_cleanup'] = state
            with self.subTest(state=state), self.assertRaises(profile.ProfileBlocked):
                profile.audit_execution(plan, context(), PATHS, profile.FULL, JAR, actual)

    def test_given_mutated_or_extra_plan_evidence__when_auditing__then_reject(self):
        plan = profile.make_plan(context(), PATHS, profile.PILOT, JAR)
        for key, value in (('schema_version', 2), ('schema_version', True), ('required_smokes', []), ('skipped_smokes', []),
                           ('selection_reason', 'manual'), ('status', 'passed'), ('extra', 'untrusted')):
            modified = copy.deepcopy(plan); modified[key] = value
            with self.subTest(key=key), self.assertRaises(profile.ProfileBlocked):
                profile.audit_execution(modified, context(), PATHS, profile.PILOT, JAR, steps(profile.PILOT))

    def test_given_duplicate_json_keys_or_nonfinite_values__when_loading__then_reject(self):
        for raw in ('{"profile":"full","profile":"full"}',
                    '{"jar_f3":{"outcome":"failure","outcome":"success"}}',
                    '{"jar_f3":{},"jar_f3":{}}', '{"a":NaN}', 'not-json'):
            with self.subTest(raw=raw), self.assertRaises(profile.ProfileBlocked):
                profile.load_json(raw)
        self.assertEqual({'a': 1}, profile.load_json('{"a":1}'))

    def test_given_unrelated_step_outputs_and_environment_values__when_auditing__then_do_not_preserve_them(self):
        ctx = dict(context(), secret='DO_NOT_PERSIST')
        plan = profile.make_plan(ctx, PATHS, profile.FULL, JAR)
        actual = steps(profile.FULL)
        actual['jar_f3']['outputs'] = {'secret': 'DO_NOT_PERSIST'}
        actual['unrelated'] = {'outputs': {'secret': 'DO_NOT_PERSIST'}}
        result = profile.audit_execution(plan, ctx, PATHS, profile.FULL, JAR, actual)
        self.assertNotIn('DO_NOT_PERSIST', json.dumps((plan, result)))
        self.assertNotIn('unrelated', result['step_results'])

    def test_given_runtime_pr_or_push_metadata__when_loading__then_reuse_common_policy_and_distinguish_head_from_source(self):
        for event in ('pull_request', 'push'):
            with self.subTest(event=event):
                ctx = context()
                payload = {'pull_request': {'base': {'sha': ctx['base_sha']},
                            'head': {'repo': {'full_name': ctx['head_repository']},
                                     'ref': ctx['head_ref'], 'sha': ctx['head_sha']}},
                           'before': ctx['base_sha'], 'unrelated': 'DO_NOT_PERSIST'}
                env = {'GITHUB_EVENT_NAME': event, 'GITHUB_EVENT_PATH': '/unused/event.json',
                       'GITHUB_REPOSITORY': ctx['repository'], 'GITHUB_SHA': ctx['source_sha'],
                       'GITHUB_REF': 'refs/heads/dev', 'GITHUB_RUN_ID': ctx['run_id'],
                       'GITHUB_RUN_ATTEMPT': ctx['run_attempt'],
                       'PULL_REQUEST_BASE_SHA': ctx['base_sha'], 'PUSH_BEFORE_SHA': ctx['base_sha']}
                common = types.SimpleNamespace(validate_event=Mock(return_value=event),
                         changed_paths=Mock(return_value=list(reversed(PATHS))),
                         git=Mock(return_value=(ctx['tree_sha'] + '\n').encode()))
                with patch.dict('sys.modules', {'ci_branch_policy': common}), \
                        patch.object(profile, 'verify_note_expiry_inputs', return_value=False), \
                        patch.object(profile, 'verify_member_management_inputs', return_value=False), \
                        patch.object(profile, 'verify_signup_statistics_inputs', return_value=False), \
                        patch.object(profile, 'verify_auth_sessions_inputs', return_value=False), \
                    patch.object(profile, 'verify_post_statistics_inputs', return_value=False), \
                        patch.object(profile, 'verify_visit_collection_inputs', return_value=False), \
                        patch.object(profile, 'verify_admin_member_update_inputs', return_value=False), \
                        patch.dict(profile.os.environ, env, clear=True), \
                        patch.object(profile.Path, 'read_text', return_value=json.dumps(payload)):
                    actual, paths = profile.runtime_context()
                    common.validate_event.assert_called_once_with(profile.os.environ)
                    common.changed_paths.assert_called_once_with(profile.os.environ, event)
                common.git.assert_called_once_with('rev-parse', 'HEAD^{tree}')
                self.assertEqual(ctx['source_sha'], actual['source_sha'])
                self.assertEqual(ctx['head_sha'] if event == 'pull_request' else ctx['source_sha'], actual['head_sha'])
                self.assertEqual(PATHS, paths)
                self.assertEqual(set(profile.IDENTITY_KEYS) | {'note_expiry_scope_verified', 'member_management_scope_verified', 'signup_statistics_scope_verified', 'admin_member_update_scope_verified', 'visit_collection_scope_verified', 'auth_sessions_scope_verified', 'post_statistics_scope_verified'}, set(actual))
                self.assertIs(False, actual['note_expiry_scope_verified'])
                self.assertIs(False, actual['member_management_scope_verified'])
                self.assertIs(False, actual['signup_statistics_scope_verified'])
                self.assertIs(False, actual['admin_member_update_scope_verified'])
                self.assertIs(False, actual['visit_collection_scope_verified'])
                self.assertIs(False, actual['auth_sessions_scope_verified'])

    def test_given_payload_base_differs_from_common_policy_input__when_loading__then_refuse_context(self):
        payload = {'pull_request': {'base': {'sha': profile.PILOT_BASE}}}
        common = types.SimpleNamespace(validate_event=Mock(return_value='pull_request'),
                 changed_paths=Mock(return_value=PATHS), git=Mock())
        env = {'GITHUB_EVENT_PATH': '/unused/event.json', 'GITHUB_REPOSITORY': profile.REPOSITORY,
               'GITHUB_SHA': '2' * 40, 'PULL_REQUEST_BASE_SHA': 'b' * 40}
        with patch.dict('sys.modules', {'ci_branch_policy': common}), \
                patch.dict(profile.os.environ, env, clear=True), \
                patch.object(profile.Path, 'read_text', return_value=json.dumps(payload)), \
                self.assertRaises(profile.ProfileBlocked):
            profile.runtime_context()
        common.git.assert_not_called()

    def test_given_duplicate_execution_file__when_cli_audits__then_fail_without_overwrite_success_or_exception_details(self):
        plan = profile.make_plan(context(), PATHS, profile.FULL, JAR)
        stderr = io.StringIO()
        with patch.object(profile, 'runtime_context', return_value=(context(), PATHS)), \
                patch.object(profile, '_jar_hash', return_value=JAR), \
                patch.object(profile.Path, 'is_symlink', return_value=False), \
                patch.object(profile.Path, 'is_file', return_value=True), \
                patch.object(profile.Path, 'read_text', return_value=json.dumps(plan)), \
                patch.object(profile, '_write_evidence', side_effect=FileExistsError('DO_NOT_PRINT')) as write, \
                patch.dict(profile.os.environ, {'CONTRACT_STEPS_JSON': json.dumps(steps(profile.FULL))}, clear=True), \
                contextlib.redirect_stderr(stderr):
            code = profile.main(['--mode', 'audit', '--profile', profile.FULL, '--jar', '/unused/app.jar',
                                 '--plan', '/unused/contract-plan.txt', '--execution', '/unused/contract-execution.txt'])
        self.assertEqual(1, code)
        self.assertEqual('Contract profile verification failed.\n', stderr.getvalue())
        self.assertEqual('failed', write.call_args.args[2]['status'])


if __name__ == '__main__':
    unittest.main()
