#!/usr/bin/env python3
"""F24 hosted-only unblock fixture and runtime evidence; no local database is permitted."""
from contextlib import contextmanager
import hashlib
import base64
import copy
import math
import stat
import time
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f19_ci as f19
import f20_ci as f20
import f21_ci as f21
import f22_ci as f22
import f23_ci as f23

ROOT,EVIDENCE=f3.ROOT,f3.EVIDENCE
SOURCE='f06a72a06bd26662c647bde780dfc7a27bf7349c'
TASK='noteBlockRemoveCompatibilityTest'
PG_CLASS='com.pawbridge.backend.compatibility.PostgresqlNoteBlockRemoveCompatibilityTest'
MVC_CLASS='com.pawbridge.backend.compatibility.F24LegacyNoteBlockRemoveMvcTest'
# Original-only observation6 and comparison14 passed locally; PG14/JAR12 remain hosted execution gates.
REPORTS={TASK:{PG_CLASS:14,MVC_CLASS:14}}
WRITER_MEMBERS='pawbridge_community.private_note_members'
WRITER_BLOCKS='pawbridge_community.private_note_blocks'

def owned_sql(query='',*,input_text=None):
    try:
        return f3.sql(query,input_text=input_text) if input_text is not None else f3.sql(query)
    except subprocess.CalledProcessError:
        raise SystemExit('F24 owned fixture SQL failed without exposing command, SQL or output') from None

def fixture_proof(owner):
    return ('owner='+owner+'\nsource_sha='+SOURCE+'\n'
            'fixture=F23 notes8/mailboxes6 plus member-lock/outgoing-block writer\n'
            'new_business_ddl=0\nnew_schema=0\nnew_marker=0\n'
            'writer_columns=members:SELECT,INSERT,UPDATE(member_id);blocks:SELECT(member_id,blocked_id),DELETE\n'
            'new_sequence_acl=0\n')

def require_prior_receipts(owner):
    # Identity and receipts only: this path must work while one owned grant is deliberately absent.
    f19.require_identity(owner)
    f21.check_receipt(EVIDENCE/'f20-fixture-reuse.txt',f21.f20_proof(owner))
    f21.require_f21_receipt(owner)
    f21.check_receipt(EVIDENCE/'f22-fixture-reuse.txt',f22.fixture_proof(owner))
    f21.check_receipt(EVIDENCE/'f23-fixture-reuse.txt',f23.fixture_proof(owner))

def require_owner():
    # The inherited User-seven chain is unchanged; no member/block writer-zero checker runs here.
    owner=f22.require_owner()
    f21.check_receipt(EVIDENCE/'f23-fixture-reuse.txt',f23.fixture_proof(owner))
    f21.check_receipt(EVIDENCE/'f24-fixture-write.txt',fixture_proof(owner))
    return owner

def catalog(query,stage):
    if stage not in ('shape','acl','constraints') or not query.startswith('SELECT '):
        raise ValueError('F24 catalog is restricted to static read-only metadata')
    return owned_sql(query)

def require_fixture():
    owner=require_owner()
    for schema, table in f20.TABLES:
        relation = schema + '.' + table
        if table == 'contact_deletions':
            shape = "('user_id','bigint','NO',0,''),('started_at','timestamp with time zone','NO',6,'CURRENT_TIMESTAMP')"
            readable, size = ('user_id',), 2
        elif table == 'private_note_members':
            shape, readable, size = "('member_id','bigint','NO',0,'')", (), 1
        else:
            shape = "('member_id','bigint','NO',0,''),('blocked_id','bigint','NO',0,''),('created_at','timestamp with time zone','NO',6,'')"
            readable, size = ('member_id', 'blocked_id', 'created_at'), 3
        where = " FROM information_schema.columns WHERE table_schema='" + schema + "' AND table_name='" + table + "'"
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and(is_identity='NO' AND is_generated='NEVER') "
                "AND bool_and((column_name,data_type,is_nullable,COALESCE(datetime_precision,0),COALESCE(column_default,'')) IN ("
                + shape + ")) AND (SELECT relkind='r' AND relpersistence='p' AND pg_get_userbyid(relowner)='postgres' "
                "FROM pg_class WHERE oid='" + relation + "'::regclass)" + where, 'shape') != 't':
            raise SystemExit('F24 inherited fixture lacks the exact original shape/defaults: ' + table)
        predicate = 'false' if not readable else 'column_name IN (' + ','.join(f4.literal(c) for c in readable) + ')'
        writer_member = "column_name='member_id'" if table == 'private_note_members' else 'false'
        writer_read = writer_member if table == 'private_note_members' else "column_name IN ('member_id','blocked_id')" if table == 'private_note_blocks' else 'false'
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and("
                "has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'SELECT')=(" + predicate + ') '
                "AND NOT has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'INSERT,UPDATE,REFERENCES') "
                "AND has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'SELECT')=(" + writer_read + ") "
                "AND has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'INSERT')=(" + writer_member + ") "
                "AND has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'UPDATE')=(" + writer_member + ") "
                "AND NOT has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'REFERENCES') "
                "AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL) "
                "AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER') "
                "AND has_table_privilege('pawbridge_f3_writer','" + relation + "','DELETE')=" + ("true" if table == 'private_note_blocks' else "false")
                + where, 'acl') != 't':
            raise SystemExit('F24 inherited fixture lacks exact exact pending/blocks/member-lock writer/no-sequence ACL: ' + table)
        pk = ('member_id', 'blocked_id') if table == 'private_note_blocks' else readable or ('member_id',)
        clauses = ["(SELECT count(*)=" + str({'contact_deletions': 2, 'private_note_members': 2, 'private_note_blocks': 4}[table])
                   + " FROM pg_constraint WHERE conrelid='" + relation + "'::regclass)",
                   "(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + relation + "'::regclass AND contype='p' "
                   "AND convalidated AND NOT condeferrable AND conkey=" + f20._columns(relation, pk) + ')']
        if table != 'contact_deletions':
            expression = 'member_id>0' if table == 'private_note_members' else 'member_id<>blocked_id'
            clauses.append("(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + relation
                + "'::regclass AND contype='c' AND convalidated AND conkey=" + f20._columns(relation, pk)
                + " AND regexp_replace(pg_get_expr(conbin,conrelid),'::bigint|[()[:space:]]','','g')='" + expression + "')")
        for column in (() if table == 'private_note_members' else ('user_id',) if table == 'contact_deletions' else pk):
            target = 'pawbridge_user.users' if table == 'contact_deletions' else 'pawbridge_community.private_note_members'
            target_column = 'user_id' if table == 'contact_deletions' else 'member_id'
            clauses.append("(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + relation
                + "'::regclass AND contype='f' AND convalidated AND NOT condeferrable AND NOT condeferred "
                "AND confrelid='" + target + "'::regclass AND confdeltype='c' AND confupdtype='a' AND confmatchtype='s' "
                'AND conkey=' + f20._columns(relation, (column,)) + ' AND confkey=' + f20._columns(target, (target_column,)) + ')')
        clauses.append("(SELECT count(*)=" + ('2' if table == 'private_note_blocks' else '1')
                       + " FROM pg_index WHERE indrelid='" + relation + "'::regclass)")
        if table == 'private_note_blocks':
            clauses.append("(SELECT count(*)=1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_am a ON a.oid=c.relam "
                "WHERE i.indrelid='" + relation + "'::regclass AND c.relname='idx_private_note_blocked_member' "
                "AND i.indisvalid AND i.indisready AND NOT i.indisunique AND i.indpred IS NULL AND i.indexprs IS NULL "
                "AND i.indnatts=1 AND a.amname='btree' AND i.indkey[0]=(SELECT attnum FROM pg_attribute WHERE attrelid='"
                + relation + "'::regclass AND attname='blocked_id'))")
        if catalog('SELECT ' + ' AND '.join(clauses), 'constraints') != 't':
            raise SystemExit('F24 inherited fixture lacks exact validated PK/FK/CHECK/index definitions: ' + table)
    shapes = {
        'private_notes': "('note_id','uuid','NO',0,'',0),('sender_id','bigint','YES',0,'',0),('recipient_id','bigint','YES',0,'',0),"
                         "('body','text','NO',0,'',0),('reply_to','uuid','YES',0,'',0),('context_type','character varying','YES',0,'',10),"
                         "('context_id','bigint','YES',0,'',0),('created_at','timestamp with time zone','NO',6,'',0),('expires_at','timestamp with time zone','NO',6,'',0)",
        'private_note_mailboxes': "('member_id','bigint','NO',0,'',0),('note_id','uuid','NO',0,'',0),('direction','character varying','NO',0,'',5),"
                                 "('read_at','timestamp with time zone','YES',6,'',0),('favorite','boolean','NO',0,'false',0),('created_at','timestamp with time zone','NO',6,'',0)",
    }
    for schema, table in f22.TABLES:
        relation, size = schema + '.' + table, 9 if table == 'private_notes' else 6
        where = " FROM information_schema.columns WHERE table_schema='" + schema + "' AND table_name='" + table + "'"
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and(is_identity='NO' AND is_generated='NEVER') AND bool_and("
                   '(column_name,data_type,is_nullable,COALESCE(datetime_precision,0),COALESCE(column_default,\'\'),COALESCE(character_maximum_length,0)) IN ('
                   + shapes[table] + ")) AND (SELECT relkind='r' AND relpersistence='p' AND pg_get_userbyid(relowner)='postgres' "
                   "FROM pg_class WHERE oid='" + relation + "'::regclass)" + where, 'shape') != 't':
            raise SystemExit('F24 inherited fixture differs from the exact original table shape: ' + table)
        allowed = 'column_name IN (' + ','.join(f4.literal(column) for column in f22.READABLE[table]) + ')'
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and(has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'SELECT')=(" + allowed + ") AND NOT has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'INSERT,UPDATE,REFERENCES') AND NOT has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'SELECT,INSERT,UPDATE,REFERENCES') AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL) "
                   "AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                   "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')" + where, 'acl') != 't':
            raise SystemExit('F24 inherited fixture lacks exact notes-eight/mailboxes-six/writer-zero/no-sequence privileges')
        if catalog('SELECT ' + ' AND '.join(f21.constraint_checks(table) + f21.index_checks(table)), 'constraints') != 't':
            raise SystemExit('F24 inherited fixture differs from the original validated PK/FK/CHECK/index definitions: ' + table)
    return owner

def database_snapshot():
    snapshot=f23.database_snapshot()
    if len(snapshot)!=16:raise SystemExit('F24 requires all sixteen inherited tables')
    return snapshot

def prepare():
    f2.require_hosted()
    owner=f23.require_fixture() # Last old writer-zero check; never called after the new grants.
    f21.check_receipt(EVIDENCE/'f23-fixture-reuse.txt',f23.fixture_proof(owner))
    target=EVIDENCE/'f24-fixture-write.txt';proof=fixture_proof(owner)
    f21.check_receipt(target,proof,allow_missing=True)
    before=database_snapshot()
    owned_sql(input_text='BEGIN;\n'
        'GRANT SELECT(member_id),INSERT(member_id),UPDATE(member_id) ON '+WRITER_MEMBERS+' TO pawbridge_f3_writer;\n'
        'GRANT SELECT(member_id,blocked_id),DELETE ON '+WRITER_BLOCKS+' TO pawbridge_f3_writer;\nCOMMIT;\n')
    target.write_text(proof)
    require_fixture()
    if database_snapshot()!=before:raise SystemExit('F24 permission preparation changed business rows')
    print('Prepared only existing member-lock and outgoing-block writer privileges; business DDL/rows unchanged')

@contextmanager
def denied_privilege(kind):
    owner=require_fixture()
    clauses={'delete':'DELETE ON '+WRITER_BLOCKS,'member-update':'UPDATE(member_id) ON '+WRITER_MEMBERS,
             'nickname':'SELECT(nickname) ON pawbridge_user.users'}
    if kind not in clauses:raise ValueError('Only the planned F24 failure privileges may be revoked')
    role='pawbridge_f2_reader' if kind=='nickname' else 'pawbridge_f3_writer'
    # require_fixture proved actual possession before this deliberate denial.
    owned_sql('REVOKE '+clauses[kind]+' FROM '+role)
    try:yield
    finally:
        require_prior_receipts(owner)
        f21.check_receipt(EVIDENCE/'f24-fixture-write.txt',fixture_proof(owner))
        owned_sql('GRANT '+clauses[kind]+' TO '+role)
        require_fixture()

def verify_results():
    require_measurements();counts={}
    for task,classes in REPORTS.items():
        if any(type(count) is not int or count<=0 for count in classes.values()):
            raise SystemExit('F24 original MVC outcomes and case count remain unmeasured')
        directory=ROOT/'boot/build/test-results'/task
        if {path.name for path in directory.glob('TEST-*.xml')}!={'TEST-'+name+'.xml' for name in classes}:
            raise SystemExit('F24 compatibility XML class set differs')
        counts[task]={}
        for name,expected in classes.items():
            suite=ET.parse(directory/('TEST-'+name+'.xml')).getroot()
            if suite.tag!='testsuite' or suite.attrib.get('name')!=name:raise SystemExit('F24 XML suite FQN differs')
            values={key:int(suite.attrib[key]) for key in ('tests','failures','errors','skipped')}
            cases=suite.findall('testcase')
            if values!={'tests':expected,'failures':0,'errors':0,'skipped':0} or len(cases)!=expected:
                raise SystemExit('F24 XML exact count or result differs')
            if any(case.attrib.get('classname')!=name for case in cases) or any(case.find(tag) is not None for case in cases for tag in ('failure','error','skipped')):
                raise SystemExit('F24 XML child failure differs from aggregate counters')
            counts[task][name]=values
    (EVIDENCE/'f24-results.json').write_text(json.dumps(counts,sort_keys=True))
    print('Verified exact F24 UTC PG and measured MVC XML')


def load_tokens():
    owner = f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    expected = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    if path.is_symlink() or path.resolve() != expected:
        raise SystemExit('F24 token path is not owned by this run')
    try:
        mode = path.stat().st_mode
        if not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise SystemExit('F24 token file must remain regular 0600')
        tokens = json.loads(path.read_text())
        parts = tokens['F24_CALLER'].split('.')
        claims = json.loads(base64.urlsafe_b64decode(parts[1] + '=' * (-len(parts[1]) % 4)))
        valid = (len(parts) == 3 and type(claims['userId']) is int and claims['userId'] == CALLER
                 and claims['role'] == 'ROLE_USER' and type(claims['exp']) in (int, float)
                 and math.isfinite(claims['exp']) and claims['exp'] >= time.time() + 60)
    except (OSError, AttributeError, KeyError, IndexError, ValueError, TypeError):
        raise SystemExit('F24 used token identity or freshness is invalid') from None
    if not valid:
        raise SystemExit('F24 used token identity, role or final freshness differs')
    return {'F24_CALLER': tokens['F24_CALLER']}



CALLER,OTHER,MISSING=9978001,9978002,9978003
USERS=(CALLER,OTHER)
MEMBERS=(CALLER,OTHER)
PAIRS=((CALLER,OTHER),(OTHER,CALLER))
OWNED=(USERS,MEMBERS,PAIRS)
NAMES={CALLER:'F24caller',OTHER:' 原文 F24nickname '}
CREATED='2031-01-01T00:00:00.123456Z'
SUCCESS='요청이 성공적으로 처리되었습니다.'
STORAGE='쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
MEMBER_UNAVAILABLE='회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.'
COUNTERS=('request_checks','page_checks','delete_checks','successful_delete_checks','rejected_delete_checks',
          'authentication_checks','adjacent_denied_checks','storage_unavailable_checks','member_unavailable_checks',
          'recovery_checks','missing_target_checks','approved_database_write_checks')
EXPECTED_JAR_COUNTS=dict(zip(COUNTERS,(12,2,8,5,3,1,2,1,1,2,1,2)))

def require_measurements():
    f2.require_hosted()
    if set(REPORTS)!={TASK} or set(REPORTS[TASK])!={PG_CLASS,MVC_CLASS} or any(type(n) is not int or n<=0 for n in REPORTS[TASK].values()):
        raise SystemExit('F24 original MVC outcomes and case count remain unmeasured')
    if set(EXPECTED_JAR_COUNTS)!=set(COUNTERS) or any(type(n) is not int or n<0 for n in EXPECTED_JAR_COUNTS.values()):
        raise SystemExit('F24 runtime journey counts remain unmeasured')

def prepare_business_rows():
    require_fixture()
    values=','.join(map(str,(*MEMBERS,MISSING)))
    if owned_sql('SELECT (SELECT count(*) FROM pawbridge_user.users WHERE user_id IN ('+values+')) + '
                 '(SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id IN ('+values+')) + '
                 '(SELECT count(*) FROM '+WRITER_MEMBERS+' WHERE member_id IN ('+values+'))')!='0':
        raise SystemExit('F24 reserved User/member/pending keys must all be absent')
    users=','.join('('+str(identity)+','+f4.literal(NAMES[identity])+','+f4.literal('f24-'+str(identity)+'@fixture.invalid')+",'F24 synthetic','GOOGLE','ROLE_USER')" for identity in USERS)
    owned_sql('BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES'+users+'; '
        'INSERT INTO '+WRITER_MEMBERS+'(member_id) VALUES'+','.join('('+str(i)+')' for i in MEMBERS)+'; '
        'INSERT INTO '+WRITER_BLOCKS+'(member_id,blocked_id,created_at) VALUES'
        +','.join('('+str(first)+','+str(second)+','+f4.literal(CREATED)+'::timestamptz)' for first,second in PAIRS)+'; COMMIT;')
    return OWNED

def cleanup_business_rows(owned,created_members):
    f2.require_hosted()
    if owned!=OWNED or created_members not in ((),(MISSING,)):
        raise ValueError('F24 cleanup requires the exact successfully owned seed and observed new member')
    require_owner()
    ids=','.join(map(str,(*MEMBERS,*created_members)))
    if owned_sql('SELECT (SELECT count(*) FROM pawbridge_community.private_notes WHERE sender_id IN ('+ids+') OR recipient_id IN ('+ids+')) + '
                 '(SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id IN ('+ids+'))')!='0':
        raise SystemExit('F24 refuses member cleanup with unexpected note/mailbox references')
    allowed_pairs=','.join('('+str(a)+','+str(b)+')' for a,b in PAIRS)
    if owned_sql('SELECT count(*) FROM '+WRITER_BLOCKS+' WHERE (member_id IN ('+ids+') OR blocked_id IN ('+ids+')) AND (member_id,blocked_id) NOT IN ('+allowed_pairs+')')!='0':
        raise SystemExit('F24 refuses member cleanup with an unowned block edge')
    queries=('DELETE FROM '+WRITER_BLOCKS+' WHERE (member_id,blocked_id) IN ('+allowed_pairs+')',
             'DELETE FROM '+WRITER_MEMBERS+' WHERE member_id IN ('+ids+')',
             'DELETE FROM pawbridge_user.users WHERE (user_id,email) IN ('+','.join('('+str(i)+','+f4.literal('f24-'+str(i)+'@fixture.invalid')+')' for i in USERS)+')')
    for query in queries:
        require_owner()
        owned_sql(query)

def observe_created_members(owned):
    # The successful atomic seed reserved this initially absent namespace. Response assertions are not ownership evidence.
    if owned!=OWNED:raise ValueError('F24 new-member observation requires the successful exact seed')
    require_owner()
    count=owned_sql('SELECT count(*) FROM '+WRITER_MEMBERS+' WHERE member_id='+str(MISSING))
    if count not in ('0','1'):raise SystemExit('F24 owned missing-target member cardinality differs')
    return (MISSING,) if count=='1' else ()

def expected_page(has_block):
    content=[{'memberId':OTHER,'nickname':NAMES[OTHER],'createdAt':CREATED}] if has_block else []
    return {'code':200,'message':SUCCESS,'data':{'content':content,'totalElements':len(content),'totalPages':len(content),'number':0}}

def smoke():
    require_measurements()
    owner=require_fixture();jar=ROOT/'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():raise SystemExit('F24 packaged JAR is missing')
    tokens=load_tokens()
    for value in tokens.values():print('::add-mask::'+value)
    bucket,prefix='f3-'+owner,'f3-'+owner+'/'
    controls={'X-F3-Owner':owner}
    base_env={key:os.environ[key] for key in ('PATH','JAVA_HOME','LANG','LC_ALL','HOME','TMPDIR') if key in os.environ}
    app_env=dict(base_env,F2_JDBC_URL=os.environ['F2_TEST_JDBC_URL'],F2_DB_USER=os.environ['F2_TEST_DB_USER'],
        F2_DB_PASSWORD=os.environ['F2_TEST_DB_PASSWORD'],F2_POOL_MAX_SIZE='1',F3_DB_WRITER_USER=f3.WRITER,
        F3_DB_WRITER_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'],F3_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
        F3_STORAGE_BUCKET=bucket,F3_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid',F3_STORAGE_OBJECT_PREFIX=prefix,
        F3_STORAGE_ACCESS_KEY_ID='f3-synthetic-access',F3_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret',F3_STORAGE_REGION='us-east-1')
    result=dict.fromkeys(COUNTERS,0)
    result.update(status='incomplete',timezone='Asia/Seoul',reader_pool_maximum=1,writer_pool_maximum=1,s3_requests=0,
                  protected_database_changes=0,permitted_delta='one outgoing block removed; one missing-target member inserted')
    EVIDENCE.mkdir(parents=True,exist_ok=True)
    app_path,stub_path=EVIDENCE/'f24-packaged-startup.log',EVIDENCE/'f24-s3-stub.log'
    baseline=database_snapshot();base=endpoint='';owned=();created_members=()
    def snapshot():
        storage=f3.expect(endpoint,'/__f3__/state',200,headers=controls)
        return database_snapshot(),storage['counts'],storage['objects'],len(storage['requests'])
    def check(path,*,method='DELETE',status=200,kind=None,token='F24_CALLER',message=None,layer='business',delta=None,page=None):
        nonlocal created_members
        headers={'X-User-Id':str(OTHER),'X-User-Role':'ROLE_ADMIN'}
        before=snapshot();expected=copy.deepcopy(before[0])
        if token is not None:headers['Authorization']='Bearer '+load_tokens()[token]
        if delta=='remove':
            expected['private_note_blocks']=[row for row in expected['private_note_blocks'] if (row['member_id'],row['blocked_id'])!=(CALLER,OTHER)]
        elif delta=='member':
            if any(row['member_id']==MISSING for row in expected['private_note_members']):raise AssertionError('F24 missing-target row was not absent')
            expected['private_note_members'].append({'member_id':MISSING});expected['private_note_members'].sort(key=lambda row:row['member_id'])
        elif delta is not None:raise ValueError('Unknown F24 permitted request delta')
        actual,raw,received=f3.request(base,path,method=method,headers=headers)
        after=snapshot()
        if after!=(expected,*before[1:]):raise AssertionError('F24 request changed a protected row, failed its exact delta or contacted S3')
        if actual!=status or received.get('Set-Cookie') or received.get('Cache-Control')!=('no-store' if layer=='business' else f19.CACHE_CONTROL):
            raise AssertionError('F24 request status/cache/cookie contract differs')
        if status==200 and method=='DELETE':
            if raw:raise AssertionError('F24 successful original DELETE must remain an empty 200')
        else:
            expected_body=expected_page(page) if page is not None else ({'message':message} if layer=='business' else {'code':status,'message':message,'data':None})
            if json.loads(raw)!=expected_body:raise AssertionError('F24 page or error payload differs from the reviewed contract')
        if delta=='member':created_members=(MISSING,)
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer') AND (state='idle in transaction' OR xact_start IS NOT NULL)")!='0':
            raise AssertionError('F24 request left an active application transaction')
        result['request_checks']+=1
        if method=='DELETE':
            result['delete_checks']+=1;result['successful_delete_checks' if status==200 else 'rejected_delete_checks']+=1
        if kind:result[kind+'_checks']+=1
        if after[0]!=before[0]:result['approved_database_write_checks']+=1
    try:
        with app_path.open('w') as app_log,stub_path.open('w') as stub_log:
            app=stub=None;app_offset=0
            try:
                app,stub,base,endpoint,attempts,app_offset=f4.start_ready_children(jar,app_env,base_env,owner,bucket,prefix,stub_log,app_log,stub_path,app_path)
                result['startup_attempts']=attempts
                if database_snapshot()!=baseline:raise AssertionError('F24 startup changed business rows')
                state=f3.expect(endpoint,'/__f3__/state',200,headers=controls)
                if any(state['counts'].values()) or state['requests'] or state['objects']:raise AssertionError('F24 startup contacted S3')
                owned=prepare_business_rows()
                check('/api/v1/notes/blocks',method='GET',kind='page',page=True)
                check('/api/v1/notes/blocks/'+str(OTHER),delta='remove')
                check('/api/v1/notes/blocks',method='GET',kind='page',page=False)
                check('/api/v1/notes/blocks/'+str(OTHER))
                check('/api/v1/notes/blocks/'+str(MISSING),delta='member',kind='missing_target')
                check('/api/v1/notes/blocks/9223372036854775808',status=401,token=None,message='인증 토큰이 필요합니다.',layer='auth',kind='authentication')
                check('/api/v1/notes/stream',method='GET',status=403,message='접근할 수 없습니다.',layer='route',kind='adjacent_denied')
                check('/api/v1/notes/blocks/'+str(OTHER),method='PUT',status=403,message='접근할 수 없습니다.',layer='route',kind='adjacent_denied')
                with denied_privilege('delete'):check('/api/v1/notes/blocks/'+str(OTHER),status=503,message=STORAGE,kind='storage_unavailable')
                check('/api/v1/notes/blocks/'+str(OTHER),kind='recovery')
                with denied_privilege('nickname'):check('/api/v1/notes/blocks/'+str(OTHER),status=503,message=MEMBER_UNAVAILABLE,kind='member_unavailable')
                check('/api/v1/notes/blocks/'+str(OTHER),kind='recovery')
                require_fixture()
            finally:
                try:
                    if app is not None:result['app_sigterm_exit']=f3.stop_owned_child(app,'F24 packaged application')
                finally:
                    try:
                        if stub is not None:result['stub_sigterm_exit']=f3.stop_owned_child(stub,'F24 S3 stub')
                    finally:
                        if owned:
                            created_members=observe_created_members(owned)
                            cleanup_business_rows(owned,created_members)
        if database_snapshot()!=baseline:raise AssertionError('F24 owned cleanup did not restore all sixteen tables')
        if app_path.stat().st_size>1024*1024 or stub_path.stat().st_size>1024*1024:raise AssertionError('F24 child log budget exceeded')
        text=f4.fresh_log(app_path,app_offset)
        if text.count('Shutdown completed.')<2 or 'Graceful shutdown complete' not in text:raise AssertionError('F24 normal HTTP/two-pool shutdown evidence is missing')
        forbidden=(os.environ['F2_TEST_DB_PASSWORD'],os.environ['F3_TEST_WRITER_PASSWORD'],os.environ['F3_TEST_JWT_SECRET'],
                   'F24 synthetic',*tokens.values(),*(name.strip() for name in NAMES.values() if name.strip()),
                   *('f24-'+str(i)+'@fixture.invalid' for i in USERS))
        if any(value in text for value in forbidden):raise AssertionError('F24 application log exposed credentials, tokens or synthetic private data')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_f2_reader','pawbridge_f3_writer')")!='0':
            raise AssertionError('F24 application connections remain after shutdown')
        if {key:result[key] for key in COUNTERS}!=EXPECTED_JAR_COUNTS:raise AssertionError('F24 packaged request counts differ')
        result.update(status='passed',shutdown_pools=2,remaining_application_connections=0)
    finally:(EVIDENCE/'f24-packaged-result.txt').write_text(json.dumps(result,indent=2)+'\n')
    print('Hosted F24 exact owned unblock journey, protected snapshots and S3 boundary passed')

if __name__=='__main__':
    f2.require_hosted()
    {'prepare':prepare,'results':verify_results,'smoke':smoke}[sys.argv[1]]()
