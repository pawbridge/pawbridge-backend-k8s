"""Signup lane pins, raw step outcomes and exact reports; synthetic inputs only."""
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

BASE = 'b59ddfbda475959bd9465e8dde709ad1cd2ca6c7'
PINS = '2997edd1dac707a2e2946a38c5edf6082b42c1e4a33c8caf073a0f041306a172'
PATHS = ['.github/scripts/backend_contract_profile.py']
JAR = 'a' * 64
CONTRACTS = ['fixture_f4_shape', 'pg_signup_statistics']


def context(**updates):
    result = dict(event_name='pull_request', repository=profile.REPOSITORY,
                  head_repository=profile.REPOSITORY, head_ref='feat/signup-statistics',
                  base_sha=BASE, head_sha='1' * 40, source_sha='2' * 40,
                  tree_sha='3' * 40, run_id='12345', run_attempt='1',
                  note_expiry_scope_verified=False, member_management_scope_verified=False,
                  signup_statistics_scope_verified=True)
    result.update(updates)
    return result


def steps():
    result = {key: dict(outcome='skipped', conclusion='skipped') for key in profile.ALL_SMOKES}
    for key in (*CONTRACTS, 'owned_cleanup'):
        result[key] = dict(outcome='success', conclusion='success')
    return result


class SignupStatisticsProfileTest(unittest.TestCase):
    def synthetic_git(self, *, diff=None, corrupt=False):
        blobs = {path: ('synthetic signup input ' + str(i)).encode()
                 for i, path in enumerate(profile.SIGNUP_APP_HASHES)}
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

    def test_given_frozen_eighteen_inputs__when_verifying__then_keep_literal_pins_and_read_git_objects_without_checkout_files(self):
        self.assertEqual(BASE, profile.SIGNUP_BASE)
        self.assertEqual(18, len(profile.SIGNUP_APP_HASHES))
        self.assertEqual(PINS, hashlib.sha256(json.dumps(sorted(profile.SIGNUP_APP_HASHES.items()),
                                                        separators=(',', ':')).encode()).hexdigest())
        self.assertEqual(frozenset(profile.SIGNUP_APP_HASHES) | {'backend-app/README.md'}, profile.SIGNUP_APP_EXCEPTIONS)
        git, hashes = self.synthetic_git()
        with patch.object(profile, 'SIGNUP_APP_HASHES', hashes):
            self.assertIs(True, profile.verify_signup_statistics_inputs(git_reader=git))
        self.assertEqual(19, git.call_count)
        for path in hashes:
            git.assert_any_call('show', 'HEAD:' + path)

    def test_given_changed_unknown_or_unreadable_inputs__when_verifying__then_refuse_without_a_scope_fallback(self):
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
            with self.subTest(defect=defect), patch.object(profile, 'SIGNUP_APP_HASHES', hashes):
                self.assertIs(False, profile.verify_signup_statistics_inputs(git_reader=git))

    def test_given_same_repo_verified_pr_or_dev_push__when_selecting__then_require_no_jar_and_exact_statistics_contracts(self):
        self.assertEqual('signup-statistics', profile.SIGNUP_STATISTICS)
        for ctx in (context(), context(event_name='push', head_ref='dev')):
            self.assertEqual([profile.SIGNUP_STATISTICS], profile.select_profiles(ctx, PATHS))
            self.assertEqual([profile.FULL], profile.select_profiles(dict(ctx, requested_profile=profile.FULL), PATHS))
            plan = profile.make_plan(ctx, PATHS, profile.SIGNUP_STATISTICS, JAR)
            self.assertEqual([], plan['required_smokes'])
            self.assertEqual(list(profile.ALL_SMOKES), plan['skipped_smokes'])
            self.assertEqual(28, len(plan['skipped_smokes']))
            self.assertEqual(CONTRACTS, plan['required_contracts'])
        for delta, paths in [(dict(signup_statistics_scope_verified=v), PATHS) for v in (False, None, 1, 'true')] + [
                ({'head_repository': 'someone/fork'}, PATHS), ({'repository': 'someone/fork'}, PATHS),
                ({'event_name': 'push', 'head_ref': 'feat/other'}, PATHS), ({'event_name': 'unknown'}, PATHS),
                ({}, []), ({}, ['backend-app/boot/src/main/java/Unknown.java'])]:
            with self.subTest(delta=delta, paths=paths):
                ctx = context(**delta)
                self.assertEqual([profile.FULL], profile.select_profiles(ctx, paths))
                with self.assertRaises(profile.ProfileBlocked):
                    profile.select_profiles(dict(ctx, requested_profile=profile.SIGNUP_STATISTICS), paths)

    def test_given_actual_required_steps__when_auditing__then_require_both_raw_success_and_all_real_skips_without_full_promotion(self):
        plan = profile.make_plan(context(), PATHS, profile.SIGNUP_STATISTICS, JAR)
        result = profile.audit_execution(plan, context(), PATHS, profile.SIGNUP_STATISTICS, JAR, steps())
        self.assertEqual(profile.SIGNUP_STATISTICS, result['profile'])
        self.assertEqual([], result['executed_smokes'])
        self.assertEqual(CONTRACTS, result['required_contracts'])
        for key in (*CONTRACTS, 'owned_cleanup'):
            for state in (None, dict(outcome='failure', conclusion='success'),
                          dict(outcome='cancelled', conclusion='cancelled'), dict(outcome='skipped', conclusion='skipped')):
                actual = steps()
                if state is None: del actual[key]
                else: actual[key] = state
                with self.subTest(key=key, state=state), self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan, context(), PATHS, profile.SIGNUP_STATISTICS, JAR, actual)
        for key in ('jar_f3', 'jar_f31'):
            for state in (dict(outcome='success', conclusion='success'), dict(outcome='skipped', conclusion='success')):
                actual = steps(); actual[key] = state
                with self.subTest(key=key, state=state), self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan, context(), PATHS, profile.SIGNUP_STATISTICS, JAR, actual)
        forged = copy.deepcopy(plan); forged['required_contracts'] = []
        with self.assertRaises(profile.ProfileBlocked):
            profile.audit_execution(forged, context(), PATHS, profile.SIGNUP_STATISTICS, JAR, steps())
        for ctx, mode in ((context(tree_sha='c' * 40), profile.SIGNUP_STATISTICS), (context(), profile.FULL)):
            with self.assertRaises(profile.ProfileBlocked):
                profile.audit_execution(plan, ctx, PATHS, mode, JAR, steps())

    def report(self, directory, methods):
        directory.mkdir()
        suite = ET.Element('testsuite', name=profile.SIGNUP_REPORT_CLASS, tests=str(len(methods)),
                           failures='0', errors='0', skipped='0', disabled='0')
        for name in methods:
            ET.SubElement(suite, 'testcase', name=name + '()', classname=profile.SIGNUP_REPORT_CLASS)
        path = directory / ('TEST-' + profile.SIGNUP_REPORT_CLASS + '.xml')
        ET.ElementTree(suite).write(path)
        return path

    def test_given_exact_three_utc_and_one_boundary_seoul_reports__when_verifying__then_accept_only_executed_cases(self):
        with tempfile.TemporaryDirectory() as tmp:
            utc, seoul = Path(tmp) / 'utc', Path(tmp) / 'seoul'
            self.report(utc, profile.SIGNUP_METHODS); self.report(seoul, profile.SIGNUP_METHODS[:1])
            profile.verify_signup_statistics_report(utc, seoul)

    def test_given_missing_extra_skipped_failed_or_wrong_case_reports__when_verifying__then_block_instead_of_counting_partial_pg(self):
        for defect in ('missing', 'extra-file', 'wrong-class', 'wrong-count', 'duplicate-case', 'wrong-seoul',
                       'failure-attr', 'error-child', 'skip-child', 'disabled-attr', 'bad-status', 'symlink'):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as tmp:
                utc, seoul = Path(tmp) / 'utc', Path(tmp) / 'seoul'
                path = self.report(utc, profile.SIGNUP_METHODS); s_path = self.report(seoul, profile.SIGNUP_METHODS[:1])
                tree = ET.parse(path); suite = tree.getroot()
                if defect == 'missing': path.unlink()
                elif defect == 'extra-file': (utc / 'TEST-extra.xml').write_bytes(path.read_bytes())
                elif defect == 'wrong-class': suite[0].set('classname', 'Other')
                elif defect == 'wrong-count': suite.set('tests', '2')
                elif defect == 'duplicate-case': suite[1].set('name', suite[0].get('name'))
                elif defect == 'wrong-seoul': s_path.write_bytes(path.read_bytes())
                elif defect == 'failure-attr': suite.set('failures', '1')
                elif defect == 'error-child': ET.SubElement(suite[0], 'error')
                elif defect == 'skip-child': ET.SubElement(suite[0], 'skipped')
                elif defect == 'disabled-attr': suite[0].set('disabled', '1')
                elif defect == 'bad-status': suite[0].set('status', 'notrun')
                elif defect == 'symlink':
                    path.rename(utc / 'real.xml'); path.symlink_to(utc / 'real.xml')
                if defect not in ('missing', 'symlink'): tree.write(path)
                with self.assertRaises(profile.ProfileBlocked):
                    profile.verify_signup_statistics_report(utc, seoul)

    def test_given_forged_environment_or_payload_flag__when_loading_runtime__then_recompute_the_actual_input_boolean(self):
        ctx = context()
        payload = {'signup_statistics_scope_verified': True,
                   'pull_request': {'base': {'sha': ctx['base_sha']},
                                    'head': {'repo': {'full_name': ctx['head_repository']},
                                             'ref': ctx['head_ref'], 'sha': ctx['head_sha']}}}
        env = dict(GITHUB_EVENT_PATH='/unused/event.json', GITHUB_REPOSITORY=ctx['repository'],
                   GITHUB_SHA=ctx['source_sha'], GITHUB_RUN_ID=ctx['run_id'], GITHUB_RUN_ATTEMPT=ctx['run_attempt'],
                   PULL_REQUEST_BASE_SHA=ctx['base_sha'], SIGNUP_STATISTICS_SCOPE_VERIFIED='true')
        for verified in (True, False):
            common = types.SimpleNamespace(validate_event=Mock(return_value='pull_request'),
                        changed_paths=Mock(return_value=PATHS), git=Mock(return_value=(ctx['tree_sha'] + '\n').encode()))
            with self.subTest(verified=verified), patch.dict('sys.modules', {'ci_branch_policy': common}), \
                    patch.dict(profile.os.environ, env, clear=True), \
                    patch.object(profile.Path, 'read_text', return_value=json.dumps(payload)), \
                    patch.object(profile, 'verify_note_expiry_inputs', return_value=False), \
                    patch.object(profile, 'verify_member_management_inputs', return_value=False), \
                    patch.object(profile, 'verify_signup_statistics_inputs', return_value=verified) as check:
                actual, _ = profile.runtime_context()
            check.assert_called_once_with()
            self.assertIs(verified, actual['signup_statistics_scope_verified'])
            self.assertNotIn('signup_statistics_scope_verified', profile.make_plan(
                context(), PATHS, profile.SIGNUP_STATISTICS, JAR)['identity'])


if __name__ == '__main__':
    unittest.main()
