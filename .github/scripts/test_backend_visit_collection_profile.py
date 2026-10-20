"""Visit lane contracts using fake Git, raw step dictionaries and owned temporary XML only."""
import copy
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

BASE = 'd938003e464c1413b8c27071e3f07d85b4e25ed6'
PATHS = ['.github/scripts/backend_contract_profile.py']
JAR = 'a' * 64
CONTRACTS = ('fixture_f4_shape', 'compile_visit_collection', 'fixture_member_cleanup',
             'fixture_visit_collection', 'pg_visit_collection')
SKIPPED = ('pg_note_expiry', 'pg_member_cleanup', 'pg_signup_statistics', 'compile_admin_update',
           'fixture_admin_update', 'pg_admin_update', 'fixture_admin_visit_collection')


def context(**updates):
    value = dict(event_name='pull_request', repository=profile.REPOSITORY,
                 head_repository=profile.REPOSITORY, head_ref='feat/visit-collection', base_sha=BASE,
                 head_sha='1' * 40, source_sha='2' * 40, tree_sha='3' * 40,
                 run_id='12345', run_attempt='1', visit_collection_scope_verified=True)
    value.update(updates)
    return value


def steps():
    value = {key: dict(outcome='skipped', conclusion='skipped') for key in (*profile.ALL_SMOKES, *SKIPPED)}
    for key in (*CONTRACTS, 'owned_cleanup'):
        value[key] = dict(outcome='success', conclusion='success')
    return value


class VisitCollectionProfileTest(unittest.TestCase):
    def test_given_the_scoped_profile_cli__when_planning_and_auditing__then_reach_real_main_and_reject_unknown_names_before_context(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            jar = root / 'synthetic.jar'
            jar.write_bytes(b'bounded synthetic candidate for CLI parsing')
            plan = root / 'contract-plan.txt'
            execution = root / 'contract-execution.txt'
            common = ['--profile', profile.VISIT_COLLECTION, '--jar', str(jar), '--plan', str(plan)]
            with patch.object(profile, 'runtime_context', return_value=(context(), PATHS)) as runtime, \
                    patch.dict(profile.os.environ, {'CONTRACT_STEPS_JSON': json.dumps(steps())}):
                self.assertEqual(0, profile.main(['--mode', 'plan', *common]))
                self.assertEqual('planned', json.loads(plan.read_text())['status'])
                self.assertEqual(0, profile.main(['--mode', 'audit', *common, '--execution', str(execution)]))
                result = json.loads(execution.read_text())
                self.assertEqual('passed', result['status'])
                self.assertEqual(profile.VISIT_COLLECTION, result['profile'])
                self.assertEqual(2, runtime.call_count)
                with patch('sys.stderr'), self.assertRaises(SystemExit) as unknown:
                    profile.main(['--mode', 'plan', '--profile', 'not-a-real-profile', '--jar', str(jar), '--plan', str(plan)])
                self.assertEqual(2, unknown.exception.code)
                self.assertEqual(2, runtime.call_count)


    def synthetic_git(self, diff=None, changed=False):
        blobs = {path: ('fixed visit input ' + str(i)).encode() for i, path in enumerate(profile.VISIT_APP_HASHES)}
        hashes = {path: hashlib.sha256(raw).hexdigest() for path, raw in blobs.items()}
        if changed:
            blobs[next(iter(blobs))] += b'changed'
        def read(*args):
            if args == ('diff', '--name-only', '--no-renames', '-z', BASE, 'HEAD', '--', 'backend-app'):
                return b'\0'.join(path.encode() for path in (*blobs, 'backend-app/README.md')) + b'\0' if diff is None else diff
            if len(args) == 2 and args[0] == 'show':
                return blobs[args[1].removeprefix('HEAD:')]
            raise AssertionError('Unexpected synthetic Git operation')
        return Mock(side_effect=read), hashes

    def test_given_twenty_two_frozen_inputs_and_readme_path_only__when_verifying__then_use_only_fixed_git_object_bytes(self):
        self.assertEqual(BASE, profile.VISIT_BASE)
        self.assertEqual(22, len(profile.VISIT_APP_HASHES))
        self.assertEqual('a37b4b2b0c4f7c66bb03331f2f81ab42ba41c80b45400d1c2dd68f7f0437ccac', hashlib.sha256(json.dumps(sorted(profile.VISIT_APP_HASHES.items()), separators=(',', ':')).encode()).hexdigest())
        self.assertEqual(frozenset(profile.VISIT_APP_HASHES) | {'backend-app/README.md'}, profile.VISIT_APP_EXCEPTIONS)
        self.assertNotIn('backend-app/README.md', profile.VISIT_APP_HASHES)
        git, hashes = self.synthetic_git()
        with patch.object(profile, 'VISIT_APP_HASHES', hashes):
            self.assertIs(True, profile.verify_visit_collection_inputs(git_reader=git))
        self.assertEqual(23, git.call_count)
        for path in hashes:
            git.assert_any_call('show', 'HEAD:' + path)

    def test_given_unknown_duplicate_changed_or_unreadable_inputs__when_verifying__then_refuse_the_scoped_lane(self):
        for defect in ('changed', 'unknown', 'duplicate', 'encoding', 'type', 'missing', 'timeout', 'partial'):
            raw = {'unknown': b'backend-app/user/src/main/java/Unreviewed.java\0',
                   'duplicate': b'backend-app/README.md\0backend-app/README.md\0',
                   'encoding': b'backend-app/\xff\0', 'type': 'not bytes'}.get(defect)
            git, hashes = self.synthetic_git(raw, changed=defect == 'changed')
            if defect == 'missing': git.side_effect = subprocess.CalledProcessError(1, ['synthetic-git'])
            elif defect == 'timeout': git.side_effect = subprocess.TimeoutExpired('synthetic-git', 8)
            elif defect == 'partial': hashes.pop(next(iter(hashes)))
            with self.subTest(defect=defect), patch.object(profile, 'VISIT_APP_HASHES', hashes):
                self.assertIs(False, profile.verify_visit_collection_inputs(git_reader=git))

    def test_given_verified_same_repo_pr_or_dev_push__when_selecting__then_require_new_pg_and_explicitly_skip_unrelated_contracts(self):
        for ctx in (context(), context(event_name='push', head_ref='dev')):
            self.assertEqual([profile.VISIT_COLLECTION], profile.select_profiles(ctx, PATHS))
            self.assertEqual([profile.FULL], profile.select_profiles(dict(ctx, requested_profile=profile.FULL), PATHS))
            plan = profile.make_plan(ctx, PATHS, profile.VISIT_COLLECTION, JAR)
            self.assertEqual([], plan['required_smokes'])
            self.assertEqual(list(profile.ALL_SMOKES), plan['skipped_smokes'])
            self.assertEqual(28, len(plan['skipped_smokes']))
            self.assertEqual(list(CONTRACTS), plan['required_contracts'])
            self.assertEqual(list(SKIPPED), plan['skipped_contracts'])
        for updates, paths in [(dict(visit_collection_scope_verified=v), PATHS) for v in (False,None,1,'true')] + [
                ({'head_repository':'fork/other'},PATHS), ({'repository':'fork/other'},PATHS),
                ({'event_name':'push','head_ref':'feat/other'},PATHS), ({'event_name':'unknown'},PATHS),
                ({},[]), ({},['backend-app/user/src/main/java/Unknown.java'])]:
            with self.subTest(updates=updates, paths=paths):
                ctx=context(**updates)
                self.assertEqual([profile.FULL],profile.select_profiles(ctx,paths))
                with self.assertRaises(profile.ProfileBlocked):
                    profile.select_profiles(dict(ctx,requested_profile=profile.VISIT_COLLECTION),paths)
        with self.assertRaises(profile.ProfileBlocked):
            profile.select_profiles(dict(context(),requested_profile='visit-collection-extra'),PATHS)

    def test_given_raw_visit_steps__when_auditing__then_block_false_skips_masked_failures_unrelated_execution_and_missing_cleanup(self):
        plan=profile.make_plan(context(),PATHS,profile.VISIT_COLLECTION,JAR)
        result=profile.audit_execution(plan,context(),PATHS,profile.VISIT_COLLECTION,JAR,steps())
        self.assertEqual([],result['executed_smokes']);self.assertEqual(list(SKIPPED),result['skipped_contracts'])
        for key in (*CONTRACTS,'owned_cleanup'):
            for bad in (None,dict(outcome='skipped',conclusion='skipped'),dict(outcome='failure',conclusion='success')):
                actual=steps()
                if bad is None:del actual[key]
                else:actual[key]=bad
                with self.subTest(required=key,bad=bad),self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan,context(),PATHS,profile.VISIT_COLLECTION,JAR,actual)
        for key in (*SKIPPED,*profile.ALL_SMOKES):
            for bad in (None,dict(outcome='success',conclusion='success'),dict(outcome='skipped',conclusion='success')):
                actual=steps()
                if bad is None:del actual[key]
                else:actual[key]=bad
                with self.subTest(skipped=key,bad=bad),self.assertRaises(profile.ProfileBlocked):
                    profile.audit_execution(plan,context(),PATHS,profile.VISIT_COLLECTION,JAR,actual)
        for defect in ('plan','identity','unknown-jar'):
            actual=steps();saved=copy.deepcopy(plan);ctx=context()
            if defect=='plan':saved['skipped_contracts']=[]
            elif defect=='identity':ctx['tree_sha']='b'*40
            else:actual['jar_f30']=dict(outcome='skipped',conclusion='skipped')
            with self.subTest(defect=defect),self.assertRaises(profile.ProfileBlocked):
                profile.audit_execution(saved,ctx,PATHS,profile.VISIT_COLLECTION,JAR,actual)

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

    def reports(self,root):
        paths=[]
        for sub,names in [('utc',profile.VISIT_REPORT_CASES),('seoul',(profile.VISIT_SEOUL_METHOD+'()',))]:
            folder=root/sub;folder.mkdir()
            suite=ET.Element('testsuite',name=profile.VISIT_REPORT_CLASS,tests=str(len(names)),failures='0',errors='0',skipped='0')
            for name in names:ET.SubElement(suite,'testcase',name=name,classname=profile.VISIT_REPORT_CLASS)
            path=folder/('TEST-'+profile.VISIT_REPORT_CLASS+'.xml');ET.ElementTree(suite).write(path);paths.append(path)
        return paths

    def test_given_all_six_methods_and_only_one_seoul_boundary__when_verifying__then_require_the_exact_ten_invocations(self):
        self.assertEqual((4,2,9),(len(profile.VISIT_SINGLE_METHODS),len(profile.VISIT_PARAMETER_METHODS),len(set(profile.VISIT_REPORT_CASES))))
        self.assertIn(profile.VISIT_PARAMETER_METHODS[1] + '[3]', profile.VISIT_REPORT_CASES)
        self.assertNotIn(profile.VISIT_PARAMETER_METHODS[0] + '[3]', profile.VISIT_REPORT_CASES)
        self.assertEqual(profile.VISIT_SINGLE_METHODS[0],profile.VISIT_SEOUL_METHOD)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp).resolve();self.reports(root)
            profile.verify_visit_collection_report(root/'utc',root/'seoul')

    def test_given_missing_wrong_nested_disabled_or_unsuccessful_reports__when_verifying__then_refuse_before_claiming_pg_success(self):
        defects=('missing','extra-file','file-name','wrong-root','wrong-class','wrong-count','wrong-label','duplicate-label',
                 'nested-case','failure-attr','missing-failure-attr','error-child','skipped-child','disabled-attr',
                 'disabled-child','bad-status','bad-result','malformed','file-link','directory-link','seoul-extra',
                 'missing-hash-permission')
        for defect in defects:
            with self.subTest(defect=defect),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp).resolve();path,seoul=self.reports(root);tree=ET.parse(path);suite=tree.getroot()
                if defect=='missing':path.unlink()
                elif defect=='extra-file':path.with_name('TEST-extra.xml').write_bytes(path.read_bytes())
                elif defect=='file-name':path.rename(path.with_name('TEST-wrong.xml'))
                elif defect=='wrong-root':suite.tag='testsuites'
                elif defect=='wrong-class':suite[0].set('classname','Other')
                elif defect=='wrong-count':suite.set('tests','7')
                elif defect=='wrong-label':suite[0].set('name','unknown()')
                elif defect=='missing-hash-permission':
                    suite.remove(next(case for case in suite if case.get('name') == profile.VISIT_PARAMETER_METHODS[1] + '[3]'))
                    suite.set('tests','8')
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
                elif defect=='seoul-extra':
                    t=ET.parse(seoul);t.getroot().set('tests','2');ET.SubElement(t.getroot(),'testcase',name=profile.VISIT_SINGLE_METHODS[1]+'()',classname=profile.VISIT_REPORT_CLASS);t.write(seoul)
                if defect not in ('missing','file-name','malformed','file-link','directory-link','seoul-extra'):tree.write(path)
                with self.assertRaises(profile.ProfileBlocked):profile.verify_visit_collection_report(root/'utc',root/'seoul')

    def test_given_forged_payload_scope__when_loading_runtime__then_recompute_the_fixed_visit_input_boolean(self):
        ctx=context();payload={'visit_collection_scope_verified':True,'pull_request':{'base':{'sha':BASE},'head':{'repo':{'full_name':profile.REPOSITORY},'ref':ctx['head_ref'],'sha':ctx['head_sha']}}}
        env=dict(GITHUB_EVENT_PATH='/unused/event.json',GITHUB_REPOSITORY=profile.REPOSITORY,GITHUB_SHA=ctx['source_sha'],GITHUB_RUN_ID=ctx['run_id'],GITHUB_RUN_ATTEMPT='1',PULL_REQUEST_BASE_SHA=BASE,VISIT_COLLECTION_SCOPE_VERIFIED='true')
        for valid in (True,False):
            common=types.SimpleNamespace(validate_event=Mock(return_value='pull_request'),changed_paths=Mock(return_value=PATHS),git=Mock(return_value=(ctx['tree_sha']+'\n').encode()))
            with self.subTest(valid=valid),patch.dict('sys.modules',{'ci_branch_policy':common}),patch.dict(profile.os.environ,env,clear=True),patch.object(profile.Path,'read_text',return_value=json.dumps(payload)),patch.object(profile,'verify_note_expiry_inputs',return_value=False),patch.object(profile,'verify_member_management_inputs',return_value=False),patch.object(profile,'verify_signup_statistics_inputs',return_value=False),patch.object(profile,'verify_admin_member_update_inputs',return_value=False),patch.object(profile,'verify_auth_sessions_inputs',return_value=False),patch.object(profile,'verify_post_statistics_inputs',return_value=False),patch.object(profile,'verify_post_reads_inputs',return_value=False),patch.object(profile,'verify_visit_collection_inputs',return_value=valid) as verify:
                actual,_=profile.runtime_context()
            verify.assert_called_once_with();self.assertIs(valid,actual['visit_collection_scope_verified'])
            self.assertNotIn('visit_collection_scope_verified',profile.make_plan(ctx,PATHS,profile.VISIT_COLLECTION,JAR)['identity'])

    def test_given_visit_workflow__when_wiring__then_compile_before_ddl_and_keep_member_mode_separate_from_full_admin_mode(self):
        ci=policy_tests.BackendAppCiPolicyTest();workflow=ci.job('check')
        self.assertEqual({'selected':'true','profiles':'["visit-collection"]'},dict(line.split('=',1) for line in ci.select(PATHS,visit=True,raw=True).splitlines()))
        parts=re.split(r'(?=^      - name: )',workflow,flags=re.MULTILINE)[1:]
        keyed={m.group(1):part for part in parts if (m:=re.search(r'^        id: (\w+)$',part,re.MULTILINE))}
        order=('fixture_f4_shape','compile_visit_collection','fixture_member_cleanup','fixture_visit_collection','fixture_admin_visit_collection','pg_visit_collection','owned_cleanup')
        for a,b in zip(order,order[1:]):self.assertLess(workflow.index('id: '+a+'\n'),workflow.index('id: '+b+'\n'))
        self.assertLess(workflow.index('id: pg_admin_update\n'),workflow.index('id: fixture_admin_visit_collection\n'))
        self.assertIn(':boot:compileCompatibilityTestJava',keyed['compile_visit_collection'])
        self.assertIn('f16-collection-oracle-sha256.txt',keyed['compile_visit_collection'])
        self.assertIn('python3 scripts/f29_ci.py visit-collection-prepare',keyed['fixture_visit_collection'])
        self.assertIn("if: ${{ matrix.profile == 'visit-collection' }}",keyed['fixture_visit_collection'])
        self.assertIn('python3 scripts/f29_ci.py admin-visit-collection-prepare',keyed['fixture_admin_visit_collection'])
        self.assertIn("if: ${{ matrix.profile == 'full' }}",keyed['fixture_admin_visit_collection'])
        self.assertNotIn("matrix.profile != 'visit-collection'",keyed['fixture_member_cleanup'])
        self.assertIn("matrix.profile == 'visit-collection'",keyed['fixture_f4_shape'])
        for key in SKIPPED:
            if key!='fixture_admin_visit_collection':self.assertTrue("matrix.profile != 'visit-collection'" in keyed[key] or "matrix.profile == 'admin-member-update'" in keyed[key])
        pg=keyed['pg_visit_collection']
        for value in (':boot:visitCollectionCompatibilityTest :boot:visitCollectionCompatibilitySeoulTest','mode=member','then mode=admin','-PvisitCollectionFixtureMode="$mode"','verify_visit_collection_report(',"Path('boot/build/test-results/visitCollectionCompatibilityTest')","Path('boot/build/test-results/visitCollectionCompatibilitySeoulTest')"):
            self.assertIn(value,pg)
        self.assertIn('if: always()',keyed['owned_cleanup'])
        self.assertIn('com/pawbridge/backend/compatibility/',workflow)
        self.assertIn('com/pawbridge/(communityservice|userservice)/',workflow)

    def test_given_current_full_recipe__when_interpreting_reuse__then_keep_new_full_pg_but_never_require_selected_only_steps(self):
        ci=policy_tests.BackendAppCiPolicyTest();text=policy_tests.WORKFLOW.read_bytes();contract=proof.workflow_contract(text)
        full=contract['check'][1]
        self.assertIn('Add visit collection tables after full administrator PostgreSQL verification',full)
        self.assertIn('Compare visit collection count, periods, cleanup and one Seoul boundary',full)
        self.assertNotIn('Compile pinned visit collection and member source closure without database tests',full)
        self.assertNotIn('Add visit collection tables and exact writer rights on the member-only fixture',full)
        self.assertNotIn('Prepare only the owned F4 dependent table shape for scoped expiry',full)
        altered=text.replace(b"if: ${{ matrix.profile == 'visit-collection' }}",b"if: ${{ matrix.profile == 'visit-collection' || always() }}",1)
        with self.assertRaises(proof.Unavailable):proof.workflow_contract(altered)


if __name__=='__main__':
    unittest.main()
