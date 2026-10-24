#!/usr/bin/env python3
"""F25 hosted-only block creation fixture and runtime evidence; no local database is permitted."""
from contextlib import contextmanager
import base64
from datetime import datetime, timezone
import re
import copy
import math
import stat
import time
import json
import os
from pathlib import Path
import subprocess
import sys

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f19_ci as f19
import f20_ci as f20
import f21_ci as f21
import f22_ci as f22
import f23_ci as f23
import f24_ci as f24

ROOT,EVIDENCE=f3.ROOT,f3.EVIDENCE
SOURCE='f06a72a06bd26662c647bde780dfc7a27bf7349c'
WRITER_MEMBERS='pawbridge_community.private_note_members'
WRITER_BLOCKS='pawbridge_community.private_note_blocks'

def owned_sql(query='',*,input_text=None):
    try:
        return f3.sql(query,input_text=input_text) if input_text is not None else f3.sql(query)
    except subprocess.CalledProcessError:
        raise SystemExit('F25 owned fixture SQL failed without exposing command, SQL or output') from None

def fixture_proof(owner):
    return ('owner='+owner+'\nsource_sha='+SOURCE+'\n'
            'fixture=F24 member-lock/outgoing-block writer plus block INSERT3\n'
            'new_business_ddl=0\nnew_schema=0\nnew_marker=0\n'
            'writer_columns=members:SELECT,INSERT,UPDATE(member_id);blocks:SELECT(member_id,blocked_id),DELETE,INSERT(member_id,blocked_id,created_at)\n'
            'new_sequence_acl=0\n')

def require_prior_receipts(owner):
    # Identity and receipts only, including during deliberate writer/User denial.
    f24.require_prior_receipts(owner)
    f21.check_receipt(EVIDENCE/'f24-fixture-write.txt',f24.fixture_proof(owner))

def require_owner():
    # F24 owner is ACL-safe for the new block INSERT grant; never call its old strict checker here.
    owner=f24.require_owner()
    f21.check_receipt(EVIDENCE/'f25-fixture-write.txt',fixture_proof(owner))
    return owner

def catalog(query,stage):
    if stage not in ('shape','acl','constraints') or not query.startswith('SELECT '):
        raise ValueError('F25 catalog is restricted to static read-only metadata')
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
            raise SystemExit('F25 inherited fixture lacks the exact original shape/defaults: ' + table)
        predicate = 'false' if not readable else 'column_name IN (' + ','.join(f4.literal(c) for c in readable) + ')'
        writer_member = "column_name='member_id'" if table == 'private_note_members' else 'false'
        writer_read = writer_member if table == 'private_note_members' else "column_name IN ('member_id','blocked_id')" if table == 'private_note_blocks' else 'false'
        writer_insert = "column_name IN ('member_id','blocked_id','created_at')" if table == 'private_note_blocks' else writer_member
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and("
                "has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'SELECT')=(" + predicate + ') '
                "AND NOT has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'INSERT,UPDATE,REFERENCES') "
                "AND has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'SELECT')=(" + writer_read + ") "
                "AND has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'INSERT')=(" + writer_insert + ") "
                "AND has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'UPDATE')=(" + writer_member + ") "
                "AND NOT has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'REFERENCES') "
                "AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL) "
                "AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER') "
                "AND has_table_privilege('pawbridge_f3_writer','" + relation + "','DELETE')=" + ("true" if table == 'private_note_blocks' else "false")
                + where, 'acl') != 't':
            raise SystemExit('F25 inherited fixture lacks exact exact pending/blocks/member-lock writer/no-sequence ACL: ' + table)
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
            raise SystemExit('F25 inherited fixture lacks exact validated PK/FK/CHECK/index definitions: ' + table)
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
            raise SystemExit('F25 inherited fixture differs from the exact original table shape: ' + table)
        allowed = 'column_name IN (' + ','.join(f4.literal(column) for column in f22.READABLE[table]) + ')'
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and(has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'SELECT')=(" + allowed + ") AND NOT has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'INSERT,UPDATE,REFERENCES') AND NOT has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'SELECT,INSERT,UPDATE,REFERENCES') AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL) "
                   "AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                   "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')" + where, 'acl') != 't':
            raise SystemExit('F25 inherited fixture lacks exact notes-eight/mailboxes-six/writer-zero/no-sequence privileges')
        if catalog('SELECT ' + ' AND '.join(f21.constraint_checks(table) + f21.index_checks(table)), 'constraints') != 't':
            raise SystemExit('F25 inherited fixture differs from the original validated PK/FK/CHECK/index definitions: ' + table)
    return owner


# Fixed late-stage F25 projection: raw rows and the original PostgreSQL ORDER BY expressions.
# F24 and earlier helpers keep their original snapshots; do not import a later stage here.
SNAPSHOT_TABLES = (
    ('users','pawbridge_user.users','user_id'), ('favorites','pawbridge_user.favorites','favorite_id'),
    ('posts','pawbridge_community.posts','post_id'), ('comments','pawbridge_community.comments','comment_id'),
    ('f4comments','pawbridge_f4_fixture.comments','comment_id'), ('notes','pawbridge_f4_fixture.private_notes','note_id'),
    ('outbox','pawbridge_community.outbox_events','outbox_id'), ('search','pawbridge_community.post_search_documents','post_id'),
    ('videos','pawbridge_community.home_videos','id'), ('board','pawbridge_community.home_video_board','id'),
    ('daily_visit_count','pawbridge_community.daily_visit_count','visit_date'),
    ('contact_deletions','pawbridge_user.contact_deletions','user_id'),
    ('private_note_members','pawbridge_community.private_note_members','member_id'),
    ('private_note_blocks','pawbridge_community.private_note_blocks','member_id,blocked_id'),
    ('private_notes','pawbridge_community.private_notes','note_id'),
    ('private_note_mailboxes','pawbridge_community.private_note_mailboxes','member_id,note_id'),
)


def old_reference_snapshot():
    value=f24.database_snapshot()
    if len(value)!=16:raise SystemExit('F25 requires all sixteen inherited tables')
    return value


def _snapshot_bytes(value):
    if (type(value) is not dict or len(value)!=16 or set(value)!={row[0] for row in SNAPSHOT_TABLES}
            or any(type(rows) is not list or any(type(row) is not dict for row in rows) for rows in value.values())):
        raise SystemExit('F25 requires exactly sixteen raw-row table snapshots')
    try:
        # Object key order is immaterial; array order and bool/int/float/null/string values remain exact.
        return json.dumps(value,sort_keys=True,ensure_ascii=False,allow_nan=False,separators=(',',':')).encode('utf-8')
    except (TypeError,ValueError,UnicodeError):
        raise SystemExit('F25 snapshot contains a malformed JSON value') from None


def database_snapshot():
    f2.require_hosted()
    if len(SNAPSHOT_TABLES)!=16 or len({row[0] for row in SNAPSHOT_TABLES})!=16:
        raise SystemExit('F25 fixed snapshot table definitions differ')
    fields=[f4.literal(name)+',(SELECT COALESCE(json_agg(r ORDER BY '+order
            +"),'[]'::json) FROM "+relation+' r)' for name,relation,order in SNAPSHOT_TABLES]
    try:
        value=json.loads(owned_sql('SELECT json_build_object('+','.join(fields)+')'))
    except (ValueError,TypeError):
        raise SystemExit('F25 complete sixteen-table snapshot is malformed') from None
    _snapshot_bytes(value)
    return value


def compare_snapshots(boundary,expected=None):
    f2.require_hosted()
    if boundary not in ('before_start','after_stop'):
        raise ValueError('F25 snapshot comparison requires a fixed quiescent boundary')
    reference=_snapshot_bytes(expected) if expected is not None else None
    samples=[]
    # Balanced small sample, not an estimate of whole-workflow savings. No writes between calls.
    for mode in ('old','new','new','old'):
        started=time.perf_counter()
        value=old_reference_snapshot() if mode=='old' else database_snapshot()
        elapsed=time.perf_counter()-started
        encoded=_snapshot_bytes(value)
        if reference is None:reference=encoded
        if encoded!=reference:
            raise SystemExit('F25 quiescent old/new snapshot values, JSON types or array order differ')
        samples.append({'mode':mode,'wall_seconds':elapsed,'snapshot_calls':1,
                        'row_count':sum(len(rows) for rows in value.values()),'canonical_json_bytes':len(encoded),
                        'static_business_selects':7 if mode=='old' else 1,
                        'static_marker_selects':7 if mode=='old' else 1,
                        'static_docker_subprocesses':21 if mode=='old' else 3})
    return {'status':'passed','boundary':boundary,'tables':16,'order':['old','new','new','old'],
            'call_counts_basis':'static source path, not observed subprocess instrumentation','samples':samples}


def prepare():
    f2.require_hosted()
    owner=f24.require_fixture()  # Last old INSERT-zero strict check, before extension only.
    target=EVIDENCE/'f25-fixture-write.txt';proof=fixture_proof(owner)
    f21.check_receipt(target,proof,allow_missing=True)
    if target.exists():raise SystemExit('F25 refuses an existing writer extension receipt')
    if owned_sql("SELECT NOT has_any_column_privilege('pawbridge_f3_writer','"+WRITER_BLOCKS+"','INSERT')")!='t':
        raise SystemExit('F25 requires original block INSERT-zero before extension')
    before=database_snapshot()
    owned_sql(input_text='BEGIN;\nGRANT INSERT(member_id,blocked_id,created_at) ON '+WRITER_BLOCKS+' TO pawbridge_f3_writer;\nCOMMIT;\n')
    target.write_text(proof)
    require_fixture()
    if database_snapshot()!=before:raise SystemExit('F25 permission extension changed business rows')
    print('Extended only three existing block INSERT columns after the complete F24 stage')


@contextmanager
def denied_privilege(kind):
    owner=require_fixture()
    clauses={'insert':('INSERT(member_id,blocked_id,created_at) ON '+WRITER_BLOCKS,'pawbridge_f3_writer'),
             'nickname':('SELECT(nickname) ON pawbridge_user.users','pawbridge_f2_reader')}
    if kind not in clauses:raise ValueError('Only the planned F25 INSERT or User denial is permitted')
    clause,role=clauses[kind]
    owned_sql('REVOKE '+clause+' FROM '+role)
    try:yield
    finally:
        require_prior_receipts(owner)
        f21.check_receipt(EVIDENCE/'f25-fixture-write.txt',fixture_proof(owner))
        owned_sql('GRANT '+clause+' TO '+role)
        require_fixture()


def require_measurements():
    f2.require_hosted()
    if set(EXPECTED_JAR_COUNTS)!=set(COUNTERS) or any(type(n) is not int or n<0 for n in EXPECTED_JAR_COUNTS.values()):
        raise SystemExit('F25 packaged journey counts remain unmeasured')


CALLER,OTHER=9979001,9979002
USERS=(CALLER,OTHER)
OWNED=USERS  # Returned only after the atomic User seed; both member keys were initially absent.
PAIR=(CALLER,OTHER)
NAMES={CALLER:'F25caller',OTHER:' 原文 F25nickname '}
SUCCESS='요청이 성공적으로 처리되었습니다.'
STORAGE='쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
MEMBER_UNAVAILABLE='회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.'
BLOCKED='이 상대에게 쪽지를 보낼 수 없습니다.'
COUNTERS=('request_checks','put_checks','successful_put_checks','rejected_put_checks','delete_checks',
          'get_checks','page_checks','status_checks','recipient_checks','duplicate_checks',
          'storage_unavailable_checks','member_unavailable_checks','recovery_checks','authentication_checks',
          'adjacent_denied_checks','approved_database_write_checks')
EXPECTED_JAR_COUNTS=dict(zip(COUNTERS,(18,8,4,4,3,7,2,2,2,1,1,1,2,1,2,6)))


def load_tokens():
    owner=f2.require_hosted();path=Path(os.environ['F3_TEST_TOKENS_PATH'])
    wanted=Path(os.environ['RUNNER_TEMP']).resolve()/('f3-tokens-'+owner+'.json')
    if path.is_symlink() or path.resolve()!=wanted:raise SystemExit('F25 token path is not owned by this run')
    try:
        mode=path.stat().st_mode
        if not stat.S_ISREG(mode) or stat.S_IMODE(mode)!=0o600:raise SystemExit('F25 used token file must remain regular 0600')
        value=json.loads(path.read_text())['F25_CALLER'];parts=value.split('.')
        claims=json.loads(base64.urlsafe_b64decode(parts[1]+'='*(-len(parts[1])%4)))
        valid=(len(parts)==3 and type(claims['userId']) is int and claims['userId']==CALLER
               and claims['role']=='ROLE_USER' and type(claims['exp']) in (int,float)
               and math.isfinite(claims['exp']) and claims['exp']>=time.time()+60)
    except (OSError,AttributeError,KeyError,IndexError,TypeError,ValueError):
        raise SystemExit('F25 used token identity or freshness is invalid') from None
    if not valid:raise SystemExit('F25 used token identity, role or freshness differs')
    return {'F25_CALLER':value}


def prepare_business_rows():
    require_fixture();ids=','.join(map(str,USERS))
    if owned_sql('SELECT (SELECT count(*) FROM pawbridge_user.users WHERE user_id IN ('+ids+')) + '
                 '(SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id IN ('+ids+')) + '
                 '(SELECT count(*) FROM '+WRITER_MEMBERS+' WHERE member_id IN ('+ids+'))')!='0':
        raise SystemExit('F25 reserved User/pending/member keys must all be absent')
    users=','.join('('+str(i)+','+f4.literal(NAMES[i])+','+f4.literal('f25-'+str(i)+'@fixture.invalid')
                   +",'F25 synthetic','GOOGLE','ROLE_USER')" for i in USERS)
    # No member/block pre-seed: first actual PUT must create only these reserved member keys and outgoing row.
    owned_sql('BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES'+users+'; COMMIT;')
    return OWNED


def observe_created_members(owned):
    if owned!=OWNED:raise ValueError('F25 member observation requires the successful exact seed')
    require_owner();ids=','.join(map(str,USERS))
    value=json.loads(owned_sql("SELECT COALESCE(json_agg(member_id ORDER BY member_id),'[]'::json) FROM "+WRITER_MEMBERS+' WHERE member_id IN ('+ids+')'))
    if type(value) is not list or any(type(i) is not int or i not in USERS for i in value) or value!=sorted(set(value)):
        raise SystemExit('F25 reserved member observation differs')
    return tuple(value)


def cleanup_business_rows(owned,created_members):
    f2.require_hosted()
    if owned!=OWNED or type(created_members) is not tuple or any(type(i) is not int or i not in USERS for i in created_members) or list(created_members)!=sorted(set(created_members)):
        raise ValueError('F25 cleanup requires exact successful User keys and observed member keys')
    require_owner();ids=','.join(map(str,USERS))
    if owned_sql('SELECT (SELECT count(*) FROM pawbridge_community.private_notes WHERE sender_id IN ('+ids+') OR recipient_id IN ('+ids+')) + '
                 '(SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id IN ('+ids+')) + '
                 '(SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id IN ('+ids+'))')!='0':
        raise SystemExit('F25 refuses cleanup with unexpected note/mailbox/deletion references')
    pair='('+str(CALLER)+','+str(OTHER)+')'
    if owned_sql('SELECT count(*) FROM '+WRITER_BLOCKS+' WHERE (member_id IN ('+ids+') OR blocked_id IN ('+ids+')) AND (member_id,blocked_id) NOT IN ('+pair+')')!='0':
        raise SystemExit('F25 refuses cleanup with an unowned block edge')
    queries=['DELETE FROM '+WRITER_BLOCKS+' WHERE (member_id,blocked_id)='+pair]
    if created_members:queries.append('DELETE FROM '+WRITER_MEMBERS+' WHERE member_id IN ('+','.join(map(str,created_members))+')')
    queries.append('DELETE FROM pawbridge_user.users WHERE (user_id,email) IN ('+','.join('('+str(i)+','+f4.literal('f25-'+str(i)+'@fixture.invalid')+')' for i in USERS)+')')
    for query in queries:
        require_owner();owned_sql(query)


def timestamp(value,wire=False):
    ending='Z' if wire else r'(?:Z|[+-]\d{2}:\d{2})'
    if type(value) is not str or not re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?'+ending,value):
        raise AssertionError('F25 timestamp must be a microsecond ISO Instant, with Z on the wire')
    return datetime.fromisoformat(value.replace('Z','+00:00')).astimezone(timezone.utc)


def expected_page(created_at):
    return {'code':200,'message':SUCCESS,'data':{'content':[{'memberId':OTHER,'nickname':NAMES[OTHER],'createdAt':created_at}],
            'totalElements':1,'totalPages':1,'number':0}}


def smoke():
    require_measurements();owner=require_fixture();jar=ROOT/'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():raise SystemExit('F25 packaged JAR is missing')
    tokens=load_tokens()
    for value in tokens.values():print('::add-mask::'+value)
    bucket,prefix='f3-'+owner,'f3-'+owner+'/'
    controls={'X-F3-Owner':owner}
    base_env={k:os.environ[k] for k in ('PATH','JAVA_HOME','LANG','LC_ALL','HOME','TMPDIR') if k in os.environ}
    app_env=dict(base_env,PAWBRIDGE_DB_URL=os.environ['F2_TEST_JDBC_URL'],PAWBRIDGE_DB_USER=f3.APP,
        PAWBRIDGE_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'],PAWBRIDGE_DB_POOL_MAX_SIZE='4',
        PAWBRIDGE_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
        PAWBRIDGE_STORAGE_BUCKET=bucket,PAWBRIDGE_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid',PAWBRIDGE_STORAGE_OBJECT_PREFIX=prefix,
        PAWBRIDGE_STORAGE_ACCESS_KEY_ID='f3-synthetic-access',PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret',PAWBRIDGE_STORAGE_REGION='us-east-1')
    result=dict.fromkeys(COUNTERS,0)
    result.update(status='incomplete',timezone='Asia/Seoul',pool_maximum=4,
                  protected_database_changes=0,s3_requests=0,permitted_delta='one outgoing block and initially absent reserved member keys',snapshot_comparisons={})
    EVIDENCE.mkdir(parents=True,exist_ok=True)
    app_path,stub_path=EVIDENCE/'f25-packaged-startup.log',EVIDENCE/'f25-s3-stub.log'
    baseline=database_snapshot();verified_database=baseline;journey_verified=False;owned=();base=endpoint='';first_created_at=None
    def snapshot():
        state=f3.expect(endpoint,'/__f3__/state',200,headers=controls)
        return database_snapshot(),state['counts'],state['objects'],len(state['requests'])
    def check(path,*,method='PUT',status=200,token='F25_CALLER',message=None,layer='business',kind=None,delta=None,view=None):
        nonlocal first_created_at,verified_database
        headers={'X-User-Id':str(OTHER),'X-User-Role':'ROLE_ADMIN'}
        before=snapshot();expected=copy.deepcopy(before[0])
        if token is not None:headers['Authorization']='Bearer '+load_tokens()[token]
        actual,raw,received=f3.request(base,path,method=method,headers=headers)
        after=snapshot()
        if delta=='insert':
            if any((r['member_id'],r['blocked_id'])==PAIR for r in expected['private_note_blocks']):raise AssertionError('F25 insert requires absent outgoing key')
            rows=[r for r in after[0]['private_note_blocks'] if (r.get('member_id'),r.get('blocked_id'))==PAIR]
            if len(rows)!=1 or set(rows[0])!={'member_id','blocked_id','created_at'} or any(type(rows[0][k]) is not int for k in ('member_id','blocked_id')):
                raise AssertionError('F25 inserted outgoing row shape differs')
            timestamp(rows[0]['created_at'])
            expected['private_note_blocks'].append(copy.deepcopy(rows[0]));expected['private_note_blocks'].sort(key=lambda r:(r['member_id'],r['blocked_id']))
            for identity in USERS:
                if not any(r['member_id']==identity for r in expected['private_note_members']):expected['private_note_members'].append({'member_id':identity})
            expected['private_note_members'].sort(key=lambda r:r['member_id'])
            if first_created_at is None:first_created_at=rows[0]['created_at']
        elif delta=='remove':
            expected['private_note_blocks']=[r for r in expected['private_note_blocks'] if (r['member_id'],r['blocked_id'])!=PAIR]
        elif delta is not None:raise ValueError('Unknown F25 permitted request delta')
        if after!=(expected,*before[1:]):raise AssertionError('F25 request changed protected rows, its exact delta or S3')
        if actual!=status or received.get('Set-Cookie') or received.get('Cache-Control')!=('no-store' if layer=='business' else f19.CACHE_CONTROL):
            raise AssertionError('F25 status/cache/cookie differs')
        if status==200 and method in ('PUT','DELETE'):
            if raw:raise AssertionError('F25 mutation must preserve an empty original 200')
        else:
            body=json.loads(raw)
            if view=='page':
                if first_created_at is None:raise AssertionError('F25 page has no previously inserted owned timestamp')
                try:
                    item=body['data']['content'][0]
                    if timestamp(item['createdAt'],wire=True)!=timestamp(first_created_at):raise AssertionError('F25 duplicate or page shifted the original Instant')
                    if any(type(body['data'][k]) is not int for k in ('totalElements','totalPages','number')) or type(item['memberId']) is not int:raise AssertionError('F25 numeric page fields differ')
                    body=copy.deepcopy(body);body['data']['content'][0]['createdAt']=first_created_at
                except (KeyError,IndexError,TypeError):raise AssertionError('F25 page shape differs') from None
                wanted=expected_page(first_created_at)
            elif view in ('blocked','unblocked'):wanted={'code':200,'message':SUCCESS,'data':{'memberIds':[OTHER] if view=='blocked' else []}}
            elif view=='recipient':
                wanted={'code':200,'message':SUCCESS,'data':{'userId':OTHER,'nickname':NAMES[OTHER],'active':True,'deletionPending':False}}
                if type(body.get('data',{}).get('userId')) is not int or type(body.get('data',{}).get('active')) is not bool or type(body.get('data',{}).get('deletionPending')) is not bool:raise AssertionError('F25 recipient identifiers or flags differ')
            else:wanted={'message':message} if layer=='business' else {'code':status,'message':message,'data':None}
            if body!=wanted:raise AssertionError('F25 exact payload differs')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') AND (state='idle in transaction' OR xact_start IS NOT NULL)")!='0':raise AssertionError('F25 request left an active transaction')
        verified_database=copy.deepcopy(after[0])
        result['request_checks']+=1
        if method=='PUT':
            result['put_checks']+=1;result['successful_put_checks' if status==200 else 'rejected_put_checks']+=1
        if method=='GET':result['get_checks']+=1
        if method=='DELETE':result['delete_checks']+=1
        if kind:result[kind+'_checks']+=1
        if after[0]!=before[0]:result['approved_database_write_checks']+=1
    try:
        result['snapshot_comparisons']['before_start']=compare_snapshots('before_start',baseline)
        with app_path.open('w') as app_log,stub_path.open('w') as stub_log:
            app=stub=None;offset=0;app_stopped=stub_stopped=False
            try:
                app,stub,base,endpoint,attempts,offset=f4.start_ready_children(jar,app_env,base_env,owner,bucket,prefix,stub_log,app_log,stub_path,app_path)
                result['startup_attempts']=attempts
                if database_snapshot()!=baseline:raise AssertionError('F25 startup changed business rows')
                state=f3.expect(endpoint,'/__f3__/state',200,headers=controls)
                if any(state['counts'].values()) or state['requests'] or state['objects']:raise AssertionError('F25 startup contacted S3')
                owned=prepare_business_rows()
                path='/api/v1/notes/blocks/'+str(OTHER)
                check(path,delta='insert')
                check('/api/v1/notes/blocks',method='GET',kind='page',view='page')
                check('/api/v1/notes/blocks/status?memberIds='+str(OTHER),method='GET',kind='status',view='blocked')
                check('/api/v1/notes/recipients/'+str(OTHER),method='GET',status=403,message=BLOCKED,kind='recipient')
                check(path,kind='duplicate')
                check('/api/v1/notes/blocks',method='GET',kind='page',view='page')
                check(path,method='DELETE',delta='remove')
                check('/api/v1/notes/blocks/status?memberIds='+str(OTHER),method='GET',kind='status',view='unblocked')
                check('/api/v1/notes/recipients/'+str(OTHER),method='GET',kind='recipient',view='recipient')
                with denied_privilege('insert'):check(path,status=503,message=STORAGE,kind='storage_unavailable')
                check(path,delta='insert',kind='recovery')
                check(path,method='DELETE',delta='remove')
                with denied_privilege('nickname'):check(path,status=503,message=MEMBER_UNAVAILABLE,kind='member_unavailable')
                check(path,delta='insert',kind='recovery')
                check(path,method='DELETE',delta='remove')
                check('/api/v1/notes/blocks/9223372036854775808',status=401,token=None,message='인증 토큰이 필요합니다.',layer='auth',kind='authentication')
                check('/api/v1/notes/stream/extra',method='GET',status=403,message='접근할 수 없습니다.',layer='route',kind='adjacent_denied')
                check(path+'/extra',status=403,message='접근할 수 없습니다.',layer='route',kind='adjacent_denied')
                require_fixture();journey_verified=True
            finally:
                try:
                    if app is not None:
                        result['app_sigterm_exit']=f3.stop_owned_child(app,'F25 packaged application');app_stopped=True
                finally:
                    try:
                        if stub is not None:
                            result['stub_sigterm_exit']=f3.stop_owned_child(stub,'F25 S3 stub');stub_stopped=True
                    finally:
                        try:
                            if app_stopped and stub_stopped:
                                result['snapshot_comparisons']['after_stop']=compare_snapshots('after_stop',verified_database if journey_verified else None)
                        finally:
                            if owned:cleanup_business_rows(owned,observe_created_members(owned))
        if database_snapshot()!=baseline:raise AssertionError('F25 cleanup did not restore all sixteen tables')
        if (set(result['snapshot_comparisons'])!={'before_start','after_stop'}
                or any(type(proof) is not dict or proof.get('status')!='passed' for proof in result['snapshot_comparisons'].values())):
            raise AssertionError('F25 quiescent snapshot comparison evidence is incomplete')
        if app_path.stat().st_size>1024*1024 or stub_path.stat().st_size>1024*1024:raise AssertionError('F25 child log budget exceeded')
        text=f4.fresh_log(app_path,offset)
        if text.count('Shutdown completed.')!=1 or 'Graceful shutdown complete' not in text:raise AssertionError('F25 exact single-pool graceful shutdown evidence missing')
        forbidden=(os.environ['F2_TEST_DB_PASSWORD'],os.environ['F3_TEST_WRITER_PASSWORD'],os.environ['F3_TEST_JWT_SECRET'],
                   'F25 synthetic',*tokens.values(),*(name.strip() for name in NAMES.values() if name.strip()),
                   *('f25-'+str(i)+'@fixture.invalid' for i in USERS))
        if any(v in text or v in stub_path.read_text() for v in forbidden):raise AssertionError('F25 child log exposed private values')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')")!='0':raise AssertionError('F25 application connections remain')
        if {k:result[k] for k in COUNTERS}!=EXPECTED_JAR_COUNTS:raise AssertionError('F25 packaged request counts differ')
        result.update(status='passed',shutdown_pools=1,remaining_application_connections=0)
    finally:(EVIDENCE/'f25-packaged-result.txt').write_text(json.dumps(result,indent=2)+'\n')
    print('Hosted F25 create/duplicate/read/remove journey and protected snapshots passed')


if __name__=='__main__':
    f2.require_hosted()
    {'prepare':prepare,'smoke':smoke}[sys.argv[1]]()

