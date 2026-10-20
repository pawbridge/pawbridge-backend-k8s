"""Pure F30 profile boundaries; fake Git and temporary XML, never a PG/JAR run."""

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

import backend_contract_profile as profile


BASE = 'a35ee31ba700093c97fa9c0864d6a8677e5fae78'
CLASS = 'com.pawbridge.backend.compatibility.PostgresqlNoteSendCompatibilityTest'
CONFIG = 'backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/NoteExpiryConfiguration.java'
PATHS = [CONFIG]
JAR = 'a' * 64
CONTRACTS = ['fixture_f4_shape', 'pg_note_expiry']
HASHES = {
    'backend-app/scripts/f4_ci.py': '68b4df20c29cd7d232d33ff1983c51a44c06302642e6d23d30474cd5e7babfa0',
    'backend-app/scripts/test_f4_ci_startup.py': 'a87a12457e2be5cf291c634345563a74b1d1b3cf658ab7f8dca4a5a03b2bca90',
}
METHODS = (
    'givenOwnedExpiryAndFortyEightHourBoundaries__whenBothExpireTwice__thenKeepForeignBaselineAndApplyFkThenOrphanCleanupEvenWhenFirstDeleteIsZero',
    'givenThreeHundredAndOneOwnedCandidatesPerTable__whenOriginalAndCandidateExpireOneBatch__thenCharacterizeOriginalAndRequireCandidateOldestThreeHundred',
    'givenOneLockedOwnedNoteAndOrphanRequest__whenBothExpire__thenSkipThoseRowsAndDeleteTheUnlockedOwnedCandidates',
    'givenOwnedRequestDeletePermissionFailure__whenCandidateHasAlreadyDeletedTheExpiredNote__thenRollbackBothSqlAndRestoreOnlyOwnedPrivileges',
    'givenAnOwnedDeferredDeleteConstraint__whenBothCommitExpiry__thenRejectFromThatConstraintAndRollbackAllEighteenTableChanges',
    'givenAnActiveExpiryWaitingForOwnedMailboxFk__whenActualBootSchedulerContextCloses__thenReturnTheTransactionBeforeWriterPoolDestructionAndNeverReschedule',
)


def context(**changes):
    result = dict(event_name='pull_request', repository=profile.REPOSITORY,
                  head_repository=profile.REPOSITORY, head_ref='feat/modular-note-expiry',
                  base_sha='b' * 40, head_sha='1' * 40, source_sha='2' * 40,
                  tree_sha='3' * 40, run_id='12345', run_attempt='1',
                  note_expiry_scope_verified=True)
    result.update(changes)
    return result


def successful_steps():
    steps = {key: {'outcome': 'skipped', 'conclusion': 'skipped'} for key in profile.ALL_SMOKES}
    for key in (*CONTRACTS, 'owned_cleanup'):
        steps[key] = {'outcome': 'success', 'conclusion': 'success'}
    return steps


def report_suite():
    suite = ET.Element('testsuite', name=CLASS, tests='6', failures='0', errors='0', skipped='0', disabled='0')
    for name in METHODS:
        ET.SubElement(suite, 'testcase', name=name + '()', classname=CLASS)
    return suite


def write_report(directory, suite):
    path = Path(directory) / ('TEST-' + CLASS + '.xml')
    path.write_bytes(ET.tostring(suite, encoding='utf-8', xml_declaration=True))
    return path


class NoteExpiryProfileTest(unittest.TestCase):
    def fake_git(self, diff=b'', corrupt=None):
        blobs = {path: ('synthetic pinned helper ' + str(index)).encode('ascii')
                 for index, path in enumerate(HASHES)}
        if corrupt:
            blobs[corrupt] = b'changed pinned helper'

        def read(*args):
            if args == ('diff', '--name-only', '--no-renames', '-z', BASE, 'HEAD', '--', 'backend-app'):
                return diff
            if len(args) == 2 and args[0] == 'show' and args[1].startswith('HEAD:'):
                return blobs[args[1][5:]]
            raise AssertionError('Unexpected Git request in pure fixture')

        return Mock(side_effect=read)

    def test_given_pinned_app_and_helpers__when_verifying_fake_git__then_allow_only_reviewed_exceptions(self):
        self.assertEqual(BASE, profile.NOTE_BASE)
        self.assertEqual(HASHES, profile.NOTE_HELPER_HASHES)
        self.assertEqual(frozenset({'backend-app/README.md', *HASHES}), profile.NOTE_APP_EXCEPTIONS)
        synthetic_hashes = {path: hashlib.sha256(('synthetic pinned helper ' + str(index)).encode('ascii')).hexdigest()
                            for index, path in enumerate(HASHES)}
        with patch.object(profile, 'NOTE_HELPER_HASHES', synthetic_hashes):
            for diff in (b'', b'backend-app/README.md\0' + b'\0'.join(p.encode() for p in HASHES) + b'\0'):
                with self.subTest(changed=bool(diff)):
                    git = self.fake_git(diff)
                    self.assertIs(True, profile.verify_note_expiry_inputs(git_reader=git))
                    git.assert_any_call('diff', '--name-only', '--no-renames', '-z', BASE, 'HEAD', '--', 'backend-app')
                    for path in HASHES:
                        git.assert_any_call('show', 'HEAD:' + path)
            for git in (self.fake_git(b'backend-app/community/src/main/java/Changed.java\0'),
                        *(self.fake_git(corrupt=path) for path in HASHES),
                        Mock(side_effect=OSError('synthetic Git failure'))):
                with self.subTest(failure=git):
                    self.assertIs(False, profile.verify_note_expiry_inputs(git_reader=git))

    def test_given_verified_same_repo_pr_or_dev_push__when_planning__then_require_two_contracts_and_skip_all_old_jars(self):
        self.assertEqual('note-expiry', profile.NOTE_EXPIRY)
        self.assertEqual(CLASS, profile.NOTE_REPORT_CLASS)
        self.assertEqual(METHODS, tuple(profile.NOTE_METHODS))
        for ctx in (context(), context(event_name='push', head_ref='dev')):
            with self.subTest(event=ctx['event_name']):
                self.assertEqual([profile.NOTE_EXPIRY], profile.select_profiles(ctx, PATHS))
                self.assertEqual([profile.FULL], profile.select_profiles(dict(ctx, requested_profile=profile.FULL), PATHS))
                plan = profile.make_plan(ctx, PATHS, profile.NOTE_EXPIRY, JAR)
                self.assertEqual([], plan['required_smokes'])
                self.assertEqual(list(profile.ALL_SMOKES), plan['skipped_smokes'])
                self.assertEqual(len(profile.ALL_SMOKES), len(plan['skipped_smokes']))
                self.assertEqual(CONTRACTS, plan['required_contracts'])

    def test_given_unverified_fork_nondev_or_shared_change__when_selecting__then_stay_full_and_refuse_explicit_scope(self):
        cases = [(dict(note_expiry_scope_verified=value), PATHS) for value in (False, None, 1, 'true')]
        cases += [({'head_repository': 'someone/fork'}, PATHS),
                  ({'repository': 'someone/fork'}, PATHS),
                  ({'event_name': 'push', 'head_ref': 'feat/other'}, PATHS),
                  ({'event_name': 'unknown'}, PATHS), ({}, []),
                  ({}, PATHS + ['backend-app/boot/src/main/java/com/pawbridge/backend/boot/configuration/ReadSecurityConfiguration.java']),
                  ({}, PATHS + ['backend-app/user/src/main/java/Unknown.java'])]
        for delta, paths in cases:
            with self.subTest(delta=delta, paths=paths):
                ctx = context(**delta)
                self.assertEqual([profile.FULL], profile.select_profiles(ctx, paths))
                with self.assertRaises(profile.ProfileBlocked):
                    profile.select_profiles(dict(ctx, requested_profile=profile.NOTE_EXPIRY), paths)
        ctx = context(); del ctx['note_expiry_scope_verified']
        self.assertEqual([profile.FULL], profile.select_profiles(ctx, PATHS))

    def test_given_note_plan__when_auditing_raw_steps__then_require_actual_contracts_cleanup_and_real_jar_skips(self):
        plan = profile.make_plan(context(), PATHS, profile.NOTE_EXPIRY, JAR)
        result = profile.audit_execution(plan, context(), PATHS, profile.NOTE_EXPIRY, JAR, successful_steps())
        self.assertEqual('passed', result['status'])
        self.assertEqual([], result['executed_smokes'])
        self.assertEqual(list(profile.ALL_SMOKES), result['skipped_smokes'])
        for key in (*CONTRACTS, 'owned_cleanup'):
            self.assertEqual({'outcome': 'success', 'conclusion': 'success'}, result['step_results'][key])
            for raw in (None, {'outcome': 'failure', 'conclusion': 'success'},
                        {'outcome': 'cancelled', 'conclusion': 'cancelled'},
                        {'outcome': 'skipped', 'conclusion': 'skipped'}):
                steps = successful_steps()
                if raw is None:
                    del steps[key]
                else:
                    steps[key] = raw
                with self.subTest(step=key, raw=raw), self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan, context(), PATHS, profile.NOTE_EXPIRY, JAR, steps)
        for key in ('jar_f3', 'jar_f29'):
            steps = successful_steps(); steps[key] = {'outcome': 'success', 'conclusion': 'success'}
            with self.subTest(copied_old_success=key), self.assertRaises(profile.ProfileBlocked):
                profile.audit_execution(plan, context(), PATHS, profile.NOTE_EXPIRY, JAR, steps)
        forged = copy.deepcopy(plan); forged['required_contracts'] = []
        with self.assertRaises(profile.ProfileBlocked):
            profile.audit_execution(forged, context(), PATHS, profile.NOTE_EXPIRY, JAR, successful_steps())
        with self.assertRaises(profile.ProfileBlocked):
            profile.audit_execution(plan, context(tree_sha='c' * 40), PATHS, profile.NOTE_EXPIRY, JAR, successful_steps())

    def test_given_event_claiming_verified_scope__when_loading_runtime_context__then_recompute_instead_of_trusting_input(self):
        ctx = context()
        payload = {'note_expiry_scope_verified': True,
                   'pull_request': {'base': {'sha': ctx['base_sha']},
                                    'head': {'repo': {'full_name': ctx['head_repository']},
                                             'ref': ctx['head_ref'], 'sha': ctx['head_sha']}}}
        env = {'GITHUB_EVENT_NAME': 'pull_request', 'GITHUB_EVENT_PATH': '/unused/event.json',
               'GITHUB_REPOSITORY': ctx['repository'], 'GITHUB_SHA': ctx['source_sha'],
               'GITHUB_REF': 'refs/pull/185/merge', 'GITHUB_RUN_ID': ctx['run_id'],
               'GITHUB_RUN_ATTEMPT': ctx['run_attempt'], 'PULL_REQUEST_BASE_SHA': ctx['base_sha'],
               'NOTE_EXPIRY_SCOPE_VERIFIED': 'true'}
        for verified in (True, False):
            common = types.SimpleNamespace(validate_event=Mock(return_value='pull_request'),
                                           changed_paths=Mock(return_value=PATHS),
                                           git=Mock(return_value=(ctx['tree_sha'] + '\n').encode()))
            with self.subTest(actual_verified=verified), \
                    patch.dict('sys.modules', {'ci_branch_policy': common}), \
                    patch.dict(profile.os.environ, env, clear=True), \
                    patch.object(profile.Path, 'read_text', return_value=json.dumps(payload)), \
                    patch.object(profile, 'verify_note_expiry_inputs', return_value=verified) as verify, \
                    patch.object(profile, 'verify_member_management_inputs', return_value=False), \
                    patch.object(profile, 'verify_signup_statistics_inputs', return_value=False):
                actual, paths = profile.runtime_context()
            verify.assert_called_once_with()
            self.assertIs(verified, actual['note_expiry_scope_verified'])
            self.assertEqual(ctx['source_sha'], actual['source_sha'])
            self.assertEqual(ctx['head_sha'], actual['head_sha'])
            self.assertEqual(PATHS, paths)

    def test_given_exact_six_expiry_cases__when_checking_report__then_accept_only_the_real_named_suite(self):
        with tempfile.TemporaryDirectory() as directory:
            write_report(directory, report_suite())
            profile.verify_note_expiry_report(Path(directory))

    def test_given_miscount_wrong_case_or_hidden_failure__when_checking_report__then_refuse_the_whole_report(self):
        def mutate(suite, kind):
            if kind == 'declared-count': suite.set('tests', '7')
            elif kind == 'suite-class': suite.set('name', 'OtherClass')
            elif kind == 'case-class': suite[0].set('classname', 'OtherClass')
            elif kind == 'case-name': suite[0].set('name', 'unmeasured()')
            elif kind == 'duplicate-case': suite[1].set('name', suite[0].get('name'))
            elif kind == 'missing-case': suite.remove(suite[-1])
            elif kind in ('failures', 'errors', 'skipped', 'disabled'): suite.set(kind, '1')
            elif kind in ('failure', 'error', 'skipped-child'):
                nested = ET.SubElement(suite[0], 'synthetic-wrapper')
                ET.SubElement(nested, 'skipped' if kind == 'skipped-child' else kind)
            elif kind == 'case-status': suite[0].set('status', 'notrun')
            else: raise AssertionError('Unknown report mutation')
        for kind in ('declared-count', 'suite-class', 'case-class', 'case-name', 'duplicate-case', 'missing-case',
                     'failures', 'errors', 'skipped', 'disabled', 'failure', 'error', 'skipped-child', 'case-status'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                suite = report_suite(); mutate(suite, kind); write_report(directory, suite)
                with self.assertRaises(profile.ProfileBlocked):
                    profile.verify_note_expiry_report(Path(directory))

    def test_given_missing_extra_or_malformed_xml__when_checking_report__then_never_accept_partial_evidence(self):
        for kind in ('missing', 'extra', 'malformed'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                if kind != 'missing':
                    path = write_report(directory, report_suite())
                    if kind == 'extra':
                        (Path(directory) / 'TEST-unrelated.xml').write_text('<testsuite/>', encoding='utf-8')
                    else:
                        path.write_text('<testsuite', encoding='utf-8')
                with self.assertRaises(profile.ProfileBlocked):
                    profile.verify_note_expiry_report(Path(directory))


if __name__ == '__main__':
    unittest.main()
