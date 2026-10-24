#!/usr/bin/env python3
"""F27 hosted fixture preparation and packaged favorite smoke.

No local PostgreSQL, HTTP or JVM is started by preparation. Packaged journey counts fail closed.
"""
from contextlib import contextmanager
import subprocess

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f20_ci as f20
import f21_ci as f21
import f22_ci as f22
import f26_ci as f26

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
SOURCE = 'f06a72a06bd26662c647bde780dfc7a27bf7349c'
COUNTERS = ('request_checks', 'nonstreaming_checks', 'put_checks', 'successful_put_checks', 'rejected_put_checks', 'get_checks', 'notification_checks', 'detail_checks', 'list_checks', 'stream_open_checks', 'stream_ready_checks', 'resync_checks', 'other_resync_checks', 'storage_unavailable_checks', 'member_unavailable_checks', 'recovery_checks', 'authentication_checks', 'adjacent_denied_checks', 'approved_database_write_checks')
# Reviewed static runtime contract; actual PG/socket/JAR execution remains pending.
EXPECTED_JAR_COUNTS = {'request_checks': 18, 'nonstreaming_checks': 16, 'put_checks': 10, 'successful_put_checks': 6, 'rejected_put_checks': 4, 'get_checks': 8, 'notification_checks': 2, 'detail_checks': 1, 'list_checks': 3, 'stream_open_checks': 2, 'stream_ready_checks': 2, 'resync_checks': 6, 'other_resync_checks': 0, 'storage_unavailable_checks': 1, 'member_unavailable_checks': 1, 'recovery_checks': 2, 'authentication_checks': 1, 'adjacent_denied_checks': 1, 'approved_database_write_checks': 5}
NOTES = 'pawbridge_community.private_notes'
MAILBOXES = 'pawbridge_community.private_note_mailboxes'


def owned_sql(query='', *, input_text=None):
    f2.require_hosted()
    try:
        return f3.sql(query, input_text=input_text) if input_text is not None else f3.sql(query)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        raise SystemExit('F27 owned fixture SQL failed without exposing command, SQL or output') from None


def fixture_proof(owner):
    return ('owner=' + owner + '\nsource_sha=' + SOURCE + '\n'
            'fixture=F26 plus own-note favorite writer\n'
            'new_business_ddl=0\nnew_schema=0\nnew_marker=0\n'
            'writer_columns=members:SELECT,INSERT,UPDATE(member_id);'
            'blocks:SELECT(member_id,blocked_id),DELETE,INSERT(member_id,blocked_id,created_at);'
            'notes:SELECT(note_id,expires_at),UPDATE(note_id);'
            'mailboxes:SELECT(member_id,note_id,direction,read_at),UPDATE(read_at,favorite)\n'
            'new_sequence_acl=0\n')


def require_prior_receipts(owner):
    # Marker identity only during intentional User/writer denial, never an old strict ACL check.
    f2.require_hosted()
    f26.require_prior_receipts(owner)
    f21.check_receipt(EVIDENCE / 'f26-fixture-write.txt', f26.fixture_proof(owner))


def require_owner():
    f2.require_hosted()
    owner = f26.require_owner()  # User-seven/old identities remain valid after notes writer grants.
    f21.check_receipt(EVIDENCE / 'f27-fixture-write.txt', fixture_proof(owner))
    return owner


def catalog(query, stage):
    if stage not in ('shape', 'acl', 'constraints') or not query.startswith('SELECT '):
        raise ValueError('F27 catalog is restricted to static read-only metadata')
    return owned_sql(query)


def require_fixture():
    owner = require_owner()
    # Explicit F26 pending/member/block contract; no old pre-favorite writer checker or monkeypatch.
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
            raise SystemExit('F27 inherited pending/member/block shape or owner differs')
        reader = 'false' if not readable else 'column_name IN (' + ','.join(f4.literal(c) for c in readable) + ')'
        writer_member = "column_name='member_id'" if table == 'private_note_members' else 'false'
        writer_read = writer_member if table == 'private_note_members' else "column_name IN ('member_id','blocked_id')" if table == 'private_note_blocks' else 'false'
        writer_insert = "column_name IN ('member_id','blocked_id','created_at')" if table == 'private_note_blocks' else writer_member
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and("
                "has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'SELECT')=(" + reader + ') '
                "AND NOT has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'INSERT,UPDATE,REFERENCES') "
                "AND has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'SELECT')=(" + writer_read + ") "
                "AND has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'INSERT')=(" + writer_insert + ") "
                "AND has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'UPDATE')=(" + writer_member + ") "
                "AND NOT has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'REFERENCES') "
                "AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL) "
                "AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER') "
                "AND has_table_privilege('pawbridge_f3_writer','" + relation + "','DELETE')=" + ('true' if table == 'private_note_blocks' else 'false')
                + where, 'acl') != 't':
            raise SystemExit('F27 inherited pending/member/block exact ACL differs')
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
            raise SystemExit('F27 inherited pending/member/block PK/FK/CHECK/index differs')
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
            raise SystemExit('F27 notes/mailboxes shape, defaults or owner differs')
        reader = 'column_name IN (' + ','.join(f4.literal(c) for c in f22.READABLE[table]) + ')'
        writer_read = "column_name IN ('note_id','expires_at')" if table == 'private_notes' else "column_name IN ('member_id','note_id','direction','read_at')"
        writer_update = "column_name='note_id'" if table == 'private_notes' else "column_name IN ('read_at','favorite')"
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and(has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'SELECT')=(" + reader + ") AND NOT has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'INSERT,UPDATE,REFERENCES') AND has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'SELECT')=(" + writer_read + ") AND has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'UPDATE')=(" + writer_update + ") AND NOT has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'INSERT,REFERENCES') AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL) "
                   "AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                   "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')" + where, 'acl') != 't':
            raise SystemExit('F27 notes-two/mailboxes-four SELECT and exact favorite/read_at UPDATE ACL differs')
        if catalog('SELECT ' + ' AND '.join(f21.constraint_checks(table) + f21.index_checks(table)), 'constraints') != 't':
            raise SystemExit('F27 notes/mailboxes validated PK/FK/CHECK/index differs')
    return owner


def database_snapshot():
    f2.require_hosted()
    value = f26.database_snapshot()
    if not isinstance(value, dict) or len(value) != 16:
        raise SystemExit('F27 requires all sixteen inherited business tables')
    return value


def prepare():
    f2.require_hosted()
    owner = f26.require_fixture()  # The last old read-only-mutation exact writer strict check, before favorite is granted.
    target = EVIDENCE / 'f27-fixture-write.txt'
    proof = fixture_proof(owner)
    f21.check_receipt(target, proof, allow_missing=True)
    if target.exists():
        raise SystemExit('F27 refuses an existing writer extension receipt')
    before = database_snapshot()
    owned_sql(input_text='BEGIN;\nGRANT UPDATE(favorite) ON ' + MAILBOXES + ' TO pawbridge_f3_writer;\nCOMMIT;\n')
    with target.open('x') as output:
        output.write(proof)
    require_fixture()
    if database_snapshot() != before:
        raise SystemExit('F27 permission extension changed existing business rows')
    print('Extended only mailbox UPDATE(favorite) after the complete F26 stage')


@contextmanager
def denied_privilege(kind):
    clauses = {'favorite': ('UPDATE(favorite) ON ' + MAILBOXES, 'pawbridge_f3_writer'),
               'nickname': ('SELECT(nickname) ON pawbridge_user.users', 'pawbridge_f2_reader')}
    if kind not in clauses:
        raise ValueError('Only the planned F27 favorite or User nickname denial is permitted')
    owner = require_fixture()
    clause, role = clauses[kind]
    owned_sql('REVOKE ' + clause + ' FROM ' + role)
    try:
        yield
    finally:
        require_prior_receipts(owner)
        f21.check_receipt(EVIDENCE / 'f27-fixture-write.txt', fixture_proof(owner))
        owned_sql('GRANT ' + clause + ' TO ' + role)
        require_fixture()


def require_measurements(kind='jar'):
    f2.require_hosted()
    if kind != 'jar':
        raise ValueError('F27 measurement gate is limited to the packaged journey')
    if (not COUNTERS or not isinstance(EXPECTED_JAR_COUNTS, dict)
            or set(EXPECTED_JAR_COUNTS) != set(COUNTERS)
            or any(type(n) is not int or n < 0 for n in EXPECTED_JAR_COUNTS.values())):
        raise SystemExit('F27 packaged journey counts remain unmeasured')


def smoke():
    f2.require_hosted()
    import sys
    from f27_stream_probe import run
    return run(sys.modules[__name__])


if __name__ == "__main__":
    import sys
    f2.require_hosted()
    {"prepare": prepare, "smoke": smoke}[sys.argv[1]]()
