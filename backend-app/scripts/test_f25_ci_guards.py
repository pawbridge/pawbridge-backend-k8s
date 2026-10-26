"""F25 guards: mocked resources.

Preparation only until separately executed. These controls do not prove PostgreSQL SQL,
HTTP/JAR behavior, transaction rollback, original JpaTM or independent business results.
"""
from contextlib import ExitStack, contextmanager, redirect_stdout
import base64
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import f25_ci as f25
from test_f17_ci_guards import HOSTED

OWNER = '123-1'
MOCK_COUNTS = dict(zip(f25.COUNTERS, (18, 8, 4, 4, 3, 7, 2, 2, 2, 1, 1, 1, 2, 1, 2, 6)))


def token(identity, expiry, role='ROLE_USER'):
    claims = base64.urlsafe_b64encode(json.dumps(
        {'userId': identity, 'role': role, 'exp': expiry}).encode()).decode().rstrip('=')
    return 'synthetic.' + claims + '.signature'


@contextmanager
def packaged_harness(directory, failure=None, mutation=None, wire=None, after_snapshot=False,
                     startup=False, shutdown=None, remaining='0', biglog=False, stub_private=False):
    """Only a stateful control fixture. expected_page and cleanup are shared/mocked, not business/SQL oracles."""
    root=Path(directory).resolve();evidence=root/'evidence'
    jar=root/'boot/build/libs/pawbridge-backend-app.jar';jar.parent.mkdir(parents=True);jar.write_bytes(b'not-a-real-jar')
    baseline={name:[] for name,_,_ in f25.SNAPSHOT_TABLES}
    baseline.update({name:[{'sentinel':index}] for index,(name,_,_) in enumerate(f25.SNAPSHOT_TABLES) if name not in ('private_note_blocks','private_note_members','users')})
    database=copy.deepcopy(baseline);events=[];calls=[];snapshots=[];state={'started':False}
    created='2031-01-01T00:00:00.123456Z'
    def snapshot():
        if after_snapshot and len(calls)==1:raise AssertionError('post-commit snapshot failed')
        value=copy.deepcopy(database)
        if startup and state['started']:value['favorites'][0]['sentinel']='changed'
        events.append('snapshot');snapshots.append(value);return value
    def seed():
        events.append('seed');database['users']=[{'user_id':i,'nickname':f25.NAMES[i]} for i in f25.USERS]
        return f25.OWNED
    def observe(owned):
        events.append('observe');return tuple(r['member_id'] for r in database['private_note_members'])
    def cleanup(owned,members):
        events.append('cleanup');database.clear();database.update(copy.deepcopy(baseline))
    def request(base,path,**kwargs):
        calls.append((path,copy.deepcopy(kwargs)));n=len(calls);events.append('request')
        if n==failure:raise AssertionError('request failed')
        if n in (1,11,14):
            for identity in f25.USERS:
                if not any(r['member_id']==identity for r in database['private_note_members']):database['private_note_members'].append({'member_id':identity})
            database['private_note_blocks'].append({'member_id':f25.CALLER,'blocked_id':f25.OTHER,'created_at':created})
        if n in (7,12,15):database['private_note_blocks']=[]
        if mutation=='column' and n==1:database['users'][0]['nickname']='unexpected'
        if mutation=='reverse' and n==1:database['private_note_blocks'].append({'member_id':f25.OTHER,'blocked_id':f25.CALLER,'created_at':created})
        if mutation=='member' and n==1:database['private_note_members'].append({'member_id':f25.OTHER+1})
        if mutation=='duplicate' and n==5:database['private_note_blocks'][0]['created_at']='2031-01-01T00:00:01Z'
        status={4:403,10:503,13:503,16:401,17:403,18:403}.get(n,200)
        if n in (2,6):body=f25.expected_page(created)
        elif n in (3,8):body={'code':200,'message':f25.SUCCESS,'data':{'memberIds':[f25.OTHER] if n==3 else []}}
        elif n==9:body={'code':200,'message':f25.SUCCESS,'data':{'userId':f25.OTHER,'nickname':f25.NAMES[f25.OTHER],'active':True,'deletionPending':False}}
        elif status!=200:
            message={4:f25.BLOCKED,10:f25.STORAGE,13:f25.MEMBER_UNAVAILABLE,16:'인증 토큰이 필요합니다.',17:'접근할 수 없습니다.',18:'접근할 수 없습니다.'}[n]
            body={'message':message} if n<16 else {'code':status,'message':message,'data':None}
        else:body=None
        headers={'Cache-Control':f25.f19.CACHE_CONTROL if n>=16 else 'no-store'}
        raw=b'' if body is None else json.dumps(body).encode()
        if wire=='cache' and n==1:headers['Cache-Control']='public'
        if wire=='cookie' and n==1:headers['Set-Cookie']='unexpected'
        if wire=='body' and n==1:raw=b'{}'
        if wire=='instant' and n==2:
            body['data']['content'][0]['createdAt']='2031-01-01T09:00:00.123456+09:00';raw=json.dumps(body).encode()
        return status,raw,headers
    def storage(*args,**kwargs):return {'counts':{'PUT':int(mutation=='s3' and bool(calls))},'objects':[],'requests':[]}
    def start(*args):
        state['started']=True;events.append('start')
        if biglog:args[7].write('x'*(1024*1024+1))
        if stub_private:args[6].write('private-reader')
        return object(),object(),'http://mock-app','http://mock-stub',1,0
    def stop(*args):events.append('stop');return 0
    def load():events.append('token');return {'F25_CALLER':'private-used-token'}
    def sql(query):
        events.append(query.split()[0]);return remaining if query.endswith("'pawbridge_f3_writer')") else '0'
    env=dict(HOSTED,F2_TEST_DB_PASSWORD='private-reader',F3_TEST_WRITER_PASSWORD='private-writer',F3_TEST_JWT_SECRET='private-jwt')
    with ExitStack() as stack:
        for patcher in (patch.dict(os.environ,env,clear=True),patch.object(f25,'ROOT',root),patch.object(f25,'EVIDENCE',evidence),
            patch.object(f25,'require_fixture',return_value=OWNER),
            patch.object(f25,'require_owner',return_value=OWNER),patch.object(f25,'require_prior_receipts'),
            patch.object(f25.f21,'check_receipt'),patch.object(f25,'database_snapshot',side_effect=snapshot),
            patch.object(f25,'old_reference_snapshot',side_effect=snapshot),
            patch.object(f25.f3,'request',side_effect=request),patch.object(f25.f3,'expect',side_effect=storage),
            patch.object(f25.f4,'fresh_log',return_value=shutdown if shutdown is not None else 'Shutdown completed. Graceful shutdown complete')):
            stack.enter_context(patcher)
        handles={}
        for name,owner,attribute,side in [('seed',f25,'prepare_business_rows',seed),('observe',f25,'observe_created_members',observe),
                ('cleanup',f25,'cleanup_business_rows',cleanup),('start',f25.f4,'start_ready_children',start),
                ('stop',f25.f3,'stop_owned_child',stop),('load',f25,'load_tokens',load),('sql',f25.f3,'sql',sql)]:
            handles[name]=stack.enter_context(patch.object(owner,attribute,side_effect=side))
        yield dict(handles,root=root,evidence=evidence,database=database,baseline=baseline,requests=calls,events=events,snapshots=snapshots)


class F25GuardTest(unittest.TestCase):
    def test_given_non_hosted_entry__when_calling_resource_actions__then_refuse_before_sql_or_children(self):
        for action in (f25.prepare,f25.require_owner,f25.require_fixture,f25.require_measurements,
                f25.database_snapshot,lambda:f25.compare_snapshots('before_start'),f25.prepare_business_rows,lambda:f25.cleanup_business_rows(f25.OWNED,()),
                lambda:f25.observe_created_members(f25.OWNED),f25.load_tokens,f25.smoke):
            with self.subTest(action=action),patch.dict(os.environ,{},clear=True),patch.object(f25.f3,'sql') as sql,patch.object(f25.f4,'start_ready_children') as start:
                with self.assertRaisesRegex(SystemExit,'restricted to this repository on GitHub-hosted Linux'):action()
                sql.assert_not_called();start.assert_not_called()

    def test_given_sql_failure__when_reporting__then_hide_command_sql_and_private_output(self):
        failure=subprocess.CalledProcessError(1,['private-command'],output='private-out',stderr='private-err')
        with patch.object(f25.f3,'sql',side_effect=failure):
            with self.assertRaises(SystemExit) as actual:f25.owned_sql('private-sql')
        self.assertNotRegex(str(actual.exception),'private-command|private-out|private-err|private-sql')

    def test_given_owner_or_denial_recovery__when_checking_receipts__then_use_prior_identity_without_old_strict(self):
        with patch.object(f25.f24,'require_owner',return_value=OWNER) as owner,patch.object(f25.f24,'require_fixture',side_effect=AssertionError('old strict')),patch.object(f25.f21,'check_receipt') as receipt:
            self.assertEqual(OWNER,f25.require_owner());owner.assert_called_once()
            receipt.assert_called_once_with(f25.EVIDENCE/'f25-fixture-write.txt',f25.fixture_proof(OWNER))
        with patch.object(f25.f24,'require_prior_receipts') as prior,patch.object(f25.f21,'check_receipt') as receipt:
            f25.require_prior_receipts(OWNER);prior.assert_called_once_with(OWNER)
            receipt.assert_called_once_with(f25.EVIDENCE/'f24-fixture-write.txt',f25.f24.fixture_proof(OWNER))

    def test_given_inherited_five_tables__when_checking_catalog__then_only_block_insert_three_is_added(self):
        queries=[]
        with patch.object(f25,'require_owner',return_value=OWNER),patch.object(f25,'catalog',side_effect=lambda q,s:queries.append((q,s)) or 't'):
            self.assertEqual(OWNER,f25.require_fixture())
        self.assertEqual(['shape','acl','constraints']*5,[s for _,s in queries])
        self.assertIn(",column_name,'INSERT')=(column_name IN ('member_id','blocked_id','created_at'))",queries[7][0])
        self.assertIn(",column_name,'UPDATE')=(false)",queries[7][0])
        self.assertIn(",column_name,'SELECT')=(column_name IN ('member_id','blocked_id'))",queries[7][0])
        self.assertIn(",column_name,'UPDATE')=(column_name='member_id')",queries[4][0])
        for query,stage in queries:
            self.assertTrue(query.startswith('SELECT '))
            if stage=='acl':self.assertIn('pg_get_serial_sequence',query);self.assertIn('IS NULL',query)
            if stage=='shape':self.assertIn("pg_get_userbyid(relowner)='postgres'",query)
        for required in ('convalidated','NOT condeferrable','confdeltype',"member_id<>blocked_id",'idx_private_note_blocked_member'):
            self.assertIn(required,queries[8][0])

    def test_given_false_catalog_or_invalid_stage_or_short_snapshot__when_checking__then_stop_at_boundary(self):
        for n in range(15):
            with self.subTest(n=n),patch.object(f25,'require_owner',return_value=OWNER),patch.object(f25,'catalog',side_effect=['t']*n+['f']) as catalog:
                with self.assertRaises(SystemExit):f25.require_fixture()
                self.assertEqual(n+1,catalog.call_count)
        with patch.object(f25,'owned_sql') as sql:
            for query,stage in [('DELETE FROM blocks','acl'),('SELECT true','business')]:
                with self.assertRaises(ValueError):f25.catalog(query,stage)
            sql.assert_not_called()
        with patch.object(f25.f24,'database_snapshot',return_value={str(i):[] for i in range(15)}):
            with self.assertRaises(SystemExit):f25.old_reference_snapshot()

    def test_given_all_raw_tables__when_batching__then_one_owned_select_keeps_original_relations_order_values_and_no_reference_fallback(self):
        tables=(('users','pawbridge_user.users','user_id'),('favorites','pawbridge_user.favorites','favorite_id'),
                ('posts','pawbridge_community.posts','post_id'),('comments','pawbridge_community.comments','comment_id'),
                ('f4comments','pawbridge_f4_fixture.comments','comment_id'),('notes','pawbridge_f4_fixture.private_notes','note_id'),
                ('outbox','pawbridge_community.outbox_events','outbox_id'),('search','pawbridge_community.post_search_documents','post_id'),
                ('videos','pawbridge_community.home_videos','id'),('board','pawbridge_community.home_video_board','id'),
                ('daily_visit_count','pawbridge_community.daily_visit_count','visit_date'),('contact_deletions','pawbridge_user.contact_deletions','user_id'),
                ('private_note_members','pawbridge_community.private_note_members','member_id'),('private_note_blocks','pawbridge_community.private_note_blocks','member_id,blocked_id'),
                ('private_notes','pawbridge_community.private_notes','note_id'),('private_note_mailboxes','pawbridge_community.private_note_mailboxes','member_id,note_id'))
        self.assertEqual(tables,f25.SNAPSHOT_TABLES)
        value={name:[] for name,_,_ in tables}
        value['users']=[{'user_id':9223372036854775807,'nickname':'한글🦊','nullable':None,'flags':[False,True],
                         'nested':{'array':[None,{'number':1.0}],'empty':[]}}]
        value['private_notes']=[{'note_id':'ffffffff-ffff-ffff-ffff-ffffffffffff','created_at':'2032-02-29T15:00:00.123456+00:00'},
                                {'note_id':'80000000-0000-0000-0000-000000000000'}]
        wire=json.dumps(value,ensure_ascii=False)
        with patch.dict(os.environ,HOSTED,clear=True),patch.object(f25.f3,'sql',return_value=wire) as sql,patch.object(f25.f24,'database_snapshot',side_effect=AssertionError('reference fallback')):
            actual=f25.database_snapshot()
        sql.assert_called_once();query=sql.call_args.args[0]
        expected='SELECT json_build_object('+','.join("'"+name+"',(SELECT COALESCE(json_agg(r ORDER BY "+order+"),'[]'::json) FROM "+relation+' r)' for name,relation,order in tables)+')'
        self.assertEqual(expected,query)
        self.assertEqual(f25._snapshot_bytes(value),f25._snapshot_bytes(actual));self.assertIs(type(actual['users'][0]['user_id']),int)
        with patch.object(f25.f24,'database_snapshot',return_value=value) as old,patch.object(f25,'owned_sql',side_effect=AssertionError('batched reference')):
            self.assertIs(value,f25.old_reference_snapshot());old.assert_called_once_with()

    def test_given_invalid_snapshot_or_sql_failure__when_batching__then_fail_without_fallback_or_private_diagnostics(self):
        value={name:[] for name,_,_ in f25.SNAPSHOT_TABLES}
        malformed=[None,{},[],dict(value,unexpected=[]),{name:rows for name,rows in value.items() if name!='users'},
                   dict(value,users=None),dict(value,users=[None])]
        with patch.dict(os.environ,HOSTED,clear=True),patch.object(f25.f24,'database_snapshot',side_effect=AssertionError('fallback')):
            for wire in ['not-json']+[json.dumps(item) for item in malformed]:
                with self.subTest(wire_shape=type(wire).__name__),patch.object(f25.f3,'sql',return_value=wire):
                    with self.assertRaises(SystemExit):f25.database_snapshot()
            failure=subprocess.CalledProcessError(1,['private-command'],output='private-rows',stderr='private-sql')
            with patch.object(f25.f3,'sql',side_effect=failure):
                with self.assertRaises(SystemExit) as actual:f25.database_snapshot()
            self.assertNotRegex(str(actual.exception),'private-command|private-rows|private-sql')

    def test_given_quiescent_snapshots__when_balancing_comparison__then_compare_types_and_array_order_without_logging_rows(self):
        value={name:[] for name,_,_ in f25.SNAPSHOT_TABLES};value['users']=[{'id':9223372036854775807,'flag':True,'name':'비공개🦊','nullable':None,'array':[1,2]}]
        calls=[]
        def sample(mode):calls.append(mode);return copy.deepcopy(value)
        with patch.dict(os.environ,HOSTED,clear=True),patch.object(f25,'old_reference_snapshot',side_effect=lambda:sample('old')),patch.object(f25,'database_snapshot',side_effect=lambda:sample('new')),patch.object(f25.time,'perf_counter',side_effect=range(8)),redirect_stdout(io.StringIO()) as output:
            result=f25.compare_snapshots('before_start',value)
        self.assertEqual(['old','new','new','old'],calls);self.assertEqual('',output.getvalue());self.assertEqual('passed',result['status'])
        self.assertEqual([1,1,1,1],[row['wall_seconds'] for row in result['samples']]);self.assertEqual([21,3,3,21],[row['static_docker_subprocesses'] for row in result['samples']])
        self.assertEqual([1,1,1,1],[row['snapshot_calls'] for row in result['samples']]);self.assertIn('not observed',result['call_counts_basis'])
        self.assertNotIn('비공개',json.dumps(result,ensure_ascii=False))
        for change in ('bool-int','int-float','array-order'):
            different=copy.deepcopy(value)
            if change=='bool-int':different['users'][0]['flag']=1
            elif change=='int-float':different['users'][0]['array'][0]=1.0
            else:different['users'][0]['array'].reverse()
            with self.subTest(change=change),patch.dict(os.environ,HOSTED,clear=True),patch.object(f25,'old_reference_snapshot',return_value=value),patch.object(f25,'database_snapshot',return_value=different):
                with self.assertRaisesRegex(SystemExit,'types or array order differ'):f25.compare_snapshots('after_stop',value)

    def test_given_snapshot_comparison_failure__when_smoking__then_preserve_baseline_and_always_clean_after_both_children_stop(self):
        for phase in ('before_start','after_stop','missing_after_stop'):
            with self.subTest(phase=phase),tempfile.TemporaryDirectory() as d,packaged_harness(d) as h:
                compare=f25.compare_snapshots
                def failure(boundary,expected=None):
                    h['events'].append('compare-'+boundary)
                    if boundary==phase:raise SystemExit('comparison boundary')
                    if phase=='missing_after_stop' and boundary=='after_stop':return None
                    return compare(boundary,expected)
                with patch.object(f25,'compare_snapshots',side_effect=failure):
                    with self.assertRaisesRegex((SystemExit,AssertionError),'comparison'):f25.smoke()
                result=json.loads((h['evidence']/'f25-packaged-result.txt').read_text());self.assertEqual('incomplete',result['status'])
                self.assertEqual(h['baseline'],h['database'])
                if phase=='before_start':h['start'].assert_not_called();h['cleanup'].assert_not_called();self.assertEqual([],h['requests'])
                else:
                    self.assertEqual(2,h['stop'].call_count);h['cleanup'].assert_called_once_with(f25.OWNED,f25.USERS)
                    events=[e for e in h['events'] if e in ('stop','compare-after_stop','observe','cleanup')]
                    self.assertEqual(['stop','stop','compare-after_stop','observe','cleanup'],events)

    def test_given_absent_receipt_and_original_insert_zero__when_preparing__then_grant_once_before_new_strict_without_rows(self):
        with tempfile.TemporaryDirectory() as d:
            events=[];evidence=Path(d).resolve()
            def sql(query='',**kwargs):events.append(kwargs.get('input_text',query));return 't'
            with patch.dict(os.environ,HOSTED,clear=True),patch.object(f25,'EVIDENCE',evidence),patch.object(f25.f24,'require_fixture',side_effect=lambda:events.append('old') or OWNER),patch.object(f25,'database_snapshot',side_effect=lambda:events.append('snapshot') or {}),patch.object(f25,'owned_sql',side_effect=sql),patch.object(f25,'require_fixture',side_effect=lambda:events.append('new') or OWNER):f25.prepare()
            self.assertEqual(['old','snapshot','new','snapshot'],[e for e in events if e in ('old','snapshot','new')])
            grant=next(e for e in events if e.startswith('BEGIN;'))
            self.assertEqual('BEGIN;\nGRANT INSERT(member_id,blocked_id,created_at) ON '+f25.WRITER_BLOCKS+' TO pawbridge_f3_writer;\nCOMMIT;\n',grant)
            self.assertLess(events.index(grant),events.index('new'))
            self.assertEqual(f25.fixture_proof(OWNER),(evidence/'f25-fixture-write.txt').read_text())

    def test_given_wrong_stage_existing_receipt_or_grant_failure__when_preparing__then_never_claim_extension_success(self):
        for phase in ('old','receipt','insert','snapshot','grant'):
            with self.subTest(phase=phase),tempfile.TemporaryDirectory() as d:
                path=Path(d).resolve();proof=path/'f25-fixture-write.txt'
                if phase=='receipt':proof.write_text(f25.fixture_proof(OWNER))
                def sql(query='',**kwargs):
                    if kwargs and phase=='grant':raise SystemExit('grant failed')
                    return 'f' if phase=='insert' else 't'
                with patch.dict(os.environ,HOSTED,clear=True),patch.object(f25,'EVIDENCE',path),patch.object(f25.f24,'require_fixture',side_effect=SystemExit('old') if phase=='old' else None,return_value=OWNER),patch.object(f25,'database_snapshot',side_effect=SystemExit('snapshot') if phase=='snapshot' else None,return_value={}),patch.object(f25,'owned_sql',side_effect=sql) as calls,patch.object(f25,'require_fixture') as strict:
                    with self.assertRaises(SystemExit):f25.prepare()
                    strict.assert_not_called()
                    if phase!='grant':self.assertFalse(any(c.kwargs for c in calls.call_args_list))
                self.assertEqual(phase=='receipt',proof.exists())

    def test_given_denial_body_or_restore_failure__when_unwinding__then_restore_only_after_identity_and_receipt(self):
        for kind in ('insert','nickname'):
            events=[]
            with self.subTest(kind=kind),patch.object(f25,'require_fixture',side_effect=lambda:events.append('strict') or OWNER),patch.object(f25,'require_prior_receipts',side_effect=lambda o:events.append('identity')),patch.object(f25.f21,'check_receipt',side_effect=lambda *a:events.append('receipt')),patch.object(f25,'owned_sql',side_effect=lambda q:events.append(q)):
                with self.assertRaisesRegex(AssertionError,'body'):
                    with f25.denied_privilege(kind):raise AssertionError('body')
            self.assertEqual('strict',events[0]);self.assertTrue(events[1].startswith('REVOKE '));self.assertEqual(['identity','receipt'],events[2:4]);self.assertEqual(events[1].replace('REVOKE ','GRANT ').replace(' FROM ',' TO '),events[4]);self.assertEqual('strict',events[5])
        with patch.object(f25,'require_fixture',return_value=OWNER) as strict,patch.object(f25,'require_prior_receipts'),patch.object(f25.f21,'check_receipt'),patch.object(f25,'owned_sql',side_effect=['',SystemExit('restore')]):
            with self.assertRaises(SystemExit):
                with f25.denied_privilege('insert'):pass
            strict.assert_called_once()

    def test_given_foreign_identity_or_receipt_under_denial__when_restoring__then_no_grant(self):
        for phase in ('identity','receipt'):
            with self.subTest(phase=phase),patch.object(f25,'require_fixture',return_value=OWNER),patch.object(f25,'require_prior_receipts',side_effect=SystemExit('foreign') if phase=='identity' else None),patch.object(f25.f21,'check_receipt',side_effect=SystemExit('foreign') if phase=='receipt' else None),patch.object(f25,'owned_sql') as sql:
                with self.assertRaises(SystemExit):
                    with f25.denied_privilege('insert'):pass
                self.assertEqual(1,sql.call_count)
        with patch.object(f25,'require_fixture',return_value=OWNER),patch.object(f25,'owned_sql') as sql:
            with self.assertRaises(ValueError):
                with f25.denied_privilege('unknown'):pass
            sql.assert_not_called()

    def test_given_unmeasured_journey_counts__when_smoke_requested__then_fail_before_resources(self):
        for counts in (dict(MOCK_COUNTS, request_checks=-1), dict(MOCK_COUNTS, request_checks=True), {'foreign': 1}):
            with self.subTest(counts=counts), patch.dict(os.environ, HOSTED, clear=True), \
                    patch.object(f25, 'EXPECTED_JAR_COUNTS', counts), patch.object(f25, 'require_fixture') as fixture, \
                    patch.object(Path, 'write_text') as write:
                with self.assertRaisesRegex(SystemExit, 'unmeasured'):
                    f25.smoke()
                fixture.assert_not_called()
                write.assert_not_called()

    def test_given_used_token_and_old_keys__when_loading__then_only_regular_owned_0600_identity_role_and_ttl_are_used(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d).resolve();path=root/('f3-tokens-'+OWNER+'.json');good=json.dumps({'F25_CALLER':token(f25.CALLER,1060),'F24_CALLER':'expired-unused'})
            path.write_text(good);path.chmod(0o600)
            with patch.dict(os.environ,dict(HOSTED,RUNNER_TEMP=str(root),F3_TEST_TOKENS_PATH=str(path)),clear=True),patch.object(f25.time,'time',return_value=1000):
                self.assertEqual({'F25_CALLER':token(f25.CALLER,1060)},f25.load_tokens());self.assertEqual(good,path.read_text())
                for identity,expiry,role in ((True,2000,'ROLE_USER'),(f25.OTHER,2000,'ROLE_USER'),(f25.CALLER,1059,'ROLE_USER'),(f25.CALLER,True,'ROLE_USER'),(f25.CALLER,float('inf'),'ROLE_USER'),(f25.CALLER,2000,'ROLE_ADMIN')):
                    path.write_text(json.dumps({'F25_CALLER':token(identity,expiry,role)}))
                    with self.assertRaises(SystemExit):f25.load_tokens()
                path.write_text(good);path.chmod(0o644)
                with self.assertRaisesRegex(SystemExit,'0600'):f25.load_tokens()
                path.chmod(0o600);alias=root/'alias';alias.symlink_to(path)
                with patch.dict(os.environ,{'F3_TEST_TOKENS_PATH':str(alias)}):
                    with self.assertRaisesRegex(SystemExit,'owned'):f25.load_tokens()

    def test_given_reserved_absence_or_collision__when_seeding__then_only_atomic_two_users_are_inserted(self):
        with patch.object(f25,'require_fixture'),patch.object(f25,'owned_sql',return_value='1') as sql:
            with self.assertRaises(SystemExit):f25.prepare_business_rows()
            sql.assert_called_once();self.assertIn('contact_deletions',sql.call_args.args[0]);self.assertIn(f25.WRITER_MEMBERS,sql.call_args.args[0])
        with patch.object(f25,'require_fixture'),patch.object(f25,'owned_sql',side_effect=['0','']) as sql:
            self.assertEqual(f25.OWNED,f25.prepare_business_rows())
        statement=sql.call_args.args[0];self.assertTrue(statement.startswith('BEGIN;'));self.assertTrue(statement.endswith('COMMIT;'));self.assertEqual(1,statement.count('INSERT INTO'))
        self.assertIn('INSERT INTO pawbridge_user.users',statement)
        for forbidden in ('private_note_members','private_note_blocks','UPDATE ','DELETE ','CREATE ','SEQUENCE'):self.assertNotIn(forbidden,statement)

    def test_given_successful_namespace__when_observing_members__then_accept_only_sorted_reserved_zero_one_or_two_keys(self):
        with patch.object(f25,'require_owner') as owner,patch.object(f25,'owned_sql') as sql:
            with self.assertRaises(ValueError):f25.observe_created_members(())
            owner.assert_not_called();sql.assert_not_called()
            for value in ([],[f25.CALLER],[f25.OTHER],list(f25.USERS)):
                sql.return_value=json.dumps(value);self.assertEqual(tuple(value),f25.observe_created_members(f25.OWNED))
            for value in ([True],[f25.OTHER+1],[f25.CALLER,f25.CALLER],list(reversed(f25.USERS)),None):
                sql.return_value=json.dumps(value)
                with self.assertRaises(SystemExit):f25.observe_created_members(f25.OWNED)
        with patch.object(f25,'require_owner',side_effect=SystemExit('foreign')),patch.object(f25,'owned_sql') as sql:
            with self.assertRaises(SystemExit):f25.observe_created_members(f25.OWNED)
            sql.assert_not_called()

    def test_given_exact_seed_and_observed_members__when_cleaning__then_refuse_foreign_references_and_delete_only_owned_keys_in_order(self):
        for observed in ((),(f25.CALLER,),f25.USERS):
            events=[]
            with self.subTest(observed=observed),patch.dict(os.environ,HOSTED,clear=True),patch.object(f25,'require_owner',side_effect=lambda:events.append('owner')),patch.object(f25,'owned_sql',side_effect=lambda q:events.append(q) or '0'):
                f25.cleanup_business_rows(f25.OWNED,observed)
            queries=[e for e in events if e!='owner'];self.assertIn('contact_deletions',queries[0]);self.assertIn('private_notes',queries[0]);self.assertIn('private_note_mailboxes',queries[0]);self.assertIn('NOT IN',queries[1])
            deletes=queries[2:];self.assertTrue(deletes[0].startswith('DELETE FROM '+f25.WRITER_BLOCKS));self.assertIn('(user_id,email)',deletes[-1]);self.assertEqual(3 if observed else 2,len(deletes))
            for q in deletes:self.assertEqual('owner',events[events.index(q)-1])
        for responses in (['1'],['0','1']):
            with patch.dict(os.environ,HOSTED,clear=True),patch.object(f25,'require_owner'),patch.object(f25,'owned_sql',side_effect=responses) as sql:
                with self.assertRaises(SystemExit):f25.cleanup_business_rows(f25.OWNED,f25.USERS)
                self.assertFalse(any(c.args[0].startswith('DELETE ') for c in sql.call_args_list))
        with patch.dict(os.environ,HOSTED,clear=True),patch.object(f25,'owned_sql') as sql:
            for owned,members in (((),()),(f25.OWNED,(f25.OTHER+1,)),(f25.OWNED,(True,))):
                with self.assertRaises(ValueError):f25.cleanup_business_rows(owned,members)
            sql.assert_not_called()

    def test_given_dynamic_database_timestamp_or_wire_instant__when_comparing__then_require_z_microseconds_and_preserve_value(self):
        self.assertEqual(f25.timestamp('2031-01-01T00:00:00.123456Z',wire=True),f25.timestamp('2031-01-01T09:00:00.123456+09:00'))
        for value in ('2031-01-01T09:00:00.123456+09:00','2031-01-01T00:00:00.123456789Z','2031-01-01 00:00:00Z',None):
            with self.assertRaises((AssertionError,ValueError)):f25.timestamp(value,wire=True)

    def test_given_mocked_eighteen_request_journey__when_smoking__then_exact_delta_and_recovery_controls_finish_before_pass(self):
        with tempfile.TemporaryDirectory() as d,packaged_harness(d) as h:
            f25.smoke();value=json.loads((h['evidence']/'f25-packaged-result.txt').read_text())
            self.assertEqual(MOCK_COUNTS,{k:value[k] for k in f25.COUNTERS});self.assertEqual(('passed', 1, 0),(value['status'],value['shutdown_pools'],value['remaining_application_connections']))
            methods=[k['method'] for _,k in h['requests']];self.assertEqual((18,8,7,3),(len(methods),methods.count('PUT'),methods.count('GET'),methods.count('DELETE')))
            self.assertEqual('/api/v1/notes/stream/extra',h['requests'][16][0])
            self.assertEqual('/api/v1/notes/blocks/'+str(f25.OTHER)+'/extra',h['requests'][-1][0]);self.assertNotIn('Authorization',h['requests'][15][1]['headers'])
            self.assertEqual(18,h['load'].call_count);self.assertEqual(['stop','stop','observe','cleanup'],[v for v in h['events'] if v in ('stop','observe','cleanup')]);h['cleanup'].assert_called_once_with(f25.OWNED,f25.USERS)
            self.assertEqual(h['baseline'],h['database'])
            self.assertEqual({'before_start','after_stop'},set(value['snapshot_comparisons']))
            self.assertTrue(all(record['status']=='passed' and record['order']==['old','new','new','old'] for record in value['snapshot_comparisons'].values()))
            for n,event in enumerate(h['events']):
                if event=='request' and h['events'][n-1]=='token':self.assertEqual('snapshot',h['events'][n-2])
            self.assertTrue(any(len(s['private_note_blocks'])==1 for s in h['snapshots']))

    def test_given_token_stale_after_snapshot__when_first_request_is_due__then_no_http_and_stop_observe_cleanup_without_pass(self):
        with tempfile.TemporaryDirectory() as d,packaged_harness(d) as h:
            def load():
                if 'seed' in h['events']:
                    self.assertEqual('snapshot',h['events'][-1]);raise SystemExit('stale')
                return {'F25_CALLER':'private-used-token'}
            h['load'].side_effect=load
            with self.assertRaisesRegex(SystemExit,'stale'):f25.smoke()
            self.assertEqual([],h['requests']);self.assertEqual(2,h['stop'].call_count);h['cleanup'].assert_called_once_with(f25.OWNED,())
            self.assertEqual('incomplete',json.loads((h['evidence']/'f25-packaged-result.txt').read_text())['status'])

    def test_given_committed_first_insert_and_bad_wire_or_after_snapshot__when_unwinding__then_observe_members_even_before_counter_success(self):
        for options in ({'wire':'cache'},{'wire':'cookie'},{'wire':'body'},{'after_snapshot':True}):
            with self.subTest(options=options),tempfile.TemporaryDirectory() as d,packaged_harness(d,**options) as h:
                with self.assertRaises(AssertionError):f25.smoke()
                self.assertEqual(1,len(h['requests']));h['observe'].assert_called_once_with(f25.OWNED);h['cleanup'].assert_called_once_with(f25.OWNED,f25.USERS);self.assertEqual(2,h['stop'].call_count)
                value=json.loads((h['evidence']/'f25-packaged-result.txt').read_text());self.assertEqual(('incomplete',0),(value['status'],value['request_checks']))

    def test_given_foreign_row_column_member_s3_duplicate_timestamp_or_page_wire__when_smoking__then_fail_closed(self):
        for options in ({'mutation':'column'},{'mutation':'reverse'},{'mutation':'member'},{'mutation':'s3'},{'mutation':'duplicate'},{'wire':'instant'},{'startup':True}):
            with self.subTest(options=options),tempfile.TemporaryDirectory() as d,packaged_harness(d,**options) as h:
                with self.assertRaises(AssertionError):f25.smoke()
                self.assertEqual(2,h['stop'].call_count);self.assertEqual('incomplete',json.loads((h['evidence']/'f25-packaged-result.txt').read_text())['status'])

    def test_given_request_failure_or_failed_restore_under_insert_denial__when_unwinding__then_restore_attempt_and_child_cleanup_precede_failure(self):
        for phase in ('request','restore'):
            with self.subTest(phase=phase),tempfile.TemporaryDirectory() as d,packaged_harness(d,failure=10 if phase=='request' else None) as h:
                previous=h['sql'].side_effect
                def sql(query):
                    if phase=='restore' and query.startswith('GRANT INSERT('):raise SystemExit('restore failed')
                    return previous(query)
                h['sql'].side_effect=sql
                with self.assertRaises((AssertionError,SystemExit)):f25.smoke()
                statements=[c.args[0] for c in h['sql'].call_args_list];self.assertTrue(any(q.startswith('REVOKE INSERT(') for q in statements));self.assertTrue(any(q.startswith('GRANT INSERT(') for q in statements));self.assertEqual(2,h['stop'].call_count);h['cleanup'].assert_called_once_with(f25.OWNED,f25.USERS)
                self.assertEqual('incomplete',json.loads((h['evidence']/'f25-packaged-result.txt').read_text())['status'])

    def test_given_failed_seed_stop_observation_or_cleanup__when_unwinding__then_never_claim_unowned_cleanup_or_pass(self):
        for phase in ('seed','stop','observe','cleanup'):
            with self.subTest(phase=phase),tempfile.TemporaryDirectory() as d,packaged_harness(d) as h:
                if phase=='stop':h['stop'].side_effect=[SystemExit('boundary'),0]
                else:h[phase].side_effect=SystemExit('boundary')
                with self.assertRaisesRegex(SystemExit,'boundary'):f25.smoke()
                self.assertEqual(2,h['stop'].call_count)
                if phase in ('seed','observe'):h['cleanup'].assert_not_called()
                if phase=='seed':h['observe'].assert_not_called();self.assertEqual([],h['requests'])
                self.assertEqual('incomplete',json.loads((h['evidence']/'f25-packaged-result.txt').read_text())['status'])

    def test_given_incomplete_shutdown_connection_or_private_or_unbounded_logs__when_finishing__then_no_passed_receipt(self):
        for options in ({'shutdown':'Graceful shutdown complete'},{'remaining':'1'},{'shutdown':'Shutdown completed. Graceful shutdown complete private-reader'},{'biglog':True},{'stub_private':True}):
            with self.subTest(options=options),tempfile.TemporaryDirectory() as d,packaged_harness(d,**options) as h:
                with self.assertRaises(AssertionError):f25.smoke()
                self.assertEqual(2,h['stop'].call_count);h['cleanup'].assert_called_once_with(f25.OWNED,f25.USERS)
                self.assertEqual('incomplete',json.loads((h['evidence']/'f25-packaged-result.txt').read_text())['status'])

    def test_given_f25_workflow__when_wiring__then_f24_finishes_before_extension_and_prepare_precedes_smoke(self):
        workflow = (f25.ROOT.parent / '.github/workflows/backend-app-check.yml').read_text()
        boundary = workflow.index('scripts/test_f25_ci_guards.py')
        stage = workflow[boundary:]
        self.assertLess(workflow.index('python3 scripts/f24_ci.py smoke'), boundary)
        self.assertLess(stage.index('python3 scripts/f25_ci.py prepare'), stage.index('python3 scripts/f25_ci.py smoke'))
        self.assertNotIn('noteBlockCreateCompatibility', workflow)
        self.assertNotIn('python3 scripts/f25_ci.py results', workflow)
        cleanup=stage.index('- name: Remove only the owned disposable fixture');self.assertIn('if: always()',stage[cleanup:cleanup+180]);self.assertNotRegex(stage[:cleanup],r'python3 scripts/f(?:[2-9]|1[0-9]|2[0-4])_ci\.py (?:prepare|smoke)')
        source_step=next(s for s in workflow.split('\n      - name:') if 'Require F25' in s)
        self.assertIn("git cat-file -e '"+f25.SOURCE+"^{commit}'",source_step);self.assertNotIn('git fetch',source_step);self.assertNotIn('GIT_CONFIG_COUNT',source_step)


if __name__=='__main__':
    print('F25 guards: mocked resources, not actual PG/JAR evidence.',flush=True)
    with redirect_stdout(io.StringIO()):unittest.main()


