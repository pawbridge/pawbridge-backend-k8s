"""Fixed administrator update lane: fake Git, raw step outcomes and temporary XML only."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

import backend_contract_profile as profile

BASE = '25bc27575f728f2e0bf710fc867fb067f2465255'
PINS = 'ecc0b59cc9a26616a05aed1f89c5cb511c922daadab142625d5a27c229fd9ae5'
PATHS = ['.github/scripts/backend_contract_profile.py']
JAR = 'a' * 64
CONTRACTS = ['fixture_f4_shape', 'compile_admin_update', 'fixture_member_cleanup', 'fixture_admin_update', 'pg_admin_update']


def context(**updates):
    value = dict(event_name='pull_request', repository=profile.REPOSITORY,
                 head_repository=profile.REPOSITORY, head_ref='feat/admin-member-update',
                 base_sha=BASE, head_sha='1' * 40, source_sha='2' * 40,
                 tree_sha='3' * 40, run_id='12345', run_attempt='1',
                 note_expiry_scope_verified=False, member_management_scope_verified=False,
                 signup_statistics_scope_verified=False, admin_member_update_scope_verified=True)
    value.update(updates)
    return value


def steps():
    result = {key: dict(outcome='skipped', conclusion='skipped') for key in profile.ALL_SMOKES}
    for key in (*CONTRACTS, 'owned_cleanup'):
        result[key] = dict(outcome='success', conclusion='success')
    return result


class AdminMemberUpdateProfileTest(unittest.TestCase):
    def synthetic_git(self, *, diff=None, corrupt=False):
        blobs = {path: ('synthetic admin input ' + str(i)).encode()
                 for i, path in enumerate(profile.ADMIN_APP_HASHES)}
        hashes = {path: hashlib.sha256(blob).hexdigest() for path, blob in blobs.items()}
        if corrupt:
            blobs[next(iter(blobs))] += b'changed'
        def read(*args):
            if args == ('diff', '--name-only', '--no-renames', '-z', BASE, 'HEAD', '--', 'backend-app'):
                return b'\0'.join(path.encode() for path in (*blobs, 'backend-app/README.md')) + b'\0' if diff is None else diff
            if len(args) == 2 and args[0] == 'show':
                return blobs[args[1].removeprefix('HEAD:')]
            raise AssertionError('Unexpected synthetic Git operation')
        return Mock(side_effect=read), hashes

    def test_given_twenty_frozen_inputs_and_readme_only_exception__when_verifying__then_read_exact_git_objects_without_checkout_files(self):
        self.assertEqual(BASE, profile.ADMIN_BASE)
        self.assertEqual(20, len(profile.ADMIN_APP_HASHES))
        self.assertEqual(PINS, hashlib.sha256(json.dumps(sorted(profile.ADMIN_APP_HASHES.items()),
                                                        separators=(',', ':')).encode()).hexdigest())
        self.assertEqual(frozenset(profile.ADMIN_APP_HASHES) | {'backend-app/README.md'}, profile.ADMIN_APP_EXCEPTIONS)
        self.assertNotIn('backend-app/README.md', profile.ADMIN_APP_HASHES)
        git, hashes = self.synthetic_git()
        with patch.object(profile, 'ADMIN_APP_HASHES', hashes):
            self.assertIs(True, profile.verify_admin_member_update_inputs(git_reader=git))
        self.assertEqual(21, git.call_count)
        for path in hashes:
            git.assert_any_call('show', 'HEAD:' + path)

    def test_given_changed_unknown_or_unreadable_inputs__when_verifying__then_keep_ineligible_changes_outside_the_admin_lane(self):
        for defect in ('changed', 'unknown', 'duplicate', 'encoding', 'type', 'missing', 'timeout', 'incomplete'):
            raw = None
            if defect == 'unknown': raw = b'backend-app/boot/src/main/java/Unknown.java\0'
            elif defect == 'duplicate': raw = b'backend-app/README.md\0backend-app/README.md\0'
            elif defect == 'encoding': raw = b'backend-app/\xff\0'
            elif defect == 'type': raw = 'not bytes'
            git, hashes = self.synthetic_git(diff=raw, corrupt=defect == 'changed')
            if defect == 'missing': git.side_effect = subprocess.CalledProcessError(1, ['synthetic-git'])
            elif defect == 'timeout': git.side_effect = subprocess.TimeoutExpired('synthetic-git', 8)
            elif defect == 'incomplete': hashes.pop(next(iter(hashes)))
            with self.subTest(defect=defect), patch.object(profile, 'ADMIN_APP_HASHES', hashes):
                self.assertIs(False, profile.verify_admin_member_update_inputs(git_reader=git))

    def test_given_verified_same_repo_pr_or_dev_push__when_selecting__then_require_only_preparation_new_pg_and_cleanup_without_jars(self):
        self.assertEqual('admin-member-update', profile.ADMIN_MEMBER_UPDATE)
        for ctx in (context(), context(event_name='push', head_ref='dev')):
            self.assertEqual([profile.ADMIN_MEMBER_UPDATE], profile.select_profiles(ctx, PATHS))
            self.assertEqual([profile.FULL], profile.select_profiles(dict(ctx, requested_profile=profile.FULL), PATHS))
            plan = profile.make_plan(ctx, PATHS, profile.ADMIN_MEMBER_UPDATE, JAR)
            self.assertEqual([], plan['required_smokes'])
            self.assertEqual(list(profile.ALL_SMOKES), plan['skipped_smokes'])
            self.assertEqual(28, len(plan['skipped_smokes']))
            self.assertEqual(CONTRACTS, plan['required_contracts'])
        for delta, paths in [(dict(admin_member_update_scope_verified=v), PATHS) for v in (False, None, 1, 'true')] + [
                ({'head_repository': 'someone/fork'}, PATHS), ({'repository': 'someone/fork'}, PATHS),
                ({'event_name': 'push', 'head_ref': 'feat/other'}, PATHS), ({'event_name': 'unknown'}, PATHS),
                ({}, []), ({}, ['backend-app/boot/src/main/java/Unknown.java'])]:
            with self.subTest(delta=delta, paths=paths):
                ctx = context(**delta)
                self.assertEqual([profile.FULL], profile.select_profiles(ctx, paths))
                with self.assertRaises(profile.ProfileBlocked):
                    profile.select_profiles(dict(ctx, requested_profile=profile.ADMIN_MEMBER_UPDATE), paths)

    def test_given_actual_admin_lane_steps__when_auditing__then_reject_missing_compile_pg_or_cleanup_masked_failures_and_old_jar_execution(self):
        plan = profile.make_plan(context(), PATHS, profile.ADMIN_MEMBER_UPDATE, JAR)
        result = profile.audit_execution(plan, context(), PATHS, profile.ADMIN_MEMBER_UPDATE, JAR, steps())
        self.assertEqual(profile.ADMIN_MEMBER_UPDATE, result['profile'])
        self.assertEqual([], result['executed_smokes'])
        self.assertEqual(CONTRACTS, result['required_contracts'])
        for key in (*CONTRACTS, 'owned_cleanup'):
            for state in (None, dict(outcome='failure', conclusion='success'),
                          dict(outcome='cancelled', conclusion='cancelled'), dict(outcome='skipped', conclusion='skipped')):
                actual = steps()
                if state is None: del actual[key]
                else: actual[key] = state
                with self.subTest(key=key, state=state), self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan, context(), PATHS, profile.ADMIN_MEMBER_UPDATE, JAR, actual)
        for key in ('jar_f3', 'jar_f31'):
            for state in (dict(outcome='success', conclusion='success'), dict(outcome='skipped', conclusion='success')):
                actual = steps(); actual[key] = state
                with self.subTest(key=key, state=state), self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan, context(), PATHS, profile.ADMIN_MEMBER_UPDATE, JAR, actual)
        forged = copy.deepcopy(plan); forged['required_contracts'] = []
        with self.assertRaises(profile.ProfileBlocked):
            profile.audit_execution(forged, context(), PATHS, profile.ADMIN_MEMBER_UPDATE, JAR, steps())
        for ctx, mode in ((context(tree_sha='c' * 40), profile.ADMIN_MEMBER_UPDATE), (context(), profile.FULL)):
            with self.assertRaises(profile.ProfileBlocked):
                profile.audit_execution(plan, ctx, PATHS, mode, JAR, steps())

    def report(self, directory):
        suite = ET.Element('testsuite', name=profile.ADMIN_REPORT_CLASS, tests='16', failures='0', errors='0', skipped='0', disabled='0')
        for name in profile.ADMIN_REPORT_CASES:
            ET.SubElement(suite, 'testcase', name=name, classname=profile.ADMIN_REPORT_CLASS)
        path = directory / ('TEST-' + profile.ADMIN_REPORT_CLASS + '.xml')
        ET.ElementTree(suite).write(path)
        return path

    def test_given_exact_six_methods_with_sixteen_unique_parameterized_labels__when_verifying__then_accept_only_the_pinned_completed_report(self):
        self.assertEqual((3, 2, 5, 2, 2, 2), tuple(count for _, count in profile.ADMIN_METHOD_CASES))
        self.assertEqual(6, len({name for name, _ in profile.ADMIN_METHOD_CASES}))
        self.assertEqual(16, len(set(profile.ADMIN_REPORT_CASES)))
        self.assertEqual('30fd56aa307fe54d2260bd65bea159df2a4a32aba9c5b7b44b2957f9fa11ef03',
                         hashlib.sha256(json.dumps(sorted(profile.ADMIN_REPORT_CASES), separators=(',', ':')).encode()).hexdigest())
        with tempfile.TemporaryDirectory() as tmp:
            self.report(Path(tmp))
            profile.verify_admin_member_update_report(Path(tmp))

    def test_given_missing_duplicate_failed_skipped_or_wrong_method_labels__when_verifying__then_block_partial_or_stale_pg_reports(self):
        for defect in ('missing', 'extra-file', 'wrong-class', 'wrong-count', 'duplicate-case', 'wrong-method',
                       'failure-attr', 'error-child', 'skip-child', 'disabled-attr', 'bad-status', 'symlink', 'nested-case'):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as tmp:
                directory = Path(tmp); path = self.report(directory); tree = ET.parse(path); suite = tree.getroot()
                if defect == 'missing': path.unlink()
                elif defect == 'extra-file': (directory / 'TEST-extra.xml').write_bytes(path.read_bytes())
                elif defect == 'wrong-class': suite[0].set('classname', 'Other')
                elif defect == 'wrong-count': suite.set('tests', '15')
                elif defect == 'duplicate-case': suite[1].set('name', suite[0].get('name'))
                elif defect == 'wrong-method': suite[0].set('name', 'unreviewed[1]')
                elif defect == 'failure-attr': suite.set('failures', '1')
                elif defect == 'error-child': ET.SubElement(suite[0], 'error')
                elif defect == 'skip-child': ET.SubElement(suite[0], 'skipped')
                elif defect == 'disabled-attr': suite[0].set('disabled', '1')
                elif defect == 'bad-status': suite[0].set('status', 'notrun')
                elif defect == 'nested-case':
                    case = suite[0]; suite.remove(case); ET.SubElement(suite, 'wrapper').append(case)
                elif defect == 'symlink':
                    path.rename(directory / 'real.xml'); path.symlink_to(directory / 'real.xml')
                if defect not in ('missing', 'symlink'): tree.write(path)
                with self.assertRaises(profile.ProfileBlocked):
                    profile.verify_admin_member_update_report(directory)

    def test_given_forged_environment_or_payload_flag__when_loading_runtime__then_recompute_the_actual_admin_input_boolean(self):
        ctx = context()
        payload = {'admin_member_update_scope_verified': True,
                   'pull_request': {'base': {'sha': ctx['base_sha']},
                                    'head': {'repo': {'full_name': ctx['head_repository']},
                                             'ref': ctx['head_ref'], 'sha': ctx['head_sha']}}}
        env = dict(GITHUB_EVENT_PATH='/unused/event.json', GITHUB_REPOSITORY=ctx['repository'],
                   GITHUB_SHA=ctx['source_sha'], GITHUB_RUN_ID=ctx['run_id'], GITHUB_RUN_ATTEMPT=ctx['run_attempt'],
                   PULL_REQUEST_BASE_SHA=ctx['base_sha'], ADMIN_MEMBER_UPDATE_SCOPE_VERIFIED='true')
        for verified in (True, False):
            common = types.SimpleNamespace(validate_event=Mock(return_value='pull_request'),
                        changed_paths=Mock(return_value=PATHS), git=Mock(return_value=(ctx['tree_sha'] + '\n').encode()))
            with self.subTest(verified=verified), patch.dict('sys.modules', {'ci_branch_policy': common}), \
                    patch.dict(profile.os.environ, env, clear=True), \
                    patch.object(profile.Path, 'read_text', return_value=json.dumps(payload)), \
                    patch.object(profile, 'verify_note_expiry_inputs', return_value=False), \
                    patch.object(profile, 'verify_member_management_inputs', return_value=False), \
                    patch.object(profile, 'verify_signup_statistics_inputs', return_value=False), \
                    patch.object(profile, 'verify_auth_sessions_inputs', return_value=False), \
                    patch.object(profile, 'verify_post_statistics_inputs', return_value=False), \
                    patch.object(profile, 'verify_visit_collection_inputs', return_value=False), \
                    patch.object(profile, 'verify_admin_member_update_inputs', return_value=verified) as check:
                actual, _ = profile.runtime_context()
            check.assert_called_once_with()
            self.assertIs(verified, actual['admin_member_update_scope_verified'])
            self.assertNotIn('admin_member_update_scope_verified', profile.make_plan(
                context(), PATHS, profile.ADMIN_MEMBER_UPDATE, JAR)['identity'])


if __name__ == '__main__':
    unittest.main()
