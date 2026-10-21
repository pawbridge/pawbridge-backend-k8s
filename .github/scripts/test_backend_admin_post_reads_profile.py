"""Fixed User Community cohort contracts using fake Git, raw step dictionaries and owned temporary XML only."""
import copy
from contextlib import ExitStack
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import types
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

import backend_contract_profile as profile
import backend_postmerge_proof as proof
import test_backend_app_ci_policy as policy_tests

# Root replaces this literal together with POST_READS_BASE only after the PostStats head is fixed.
BASE = '990e7b3459dbbefb7c2bf877b6a2cf0891d54e68'
SYNTHETIC_BASE = 'a' * 40
PATHS = ['.github/scripts/backend_contract_profile.py']
JAR = 'a' * 64
CONTRACTS = ('fixture_f4_shape','compile_post_reads','pg_post_reads')
SKIPPED = ('pg_note_expiry', 'compile_admin_update', 'compile_visit_collection', 'compile_auth_sessions', 'fixture_member_cleanup', 'pg_member_cleanup', 'fixture_admin_update', 'pg_admin_update', 'fixture_auth_sessions', 'pg_auth_sessions', 'restore_auth_sessions', 'fixture_visit_collection', 'fixture_admin_visit_collection', 'pg_visit_collection', 'pg_signup_statistics', 'fixture_post_unrelated_f8', 'fixture_post_unrelated_f9', 'fixture_post_unrelated_f10', 'fixture_post_unrelated_f11', 'fixture_post_unrelated_f12', 'fixture_post_unrelated_f13', 'fixture_post_unrelated_f14', 'fixture_post_unrelated_f15', 'fixture_post_unrelated_f16', 'fixture_post_unrelated_f17', 'fixture_post_unrelated_f18', 'fixture_post_unrelated_f19', 'fixture_post_unrelated_f20', 'fixture_post_unrelated_f21', 'fixture_post_unrelated_f22', 'fixture_post_unrelated_f23', 'fixture_post_unrelated_f24', 'fixture_post_unrelated_f25', 'fixture_post_unrelated_f26', 'fixture_post_unrelated_f27', 'fixture_post_unrelated_f28', 'fixture_post_unrelated_f29', 'compile_post_statistics', 'pg_post_statistics')

def context(**updates):
    value = dict(event_name='pull_request', repository=profile.REPOSITORY,
                 head_repository=profile.REPOSITORY, head_ref='feat/user-community-integration', base_sha=SYNTHETIC_BASE,
                 head_sha='1' * 40, source_sha='2' * 40, tree_sha='3' * 40,
                 run_id='12345', run_attempt='1', post_reads_scope_verified=True)
    value.update(updates)
    return value


def steps():
    value = {key: dict(outcome='skipped', conclusion='skipped') for key in (*profile.ALL_SMOKES, *SKIPPED)}
    for key in (*CONTRACTS, 'owned_cleanup'):
        value[key] = dict(outcome='success', conclusion='success')
    return value


class AdminPostReadsProfileTest(unittest.TestCase):
    def test_given_the_scoped_profile_cli__when_planning_and_auditing__then_reach_real_main_and_reject_unknown_names_before_context(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            jar = root / 'synthetic.jar'
            jar.write_bytes(b'bounded synthetic candidate for CLI parsing')
            plan = root / 'contract-plan.txt'
            execution = root / 'contract-execution.txt'
            common = ['--profile', profile.POST_READS, '--jar', str(jar), '--plan', str(plan)]
            with patch.object(profile, 'runtime_context', return_value=(context(), PATHS)) as runtime, \
                    patch.dict(profile.os.environ, {'CONTRACT_STEPS_JSON': json.dumps(steps())}):
                self.assertEqual(0, profile.main(['--mode', 'plan', *common]))
                self.assertEqual('planned', json.loads(plan.read_text())['status'])
                self.assertEqual(0, profile.main(['--mode', 'audit', *common, '--execution', str(execution)]))
                result = json.loads(execution.read_text())
                self.assertEqual('passed', result['status'])
                self.assertEqual(profile.POST_READS, result['profile'])
                self.assertEqual(2, runtime.call_count)
                with patch('sys.stderr'), self.assertRaises(SystemExit) as unknown:
                    profile.main(['--mode', 'plan', '--profile', 'not-a-real-profile', '--jar', str(jar), '--plan', str(plan)])
                self.assertEqual(2, unknown.exception.code)
                self.assertEqual(2, runtime.call_count)


    def synthetic_git(self, diff=None, changed=False, workflow=None):
        blobs = {path: ('fixed post reads input ' + str(i)).encode() for i, path in enumerate(profile.POST_READS_APP_HASHES)}
        hashes = {path: hashlib.sha256(raw).hexdigest() for path, raw in blobs.items()}
        if workflow is None:
            workflow = (Path(__file__).resolve().parents[1] / 'workflows/backend-app-check.yml').read_bytes()
        if changed:
            blobs[next(iter(blobs))] += b'changed'
        def read(*args):
            if args == ('diff', '--name-only', '--no-renames', '-z', SYNTHETIC_BASE, 'HEAD', '--', 'backend-app'):
                return b'\0'.join(path.encode() for path in (*blobs, 'backend-app/README.md')) + b'\0' if diff is None else diff
            if len(args) == 2 and args[0] == 'show':
                if args[1] == 'HEAD:.github/workflows/backend-app-check.yml':
                    if isinstance(workflow, Exception):
                        raise workflow
                    return workflow
                return blobs[args[1].removeprefix('HEAD:')]
            raise AssertionError('Unexpected synthetic Git operation')
        return Mock(side_effect=read), hashes

    def test_given_reviewed_or_unknown_workflow_bytes__when_loading_context_and_running_main__then_scope_only_known_bytes_and_keep_full_fallback(self):
        workflow = (Path(__file__).resolve().parents[1] / 'workflows/backend-app-check.yml').read_bytes()
        ctx = context()
        payload = {'pull_request': {'base': {'sha': ctx['base_sha']}, 'head': {
            'repo': {'full_name': profile.REPOSITORY}, 'ref': ctx['head_ref'], 'sha': ctx['head_sha']}}}
        env = dict(GITHUB_EVENT_PATH='/unused/event.json', GITHUB_REPOSITORY=profile.REPOSITORY,
                   GITHUB_SHA=ctx['source_sha'], GITHUB_RUN_ID=ctx['run_id'], GITHUB_RUN_ATTEMPT='1',
                   PULL_REQUEST_BASE_SHA=ctx['base_sha'])
        for defect in ('known', 'changed', 'changed-full', 'missing', 'wrong-type', 'other-ci', 'explicit-full'):
            raw = workflow
            if defect == 'changed': raw += b'\n# unreviewed workflow bytes\n'
            elif defect == 'changed-full':
                raw = workflow.replace(b"matrix.profile == 'full'", b"matrix.profile != 'full'", 1)
                self.assertNotEqual(workflow, raw)
            elif defect == 'missing': raw = subprocess.CalledProcessError(1, ['synthetic-git'])
            elif defect == 'wrong-type': raw = 'not Git object bytes'
            known = defect in ('known', 'other-ci', 'explicit-full')
            paths = ['.github/workflows/other-service.yml'] if defect == 'other-ci' else ['.github/workflows/backend-app-check.yml']
            git, hashes = self.synthetic_git(workflow=raw)
            common = types.SimpleNamespace(validate_event=Mock(return_value='pull_request'),
                    changed_paths=Mock(return_value=paths), git=Mock(return_value=(ctx['tree_sha']+'\n').encode()))
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
                root = Path(directory).resolve()
                jar = root / 'synthetic.jar'; jar.write_bytes(b'never executed')
                plan = root / 'contract-plan.txt'
                stack.enter_context(patch.dict('sys.modules', {'ci_branch_policy': common}))
                stack.enter_context(patch.dict(profile.os.environ, env, clear=True))
                stack.enter_context(patch.object(profile.Path, 'read_text', return_value=json.dumps(payload)))
                stack.enter_context(patch.object(profile, 'POST_READS_APP_HASHES', hashes))
                stack.enter_context(patch.object(profile, 'POST_READS_BASE', SYNTHETIC_BASE))
                stack.enter_context(patch.object(profile.subprocess, 'run', side_effect=lambda command, **kw:
                    types.SimpleNamespace(stdout=git(*command[1:]))))
                for name in ('verify_note_expiry_inputs', 'verify_member_management_inputs', 'verify_signup_statistics_inputs',
                             'verify_admin_member_update_inputs', 'verify_visit_collection_inputs', 'verify_auth_sessions_inputs',
                             'verify_post_statistics_inputs'):
                    stack.enter_context(patch.object(profile, name, return_value=False))
                actual, actual_paths = profile.runtime_context()
                self.assertIs(known, actual['post_reads_scope_verified'])
                selection = dict(actual, requested_profile=profile.FULL) if defect == 'explicit-full' else actual
                wanted = profile.POST_READS if defect == 'known' else profile.FULL
                self.assertEqual([wanted], profile.select_profiles(selection, actual_paths))
                args = ['--mode', 'plan', '--jar', str(jar), '--plan', str(plan)]
                self.assertEqual(0, profile.main([*args, '--profile', wanted]))
                result = json.loads(plan.read_bytes())
                self.assertEqual(wanted, result['profile'])
                self.assertEqual(list(profile.ALL_SMOKES) if wanted == profile.FULL else [], result['required_smokes'])
                if wanted == profile.POST_READS:
                    self.assertEqual(list(CONTRACTS), result['required_contracts'])
                    self.assertEqual(list(SKIPPED), result['skipped_contracts'])
                if wanted == profile.FULL and defect != 'explicit-full':
                    blocked = root / 'blocked'; blocked.mkdir()
                    with patch('sys.stderr'):
                        self.assertEqual(1, profile.main(['--mode', 'plan', '--profile', profile.POST_READS,
                            '--jar', str(jar), '--plan', str(blocked / 'contract-plan.txt')]))
                        self.assertFalse((blocked / 'contract-plan.txt').exists())

    def test_given_frozen_cohort_inputs_and_readme_path_only__when_verifying__then_use_only_fixed_git_object_bytes(self):
        self.assertEqual(BASE, profile.POST_READS_BASE)
        self.assertEqual(528, len(profile.POST_READS_APP_HASHES))
        self.assertEqual('7229e2f7e0c781e319f4311b8d8fd16ab32c978b872ed558b43a991fb56c0540', hashlib.sha256(json.dumps(sorted(profile.POST_READS_APP_HASHES.items()), separators=(',', ':')).encode()).hexdigest())
        self.assertEqual(frozenset(profile.POST_READS_APP_HASHES) | {'backend-app/README.md'}, profile.POST_READS_APP_EXCEPTIONS)
        self.assertNotIn('backend-app/README.md', profile.POST_READS_APP_HASHES)
        self.assertEqual(profile.POST_READS_WORKFLOW_SHA256, hashlib.sha256(
            (Path(__file__).resolve().parents[1] / 'workflows/backend-app-check.yml').read_bytes()).hexdigest())
        git, hashes = self.synthetic_git()
        with patch.object(profile, 'POST_READS_APP_HASHES', hashes), patch.object(profile,'POST_READS_BASE',SYNTHETIC_BASE):
            self.assertIs(True, profile.verify_post_reads_inputs(git_reader=git))
        self.assertEqual(len(profile.POST_READS_APP_HASHES) + 2, git.call_count)
        git.assert_any_call('show', 'HEAD:.github/workflows/backend-app-check.yml')
        for path in hashes:
            git.assert_any_call('show', 'HEAD:' + path)

    def test_given_identical_reviewed_guard_inputs__when_only_the_fixed_cohort_is_selected__then_reference_only_selftests(self):
        self.assertEqual(39481336774, profile.ARCHITECTURE_SELFTEST_REFERENCE_RUN)
        self.assertEqual('217cb7a3d9a61e6d8de5eacc893c97a762b1d193', profile.ARCHITECTURE_SELFTEST_REFERENCE_HEAD)
        self.assertEqual(16, len(profile.ARCHITECTURE_SELFTEST_INPUT_HASHES))
        blobs = {path: ('fixed guard input ' + str(i)).encode()
                 for i, path in enumerate(profile.ARCHITECTURE_SELFTEST_INPUT_HASHES)}
        hashes = {path: hashlib.sha256(raw).hexdigest() for path, raw in blobs.items()}
        git = Mock(side_effect=lambda *args: blobs[args[1].removeprefix('HEAD:')])
        with patch.object(profile, 'ARCHITECTURE_SELFTEST_INPUT_HASHES', hashes), \
                patch.object(profile, 'verify_post_reads_inputs', return_value=True) as cohort:
            self.assertIs(True, profile.verify_architecture_selftest_inputs([profile.POST_READS], git))
            cohort.assert_called_once_with(git_reader=git)
        self.assertEqual(16, git.call_count)
        for selected in ([profile.FULL], [profile.PILOT], [profile.NOTE_EXPIRY], [profile.MEMBER_MANAGEMENT],
                         [profile.SIGNUP_STATISTICS], [profile.ADMIN_MEMBER_UPDATE], [profile.VISIT_COLLECTION],
                         [profile.AUTH_SESSIONS], [profile.POST_STATISTICS], [profile.POST_READS, profile.FULL],
                         [], None, profile.POST_READS, ('user-community-integration',), [True], ['unknown']):
            git = Mock(side_effect=AssertionError('Other profiles must not read guard inputs'))
            with self.subTest(selected=selected):
                self.assertIs(False, profile.verify_architecture_selftest_inputs(selected, git))
            git.assert_not_called()

    def test_given_changed_missing_unknown_or_unverified_guard_inputs__when_deciding__then_execute_current_selftests(self):
        paths = tuple(profile.ARCHITECTURE_SELFTEST_INPUT_HASHES)
        blobs = {path: ('fixed guard input ' + str(i)).encode() for i, path in enumerate(paths)}
        hashes = {path: hashlib.sha256(raw).hexdigest() for path, raw in blobs.items()}
        defects = [('changed', path) for path in paths] + [(kind, paths[0])
                   for kind in ('missing', 'timeout', 'wrong-type', 'partial-map', 'extra-map', 'cohort-unverified')]
        for defect, target in defects:
            actual = dict(blobs); declared = dict(hashes)
            if defect == 'changed': actual[target] += b'changed'
            elif defect == 'wrong-type': actual[target] = 'not Git bytes'
            elif defect == 'partial-map': declared.pop(target)
            elif defect == 'extra-map': declared['backend-app/unknown.gradle'] = 'a' * 64
            def read(*args):
                if defect == 'missing': raise subprocess.CalledProcessError(1, ['synthetic-git'])
                if defect == 'timeout': raise subprocess.TimeoutExpired('synthetic-git', 8)
                return actual[args[1].removeprefix('HEAD:')]
            git = Mock(side_effect=read)
            with self.subTest(defect=defect, path=target), \
                    patch.object(profile, 'ARCHITECTURE_SELFTEST_INPUT_HASHES', declared), \
                    patch.object(profile, 'verify_post_reads_inputs', return_value=defect != 'cohort-unverified'):
                self.assertIs(False, profile.verify_architecture_selftest_inputs([profile.POST_READS], git))
            if defect in ('partial-map', 'extra-map', 'cohort-unverified'): git.assert_not_called()

    def test_given_package_selftest_reference__when_reading_the_workflow__then_exclude_only_the_guard_task_and_preserve_checks(self):
        workflow = (Path(__file__).resolve().parents[1] / 'workflows/backend-app-check.yml').read_text()
        package = workflow.split('\n  package:\n', 1)[1].split('\n  check:\n', 1)[0]
        self.assertIn('SELECTED_CONTRACT_PROFILES: ${{ needs.selection.outputs.profiles }}', package)
        self.assertIn('.github/scripts/backend_contract_profile.py', package)
        self.assertIn('reuse = verify_architecture_selftest_inputs(profiles)', package)
        self.assertEqual(1, package.count('architecture_args=(-x :boot:architectureTest)'))
        self.assertEqual(1, package.count('-x '))
        self.assertIn('architecture_args=()', package)
        self.assertIn('projects check :boot:bootJar --no-daemon --max-workers=1 -Dorg.gradle.parallel=false "${architecture_args[@]}"', package)
        self.assertIn('reference-previous-selftests', package)
        self.assertIn('execute-current-selftests', package)
        self.assertIn('backend-app/build/ci/architecture-selftests.txt', package)
        self.assertNotIn('cache-enabled', package)

    def test_given_the_single_gateway_race_fix__when_selecting__then_pin_it_and_refuse_siblings_or_changed_bytes(self):
        path = 'api-gateway/src/test/java/com/pawbridge/apigateway/filter/LostSearchLimitsTest.java'
        self.assertEqual(profile.POST_READS_APP_HASHES[path], hashlib.sha256(
            (Path(__file__).resolve().parents[2] / path).read_bytes()).hexdigest())
        self.assertEqual([profile.POST_READS], profile.select_profiles(context(), [path]))
        for unknown in (path + '.extra', path.replace('LostSearchLimitsTest', 'AnotherTest'),
                        'api-gateway/src/main/java/com/pawbridge/apigateway/filter/LostSearchLimitsGatewayFilterFactory.java'):
            self.assertEqual([profile.FULL], profile.select_profiles(context(), [unknown]))
        git, hashes = self.synthetic_git()
        read = git.side_effect
        git.side_effect = lambda *args: read(*args) + b'changed' if args == ('show', 'HEAD:' + path) else read(*args)
        with patch.object(profile, 'POST_READS_APP_HASHES', hashes), patch.object(profile, 'POST_READS_BASE', SYNTHETIC_BASE):
            self.assertIs(False, profile.verify_post_reads_inputs(git_reader=git))

    def test_given_an_unconfirmed_or_invalid_future_base__when_verifying__then_refuse_before_any_git_read(self):
        for base in ('0'*40,'not-a-commit'):
            git=Mock(side_effect=AssertionError('An unconfirmed base must not trigger Git'))
            with self.subTest(base=base),patch.object(profile,'POST_READS_BASE',base):
                self.assertIs(False,profile.verify_post_reads_inputs(git_reader=git))
            git.assert_not_called()

    def test_given_unknown_duplicate_changed_or_unreadable_inputs__when_verifying__then_refuse_the_scoped_lane(self):
        for defect in ('changed', 'unknown', 'duplicate', 'encoding', 'type', 'missing', 'timeout', 'partial'):
            raw = {'unknown': b'backend-app/user/src/main/java/Unreviewed.java\0',
                   'duplicate': b'backend-app/README.md\0backend-app/README.md\0',
                   'encoding': b'backend-app/\xff\0', 'type': 'not bytes'}.get(defect)
            git, hashes = self.synthetic_git(raw, changed=defect == 'changed')
            if defect == 'missing': git.side_effect = subprocess.CalledProcessError(1, ['synthetic-git'])
            elif defect == 'timeout': git.side_effect = subprocess.TimeoutExpired('synthetic-git', 8)
            elif defect == 'partial': hashes.pop(next(iter(hashes)))
            with self.subTest(defect=defect), patch.object(profile, 'POST_READS_APP_HASHES', hashes), patch.object(profile,'POST_READS_BASE',SYNTHETIC_BASE):
                self.assertIs(False, profile.verify_post_reads_inputs(git_reader=git))

    def test_given_verified_same_repo_pr_or_dev_push__when_selecting__then_require_new_pg_and_explicitly_skip_unrelated_contracts(self):
        routing = ['.github/scripts/ci_branch_policy.py', '.github/scripts/test_ci_branch_policy.py']
        for ctx in (context(), context(event_name='push', head_ref='dev')):
            self.assertEqual([profile.POST_READS], profile.select_profiles(ctx, PATHS))
            for paths in ([routing[0]], [routing[1]], routing + PATHS + [
                    '.github/scripts/test_backend_admin_post_reads_profile.py']):
                with self.subTest(event=ctx['event_name'], paths=paths):
                    self.assertEqual([profile.POST_READS], profile.select_profiles(ctx, paths))
            self.assertEqual([profile.FULL], profile.select_profiles(dict(ctx, requested_profile=profile.FULL), PATHS))
            plan = profile.make_plan(ctx, PATHS, profile.POST_READS, JAR)
            self.assertEqual([], plan['required_smokes'])
            self.assertEqual(list(profile.ALL_SMOKES), plan['skipped_smokes'])
            self.assertEqual(28, len(plan['skipped_smokes']))
            self.assertEqual(list(CONTRACTS), plan['required_contracts'])
            self.assertEqual(list(SKIPPED), plan['skipped_contracts'])
        for updates, paths in [(dict(post_reads_scope_verified=v), PATHS) for v in (False,None,1,'true')] + [
                ({'head_repository':'fork/other'},PATHS), ({'repository':'fork/other'},PATHS),
                ({'event_name':'push','head_ref':'feat/other'},PATHS), ({'event_name':'unknown'},PATHS),
                ({},[]), ({},['backend-app/user/src/main/java/Unknown.java']),
                ({},['.github/scripts/ci_branch_policy.py.extra']),
                ({},['.github/scripts/test_ci_branch_policy.py.extra']),
                ({},routing + ['.github/scripts/unreviewed.py'])]:
            with self.subTest(updates=updates, paths=paths):
                ctx=context(**updates)
                self.assertEqual([profile.FULL],profile.select_profiles(ctx,paths))
                with self.assertRaises(profile.ProfileBlocked):
                    profile.select_profiles(dict(ctx,requested_profile=profile.POST_READS),paths)
        with self.assertRaises(profile.ProfileBlocked):
            profile.select_profiles(dict(context(),requested_profile='user-community-integration-extra'),PATHS)

    def test_given_raw_post_reads_steps__when_auditing__then_block_false_skips_masked_failures_unrelated_execution_and_missing_cleanup(self):
        plan=profile.make_plan(context(),PATHS,profile.POST_READS,JAR)
        result=profile.audit_execution(plan,context(),PATHS,profile.POST_READS,JAR,steps())
        self.assertEqual([],result['executed_smokes']);self.assertEqual(list(SKIPPED),result['skipped_contracts'])
        for key in (*CONTRACTS,'owned_cleanup'):
            for bad in (None,dict(outcome='skipped',conclusion='skipped'),dict(outcome='failure',conclusion='success')):
                actual=steps()
                if bad is None:del actual[key]
                else:actual[key]=bad
                with self.subTest(required=key,bad=bad),self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan,context(),PATHS,profile.POST_READS,JAR,actual)
        for key in (*SKIPPED,*profile.ALL_SMOKES):
            for bad in (None,dict(outcome='success',conclusion='success'),dict(outcome='skipped',conclusion='success')):
                actual=steps()
                if bad is None:del actual[key]
                else:actual[key]=bad
                with self.subTest(skipped=key,bad=bad),self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan,context(),PATHS,profile.POST_READS,JAR,actual)
        for defect in ('plan','identity','unknown-jar'):
            actual=steps();saved=copy.deepcopy(plan);ctx=context()
            if defect=='plan':saved['skipped_contracts']=[]
            elif defect=='identity':ctx['tree_sha']='b'*40
            else:actual['jar_f30']=dict(outcome='skipped',conclusion='skipped')
            with self.subTest(defect=defect),self.assertRaises(profile.ProfileBlocked):
                profile.audit_execution(saved,ctx,PATHS,profile.POST_READS,JAR,actual)

    def test_given_full_execution__when_auditing__then_keep_all_jars_and_require_admin_parent_plus_visit_pg(self):
        ctx=context();plan=profile.make_plan(ctx,PATHS,profile.FULL,JAR)
        self.assertEqual(list(profile.ALL_SMOKES),plan['required_smokes'])
        self.assertEqual(list(profile.POST_READS_FULL_CONTRACTS+profile.POST_STATISTICS_FULL_CONTRACTS+profile.AUTH_FULL_CONTRACTS+profile.VISIT_FULL_CONTRACTS),plan['required_contracts'])
        actual={key:dict(outcome='success',conclusion='success') for key in (*profile.ALL_SMOKES,*plan['required_contracts'],'owned_cleanup')}
        self.assertEqual('passed',profile.audit_execution(plan,ctx,PATHS,profile.FULL,JAR,actual)['status'])
        for key in (*plan['required_contracts'],'owned_cleanup'):
            bad=copy.deepcopy(actual);bad[key]=dict(outcome='skipped',conclusion='skipped')
            with self.subTest(key=key),self.assertRaises(profile.ProfileBlocked):
                profile.audit_execution(plan,ctx,PATHS,profile.FULL,JAR,bad)

    def reports(self, root):
        folder=root/'utc';folder.mkdir()
        suite=ET.Element('testsuite',name=profile.POST_READS_REPORT_CLASS,tests=str(len(profile.POST_READS_REPORT_CASES)),failures='0',errors='0',skipped='0')
        for name in profile.POST_READS_REPORT_CASES:ET.SubElement(suite,'testcase',name=name,classname=profile.POST_READS_REPORT_CLASS)
        path=folder/('TEST-'+profile.POST_READS_REPORT_CLASS+'.xml');ET.ElementTree(suite).write(path)
        return path

    def test_given_all_frozen_methods_and_labels__when_verifying__then_require_every_unique_utc_case(self):
        self.assertEqual((3, 4, 4),tuple(count for _,count in profile.POST_READS_METHOD_CASES))
        self.assertEqual(11,len(set(profile.POST_READS_REPORT_CASES)))
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve();self.reports(root)
            profile.verify_admin_post_reads_report(root/'utc')

    def test_given_missing_wrong_nested_disabled_or_unsuccessful_reports__when_verifying__then_refuse_before_claiming_pg_success(self):
        defects=('missing','extra-file','file-name','wrong-root','wrong-class','wrong-count','wrong-label','duplicate-label',
                 'nested-case','failure-attr','missing-failure-attr','error-child','skipped-child','disabled-attr',
                 'disabled-child','bad-status','bad-result','malformed','file-link','directory-link')
        for defect in defects:
            with self.subTest(defect=defect),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp).resolve();path=self.reports(root);tree=ET.parse(path);suite=tree.getroot()
                if defect=='missing':path.unlink()
                elif defect=='extra-file':path.with_name('TEST-extra.xml').write_bytes(path.read_bytes())
                elif defect=='file-name':path.rename(path.with_name('TEST-wrong.xml'))
                elif defect=='wrong-root':suite.tag='testsuites'
                elif defect=='wrong-class':suite[0].set('classname','Other')
                elif defect=='wrong-count':suite.set('tests','7')
                elif defect=='wrong-label':suite[0].set('name','unknown()')
                elif defect=='duplicate-label':suite[1].set('name',suite[0].get('name'))
                elif defect=='nested-case':
                    case=suite[0];suite.remove(case);ET.SubElement(suite,'wrapper').append(case)
                elif defect=='failure-attr':suite.set('failures','1')
                elif defect=='missing-failure-attr':del suite.attrib['failures']
                elif defect=='error-child':ET.SubElement(suite[0],'error')
                elif defect=='skipped-child':ET.SubElement(suite[0],'skipped')
                elif defect=='disabled-attr':suite[0].set('disabled','1')
                elif defect=='disabled-child':ET.SubElement(suite[0],'disabled')
                elif defect=='bad-status':suite[0].set('status','notrun')
                elif defect=='bad-result':suite[0].set('result','ignored')
                elif defect=='malformed':path.write_text('<bad')
                elif defect=='file-link':path.rename(path.with_name('actual.xml'));path.symlink_to(path.with_name('actual.xml'))
                elif defect=='directory-link':(root/'utc').rename(root/'actual');(root/'utc').symlink_to(root/'actual',target_is_directory=True)
                if defect not in ('missing','file-name','malformed','file-link','directory-link'):tree.write(path)
                with self.assertRaises(profile.ProfileBlocked):profile.verify_admin_post_reads_report(root/'utc')

    def test_given_forged_payload_scope__when_loading_runtime__then_recompute_the_fixed_post_reads_input_boolean(self):
        ctx=context();payload={'post_reads_scope_verified':True,'pull_request':{'base':{'sha':ctx['base_sha']},'head':{'repo':{'full_name':profile.REPOSITORY},'ref':ctx['head_ref'],'sha':ctx['head_sha']}}}
        env=dict(GITHUB_EVENT_PATH='/unused/event.json',GITHUB_REPOSITORY=profile.REPOSITORY,GITHUB_SHA=ctx['source_sha'],GITHUB_RUN_ID=ctx['run_id'],GITHUB_RUN_ATTEMPT='1',PULL_REQUEST_BASE_SHA=ctx['base_sha'],POST_READS_SCOPE_VERIFIED='true')
        for valid in (True,False):
            common=types.SimpleNamespace(validate_event=Mock(return_value='pull_request'),changed_paths=Mock(return_value=PATHS),git=Mock(return_value=(ctx['tree_sha']+'\n').encode()))
            with self.subTest(valid=valid),patch.dict('sys.modules',{'ci_branch_policy':common}),patch.dict(profile.os.environ,env,clear=True),patch.object(profile.Path,'read_text',return_value=json.dumps(payload)),patch.object(profile,'verify_note_expiry_inputs',return_value=False),patch.object(profile,'verify_member_management_inputs',return_value=False),patch.object(profile,'verify_signup_statistics_inputs',return_value=False),patch.object(profile,'verify_admin_member_update_inputs',return_value=False),patch.object(profile,'verify_visit_collection_inputs',return_value=False),patch.object(profile,'verify_auth_sessions_inputs',return_value=False),patch.object(profile,'verify_post_statistics_inputs',return_value=False),patch.object(profile,'verify_post_reads_inputs',return_value=valid) as verify:
                actual,_=profile.runtime_context()
            verify.assert_called_once_with();self.assertIs(valid,actual['post_reads_scope_verified'])
            self.assertNotIn('post_reads_scope_verified',profile.make_plan(ctx,PATHS,profile.POST_READS,JAR)['identity'])

    def test_given_post_reads_workflow__when_wiring__then_compile_after_f6_and_preserve_only_common_shape_and_exact_pg(self):
        ci=policy_tests.BackendAppCiPolicyTest();workflow=ci.job('check')
        self.assertEqual({'selected':'true','profiles':'["user-community-integration"]'},dict(line.split('=',1) for line in ci.select(PATHS,reads=True,raw=True).splitlines()))
        for path in ('.github/scripts/backend_contract_profile.py',
                     '.github/scripts/test_backend_admin_post_reads_profile.py'):
            with self.subTest(application_input=path):
                self.assertEqual('selected=true\n', ci.select([path], reads=True))
        parts=re.split(r'(?=^      - name: )',workflow,flags=re.MULTILINE)[1:]
        keyed={m.group(1):part for part in parts if (m:=re.search(r'^        id: (\w+)$',part,re.MULTILINE))}
        order=('fixture_f4_shape','jar_f6','compile_post_reads','pg_post_reads','jar_f7','owned_cleanup')
        for a,b in zip(order,order[1:]):self.assertLess(workflow.index('id: '+a+'\n'),workflow.index('id: '+b+'\n'))
        for key in ('compile_post_reads','pg_post_reads'):
            self.assertIn("if: ${{ matrix.profile == 'full' || matrix.profile == 'user-community-integration' }}",keyed[key])
        self.assertIn(':boot:compileCompatibilityTestJava',keyed['compile_post_reads'])
        for receipt in ('f6-oracle-sha256.txt',):
            self.assertIn(receipt,keyed['compile_post_reads'])
        for n in range(8,30):
            part=keyed['fixture_post_unrelated_f'+str(n)]
            self.assertIn("if: ${{ matrix.profile != 'admin-post-statistics' && matrix.profile != 'user-community-integration' }}",part)
            self.assertIn('python3 scripts/f'+str(n)+'_ci.py prepare',part)
        for key in SKIPPED:
            self.assertTrue("matrix.profile != 'user-community-integration'" in keyed[key]
                            or "matrix.profile == 'full'" in keyed[key]
                            or "matrix.profile == 'visit-collection'" in keyed[key]
                            or "matrix.profile == 'admin-member-update'" in keyed[key])
        self.assertIn("matrix.profile == 'user-community-integration'",keyed['fixture_f4_shape'])
        for script in ('f2','f3','f4','f5','f7'):
            found=[part for part in parts if re.search(r'^        run: python3 scripts/'+script+r'_ci.py prepare$',part,re.MULTILINE)]
            if script=='f2':continue
            self.assertEqual(1,len(found));self.assertNotIn("matrix.profile != 'user-community-integration'",found[0])
        pg=keyed['pg_post_reads']
        for value in (':boot:adminPostReadCompatibilityTest','verify_admin_post_reads_report(',
                      "Path('boot/build/test-results/adminPostReadCompatibilityTest')"):
            self.assertIn(value,pg)
        self.assertIn('if: always()',keyed['owned_cleanup'])
        self.assertIn('F6LegacyAdminPostTodayService',workflow)
        self.assertIn('com/pawbridge/(communityservice|userservice)/',workflow)

    def test_given_current_full_recipe__when_interpreting_reuse__then_require_new_source_pg_and_preserve_every_prior_full_step(self):
        text=policy_tests.WORKFLOW.read_bytes();contract=proof.workflow_contract(text);full=contract['check'][1]
        for name in ('Compile pinned User Community cohort source without database tests',
                     'Compare administrator post pagination and bounded reader permissions in UTC',
                     'Always restore only auth column rights and verify the prior administrator fixture',
                     'Add visit collection tables after full administrator PostgreSQL verification'):
            self.assertIn(name,full)
        self.assertNotIn('Prepare only the owned F4 dependent table shape for scoped expiry',full)
        altered=text.replace(b"matrix.profile == 'full' || matrix.profile == 'user-community-integration'",b"matrix.profile == 'user-community-integration' || always()",1)
        with self.assertRaises(proof.Unavailable):proof.workflow_contract(altered)


if __name__=='__main__':
    unittest.main()
