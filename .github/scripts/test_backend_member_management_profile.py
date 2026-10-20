"""Fixed member lane contracts with synthetic Git/steps only; no PG, app or network."""

import contextlib
import copy
import hashlib
import io
import json
import subprocess
import types
import unittest
from unittest.mock import Mock, patch

import backend_contract_profile as profile


BASE = 'a35ee31ba700093c97fa9c0864d6a8677e5fae78'
PIN_MANIFEST_SHA256 = 'e61f2a56d60fdd9fed8e486c6538aad5f9e738964071b555a33cf7158231935c'
PATHS = ['backend-app/user/src/main/java/com/pawbridge/backend/user/internal/profile/web/AdminUserDetailController.java']
PG_SOURCE = 'backend-app/boot/src/compatibilityTest/java/com/pawbridge/backend/compatibility/PostgresqlNoteSendCompatibilityTest.java'
CONTRACTS = ['fixture_f4_shape', 'pg_note_expiry', 'fixture_member_cleanup', 'pg_member_cleanup']
JAR = 'a' * 64


def context(**changes):
    result = dict(event_name='pull_request', repository=profile.REPOSITORY,
                  head_repository=profile.REPOSITORY, head_ref='feat/member-management',
                  base_sha='b' * 40, head_sha='1' * 40, source_sha='2' * 40,
                  tree_sha='3' * 40, run_id='12345', run_attempt='1',
                  note_expiry_scope_verified=False, member_management_scope_verified=True)
    result.update(changes)
    return result


def successful_steps():
    steps = {key: {'outcome': 'skipped', 'conclusion': 'skipped'} for key in profile.ALL_SMOKES}
    for key in ('jar_f31', *CONTRACTS, 'owned_cleanup'):
        steps[key] = {'outcome': 'success', 'conclusion': 'success'}
    return steps


class MemberManagementProfileTest(unittest.TestCase):
    def fake_git(self, diff=None, corrupt=None, missing=None):
        blobs = {path: ('synthetic reviewed member input ' + str(index)).encode('ascii')
                 for index, path in enumerate(profile.MEMBER_APP_HASHES)}
        expected = {path: hashlib.sha256(raw).hexdigest() for path, raw in blobs.items()}
        if corrupt is not None:
            blobs[corrupt] = b'private mutation'
        if diff is None:
            diff = b'\0'.join(path.encode() for path in blobs) + b'\0'

        def read(*args):
            if args == ('diff', '--name-only', '--no-renames', '-z', BASE, 'HEAD', '--', 'backend-app'):
                return diff
            if len(args) == 2 and args[0] == 'show' and args[1].startswith('HEAD:'):
                path = args[1][5:]
                if path == missing:
                    raise subprocess.CalledProcessError(1, ['synthetic-read-only-git'])
                return blobs[path]
            raise AssertionError('Unexpected synthetic Git operation')

        return Mock(side_effect=read), expected

    def test_given_reviewed_member_and_shape_sources__when_checking_pin_contract__then_keep_exact_base_and_all_thirty_seven_hashes(self):
        self.assertEqual(BASE, profile.MEMBER_BASE)
        self.assertEqual(37, len(profile.MEMBER_APP_HASHES))
        self.assertEqual(PIN_MANIFEST_SHA256, hashlib.sha256(json.dumps(
            sorted(profile.MEMBER_APP_HASHES.items()), separators=(',', ':')).encode()).hexdigest())
        self.assertEqual(frozenset(profile.MEMBER_APP_HASHES) | {'backend-app/README.md'}, profile.MEMBER_APP_EXCEPTIONS)
        self.assertEqual(profile.NOTE_HELPER_HASHES['backend-app/scripts/f4_ci.py'],
                         profile.MEMBER_APP_HASHES['backend-app/scripts/f4_ci.py'])
        self.assertEqual(profile.NOTE_HELPER_HASHES['backend-app/scripts/test_f4_ci_startup.py'],
                         profile.MEMBER_APP_HASHES['backend-app/scripts/test_f4_ci_startup.py'])

    def test_given_sparse_checkout_and_verified_git_objects__when_checking_member_inputs__then_read_only_exact_pins_without_working_tree_files(self):
        git, expected = self.fake_git()
        with patch.object(profile, 'MEMBER_APP_HASHES', expected):
            self.assertIs(True, profile.verify_member_management_inputs(git_reader=git))
        self.assertEqual(38, git.call_count)
        git.assert_any_call('diff', '--name-only', '--no-renames', '-z', BASE, 'HEAD', '--', 'backend-app')
        for path in expected:
            git.assert_any_call('show', 'HEAD:' + path)

    def test_given_changed_missing_unapproved_or_unreadable_member_inputs__when_verifying__then_return_false_without_relaxing_scope(self):
        for defect in ('changed', 'missing', 'unapproved', 'duplicate', 'utf8', 'malformed', 'timeout'):
            with self.subTest(defect=defect):
                diff = None
                if defect == 'unapproved': diff = b'backend-app/user/src/main/java/Unreviewed.java\0'
                elif defect == 'duplicate': diff = (PATHS[0] + '\0' + PATHS[0] + '\0').encode()
                elif defect == 'utf8': diff = b'backend-app/\xff\0'
                elif defect == 'malformed': diff = 'not bytes'
                git, expected = self.fake_git(diff, corrupt=PG_SOURCE if defect == 'changed' else None,
                                               missing=PG_SOURCE if defect == 'missing' else None)
                if defect == 'timeout': git.side_effect = subprocess.TimeoutExpired('synthetic-git', 8)
                with patch.object(profile, 'MEMBER_APP_HASHES', expected):
                    self.assertIs(False, profile.verify_member_management_inputs(git_reader=git))

    def test_given_verified_member_pr_or_dev_push__when_selecting__then_require_only_its_jar_and_four_contracts_while_full_stays_available(self):
        self.assertEqual('member-management', profile.MEMBER_MANAGEMENT)
        self.assertEqual(CONTRACTS, list(profile.MEMBER_REQUIRED_CONTRACTS))
        self.assertEqual(('jar_f31',), profile.expected_smokes(profile.MEMBER_MANAGEMENT))
        self.assertEqual(28, len(profile.expected_smokes(profile.FULL)))
        self.assertEqual((), profile.expected_smokes(profile.NOTE_EXPIRY))
        for ctx in (context(), context(event_name='push', head_ref='dev')):
            for paths in (PATHS, ['.github/scripts/backend_contract_profile.py']):
                with self.subTest(event=ctx['event_name'], paths=paths):
                    self.assertEqual([profile.MEMBER_MANAGEMENT], profile.select_profiles(ctx, paths))
                    self.assertEqual([profile.FULL], profile.select_profiles(dict(ctx, requested_profile=profile.FULL), paths))
                    plan = profile.make_plan(ctx, paths, profile.MEMBER_MANAGEMENT, JAR)
                    self.assertEqual(['jar_f31'], plan['required_smokes'])
                    self.assertEqual(list(profile.ALL_SMOKES[:-1]), plan['skipped_smokes'])
                    self.assertEqual(27, len(plan['skipped_smokes']))
                    self.assertEqual(CONTRACTS, plan['required_contracts'])

    def test_given_unverified_fork_nondev_or_unknown_changes__when_selecting__then_default_full_and_refuse_an_explicit_member_claim(self):
        cases = [(dict(member_management_scope_verified=value), PATHS) for value in (False, None, 1, 'true')]
        cases += [({'repository': 'someone/fork'}, PATHS), ({'head_repository': 'someone/fork'}, PATHS),
                  ({'event_name': 'push', 'head_ref': 'feat/other'}, PATHS),
                  ({'event_name': 'unknown'}, PATHS), ({}, []),
                  ({}, PATHS + ['backend-app/user/src/main/java/Unreviewed.java'])]
        for delta, paths in cases:
            with self.subTest(delta=delta, paths=paths):
                ctx = context(**delta)
                self.assertEqual([profile.FULL], profile.select_profiles(ctx, paths))
                with self.assertRaises(profile.ProfileBlocked):
                    profile.select_profiles(dict(ctx, requested_profile=profile.MEMBER_MANAGEMENT), paths)
        ctx = context(); del ctx['member_management_scope_verified']
        self.assertEqual([profile.FULL], profile.select_profiles(ctx, PATHS))

    def test_given_successful_member_steps__when_auditing__then_preserve_only_actual_lane_results_and_never_a_full_claim(self):
        plan = profile.make_plan(context(), PATHS, profile.MEMBER_MANAGEMENT, JAR)
        result = profile.audit_execution(plan, context(), PATHS, profile.MEMBER_MANAGEMENT, JAR, successful_steps())
        self.assertEqual('passed', result['status'])
        self.assertEqual(profile.MEMBER_MANAGEMENT, result['profile'])
        self.assertEqual(['jar_f31'], result['executed_smokes'])
        self.assertEqual(CONTRACTS, result['required_contracts'])
        self.assertEqual(33, len(result['step_results']))
        self.assertEqual(set(profile.IDENTITY_KEYS), set(result['identity']))
        self.assertNotIn('member_management_scope_verified', json.dumps((plan, result)))

    def test_given_missing_failed_or_masked_member_contract_cleanup_or_jar__when_auditing__then_refuse_incomplete_success(self):
        plan = profile.make_plan(context(), PATHS, profile.MEMBER_MANAGEMENT, JAR)
        for key in ('jar_f31', *CONTRACTS, 'owned_cleanup'):
            for raw in (None, {'outcome': 'failure', 'conclusion': 'success'},
                        {'outcome': 'cancelled', 'conclusion': 'cancelled'},
                        {'outcome': 'skipped', 'conclusion': 'skipped'}):
                steps = successful_steps()
                if raw is None: del steps[key]
                else: steps[key] = raw
                with self.subTest(step=key, state=raw), self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan, context(), PATHS, profile.MEMBER_MANAGEMENT, JAR, steps)

    def test_given_old_jar_success_failure_or_unknown_step__when_auditing_member_lane__then_require_explicit_real_skips_and_exact_stage_set(self):
        plan = profile.make_plan(context(), PATHS, profile.MEMBER_MANAGEMENT, JAR)
        for key in ('jar_f3', 'jar_f29'):
            for raw in ({'outcome': 'success', 'conclusion': 'success'},
                        {'outcome': 'failure', 'conclusion': 'failure'},
                        {'outcome': 'skipped', 'conclusion': 'success'}):
                steps = successful_steps(); steps[key] = raw
                with self.subTest(step=key, state=raw), self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan, context(), PATHS, profile.MEMBER_MANAGEMENT, JAR, steps)
        steps = successful_steps(); steps['jar_f30'] = {'outcome': 'success', 'conclusion': 'success'}
        with self.assertRaises(profile.ProfileBlocked):
            profile.audit_execution(plan, context(), PATHS, profile.MEMBER_MANAGEMENT, JAR, steps)

    def test_given_forged_or_stale_member_plan__when_auditing__then_refuse_missing_contracts_and_promotion_to_another_profile(self):
        plan = profile.make_plan(context(), PATHS, profile.MEMBER_MANAGEMENT, JAR)
        for key, value in (('required_contracts', []), ('required_smokes', []),
                           ('skipped_smokes', []), ('profile', profile.FULL)):
            forged = copy.deepcopy(plan); forged[key] = value
            with self.subTest(field=key), self.assertRaises(profile.ProfileBlocked):
                profile.audit_execution(forged, context(), PATHS, profile.MEMBER_MANAGEMENT, JAR, successful_steps())
        with self.assertRaises(profile.ProfileBlocked):
            profile.audit_execution(plan, context(tree_sha='c' * 40), PATHS, profile.MEMBER_MANAGEMENT, JAR, successful_steps())
        with self.assertRaises(profile.ProfileBlocked):
            profile.audit_execution(plan, context(), PATHS, profile.FULL, JAR, successful_steps())

    def test_given_payload_and_environment_claiming_member_scope__when_loading_context__then_recompute_boolean_and_accept_member_cli_without_reading_a_real_jar(self):
        ctx = context()
        payload = {'member_management_scope_verified': True,
                   'pull_request': {'base': {'sha': ctx['base_sha']},
                                    'head': {'repo': {'full_name': ctx['head_repository']},
                                             'ref': ctx['head_ref'], 'sha': ctx['head_sha']}}}
        env = {'GITHUB_EVENT_NAME': 'pull_request', 'GITHUB_EVENT_PATH': '/unused/event.json',
               'GITHUB_REPOSITORY': ctx['repository'], 'GITHUB_SHA': ctx['source_sha'],
               'GITHUB_RUN_ID': ctx['run_id'], 'GITHUB_RUN_ATTEMPT': ctx['run_attempt'],
               'PULL_REQUEST_BASE_SHA': ctx['base_sha'], 'MEMBER_MANAGEMENT_SCOPE_VERIFIED': 'true'}
        for verified in (True, False):
            common = types.SimpleNamespace(validate_event=Mock(return_value='pull_request'),
                                           changed_paths=Mock(return_value=PATHS),
                                           git=Mock(return_value=(ctx['tree_sha'] + '\n').encode()))
            with self.subTest(actual_verified=verified), patch.dict('sys.modules', {'ci_branch_policy': common}), \
                    patch.dict(profile.os.environ, env, clear=True), \
                    patch.object(profile.Path, 'read_text', return_value=json.dumps(payload)), \
                    patch.object(profile, 'verify_note_expiry_inputs', return_value=False), \
                    patch.object(profile, 'verify_member_management_inputs', return_value=verified) as verify, \
                    patch.object(profile, 'verify_signup_statistics_inputs', return_value=False), \
                    patch.object(profile, 'verify_auth_sessions_inputs', return_value=False), \
                    patch.object(profile, 'verify_visit_collection_inputs', return_value=False), \
                    patch.object(profile, 'verify_admin_member_update_inputs', return_value=False):
                actual, paths = profile.runtime_context()
            verify.assert_called_once_with()
            self.assertIs(verified, actual['member_management_scope_verified'])
            self.assertEqual(PATHS, paths)
        with patch.object(profile, 'runtime_context', return_value=(ctx, PATHS)), \
                patch.object(profile, '_jar_hash', return_value=JAR), \
                patch.object(profile, '_write_evidence') as write, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, profile.main(['--mode', 'plan', '--profile', profile.MEMBER_MANAGEMENT,
                '--jar', '/unused/app.jar', '--plan', '/unused/contract-plan.txt']))
        self.assertEqual(profile.MEMBER_MANAGEMENT, write.call_args.args[2]['profile'])


if __name__ == '__main__':
    unittest.main()
