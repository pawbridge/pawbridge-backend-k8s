#!/usr/bin/env python3
"""F28 hosted fixture preparation and packaged mailbox deletion smoke.

No local PostgreSQL, HTTP or JVM is started by preparation. Packaged journey counts fail closed.
"""
from contextlib import contextmanager
import hashlib
import json
import subprocess

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f20_ci as f20
import f21_ci as f21
import f22_ci as f22
import f27_ci as f27

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
SOURCE = 'f06a72a06bd26662c647bde780dfc7a27bf7349c'
COUNTERS = ('request_checks', 'nonstreaming_checks', 'delete_checks', 'successful_delete_checks', 'rejected_delete_checks', 'get_checks', 'notification_checks', 'detail_checks', 'list_checks', 'stream_open_checks', 'stream_ready_checks', 'resync_checks', 'other_resync_checks', 'storage_unavailable_checks', 'member_unavailable_checks', 'recovery_checks', 'repeated_delete_checks', 'not_found_checks', 'authentication_checks', 'adjacent_denied_checks', 'approved_database_write_checks', 'deleted_mailbox_rows', 'deleted_note_rows', 'cleared_request_links', 'cleared_reply_links')
EXPECTED_JAR_COUNTS = {'request_checks': 18, 'nonstreaming_checks': 16, 'delete_checks': 9, 'successful_delete_checks': 3, 'rejected_delete_checks': 6, 'get_checks': 9, 'notification_checks': 3, 'detail_checks': 3, 'list_checks': 1, 'stream_open_checks': 2, 'stream_ready_checks': 2, 'resync_checks': 3, 'other_resync_checks': 0, 'storage_unavailable_checks': 2, 'member_unavailable_checks': 1, 'recovery_checks': 3, 'repeated_delete_checks': 1, 'not_found_checks': 3, 'authentication_checks': 1, 'adjacent_denied_checks': 1, 'approved_database_write_checks': 3, 'deleted_mailbox_rows': 3, 'deleted_note_rows': 2, 'cleared_request_links': 1, 'cleared_reply_links': 1}
NOTES = 'pawbridge_community.private_notes'
MAILBOXES = 'pawbridge_community.private_note_mailboxes'
REQUESTS = 'pawbridge_community.private_note_requests'


def owned_sql(query='', *, input_text=None):
    f2.require_hosted()
    try:
        return f3.sql(query, input_text=input_text) if input_text is not None else f3.sql(query)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        raise SystemExit('F28 owned fixture SQL failed without exposing command, SQL or output') from None


MIGRATIONS = 'community-service/src/migration/resources/db/postgresql/'
DDL_HASHES = {
    MIGRATIONS + 'V6__private_notes.sql': '3ec7def5eed39ddbf963839c7fd8648ecca9bc168570403778c3341e8f31c119',
    MIGRATIONS + 'V7__private_note_cleanup_indexes.sql': 'f1dcd3476bf56eb7862ffcdbdaa0546a59c94a0fb637c04c7a5d6fb677da1cc6',
}
FRAGMENT_HASHES = (
    '0a794d8711da88e4c98ed98fe931d2b775dd29037169cd9c1655a34ad91d83bc',
    'a7744fbfb946dfb2e7b42168a4dbf99a52bec4978d88dea24125007495b5822f',
    '65a253fa5f95bd592ff66373daf3fd7d41614c8c9a1aeaed5f6a6db071036fbc',
    '020b3e8baa54267e0699f878ccabd5cfbb68d35ff187d381491910315ec16ab0',
)


def source_proof():
    return (''.join(digest + '  ddl-source ' + path + '\n' for path, digest in DDL_HASHES.items())
            + ''.join(digest + '  request-fragment-' + str(index) + '\n'
                      for index, digest in enumerate(FRAGMENT_HASHES)))


def fixture_proof(owner):
    return ('owner=' + owner + '\nsource_sha=' + SOURCE + '\n'
            'fixture=F27 plus original requests and own-note mailbox deletion\n'
            'new_business_ddl=0\nsynthetic_tables_added=1\nnew_schema=0\nnew_marker=0\nsnapshot_tables=17\n'
            'writer_columns=members:SELECT,INSERT,UPDATE(member_id);'
            'blocks:SELECT(member_id,blocked_id),DELETE,INSERT(member_id,blocked_id,created_at);'
            'notes:SELECT(note_id,expires_at),UPDATE(note_id),DELETE;'
            'mailboxes:SELECT(member_id,note_id,direction,read_at),UPDATE(read_at,favorite),DELETE\n'
            'requests_app_acl=0\nnew_sequence_acl=0\n' + source_proof())


def verified_requests_ddl(inputs):
    """Four original LF fragments; only schema qualification adapts the original DDL."""
    try:
        if any(hashlib.sha256(inputs[path]).hexdigest() != digest for path, digest in DDL_HASHES.items()):
            raise SystemExit('F28 request migration bytes differ')
        v6 = inputs[MIGRATIONS + 'V6__private_notes.sql'].decode('utf-8')
        v7 = inputs[MIGRATIONS + 'V7__private_note_cleanup_indexes.sql'].decode('utf-8')
        anchor = 'CREATE TABLE private_note_requests ('
        if v6.count(anchor) != 1:
            raise SystemExit('F28 request table fragment is not unique')
        start = v6.index(anchor)
        fragments = [v6[start:v6.index(');\n', start) + 3]]
        for name, text in (('idx_private_note_request_rate', v6),
                           ('idx_private_note_request_note', v7),
                           ('idx_private_note_request_cleanup', v7)):
            lines = [line for line in text.splitlines(keepends=True) if line.startswith('CREATE INDEX ' + name + ' ')]
            if len(lines) != 1:
                raise SystemExit('F28 original request index fragment is not unique')
            fragments.append(lines[0])
        if tuple(hashlib.sha256(part.encode()).hexdigest() for part in fragments) != FRAGMENT_HASHES:
            raise SystemExit('F28 request table/index LF fragment identity differs')
    except (KeyError, UnicodeError, ValueError):
        raise SystemExit('F28 fixed request DDL fragments are missing or malformed') from None
    ddl = ''.join(fragments)
    if ddl.count('CREATE TABLE ') != 1 or ddl.count('CREATE INDEX ') != 3:
        raise SystemExit('F28 request DDL exceeds its one-table/three-index boundary')
    for table in ('private_note_requests', 'private_note_members', 'private_notes'):
        for prefix in ('CREATE TABLE ', 'REFERENCES ', 'ON '):
            ddl = ddl.replace(prefix + table, prefix + 'pawbridge_community.' + table)
    return ('BEGIN;\n' + ddl + 'GRANT DELETE ON ' + MAILBOXES + ',' + NOTES
            + ' TO pawbridge_f3_writer;\nCOMMIT;\n')


def require_absent_requests():
    names = ('private_note_requests', 'private_note_requests_pkey', 'idx_private_note_request_rate',
             'idx_private_note_request_note', 'idx_private_note_request_cleanup')
    if catalog("SELECT NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
               "WHERE n.nspname='pawbridge_community' AND c.relname IN ("
               + ','.join(f4.literal(name) for name in names) + '))', 'absence') != 't':
        raise SystemExit('F28 refuses an existing request table/index namespace')
    if catalog("SELECT NOT EXISTS(SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace "
               "WHERE n.nspname='pawbridge_community' AND t.typname='private_note_requests')", 'absence') != 't':
        raise SystemExit('F28 refuses a conflicting request row-type namespace')


def require_prior_receipts(owner):
    # Marker identity only during intentional User/writer denial, never an old strict ACL check.
    f2.require_hosted()
    f27.require_prior_receipts(owner)
    f21.check_receipt(EVIDENCE / 'f27-fixture-write.txt', f27.fixture_proof(owner))


def require_owner():
    f2.require_hosted()
    owner = f27.require_owner()  # User-seven/old identities remain valid after notes writer grants.
    f21.check_receipt(EVIDENCE / 'f28-fixture-write.txt', fixture_proof(owner))
    return owner


def verified_ddl_sources():
    """Read the two pinned migration Git blobs, not working-tree paths; produce no generated file."""
    f2.require_hosted()
    inputs = {}
    for path, expected in DDL_HASHES.items():
        try:
            raw = subprocess.check_output(['git', 'show', SOURCE + ':' + path], cwd=ROOT.parent,
                                          stderr=subprocess.PIPE)
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            raise SystemExit('F28 fixed migration source object is unavailable') from None
        if hashlib.sha256(raw).hexdigest() != expected:
            raise SystemExit('F28 fixed migration source checksum differs')
        inputs[path] = raw
    return inputs


def catalog(query, stage):
    if stage not in ('absence', 'shape', 'acl', 'constraints') or not query.startswith('SELECT '):
        raise ValueError('F28 catalog is restricted to static read-only metadata')
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
            raise SystemExit('F28 inherited pending/member/block shape or owner differs')
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
            raise SystemExit('F28 inherited pending/member/block exact ACL differs')
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
            raise SystemExit('F28 inherited pending/member/block PK/FK/CHECK/index differs')
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
            raise SystemExit('F28 notes/mailboxes shape, defaults or owner differs')
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
                   "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER') "
                   "AND has_table_privilege('pawbridge_f3_writer','" + relation + "','DELETE')" + where, 'acl') != 't':
            raise SystemExit('F28 notes-two/mailboxes-four SELECT and exact favorite/read_at UPDATE ACL differs')
        if catalog('SELECT ' + ' AND '.join(f21.constraint_checks(table) + f21.index_checks(table)), 'constraints') != 't':
            raise SystemExit('F28 notes/mailboxes validated PK/FK/CHECK/index differs')
    require_requests_fixture()
    return owner


def request_constraint_checks():
    columns = f20._columns
    terms = ["(SELECT count(*)=3 FROM pg_constraint WHERE conrelid='" + REQUESTS + "'::regclass)",
             "(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + REQUESTS + "'::regclass "
             "AND contype='p' AND convalidated AND NOT condeferrable AND conkey="
             + columns(REQUESTS, ('sender_id','request_id')) + ')']
    for field, target, key, deletion in (('sender_id','pawbridge_community.private_note_members','member_id','c'),
                                          ('note_id',NOTES,'note_id','n')):
        terms.append("(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + REQUESTS + "'::regclass "
                     "AND contype='f' AND convalidated AND NOT condeferrable AND NOT condeferred "
                     "AND confrelid='" + target + "'::regclass AND confdeltype='" + deletion
                     + "' AND confupdtype='a' AND confmatchtype='s' AND conkey="
                     + columns(REQUESTS, (field,)) + ' AND confkey=' + columns(target, (key,)) + ')')
    return terms


def request_index_checks():
    definitions = (
        ('private_note_requests_pkey', ('sender_id','request_id'), (), None, True),
        ('idx_private_note_request_rate', ('sender_id','created_at'), (1,), None, False),
        ('idx_private_note_request_note', ('note_id',), (), 'note_idISNOTNULL', False),
        ('idx_private_note_request_cleanup', ('created_at',), (), 'note_idISNULL', False),
    )
    terms = ["(SELECT count(*)=4 FROM pg_index WHERE indrelid='" + REQUESTS + "'::regclass)"]
    for name, keys, descending, predicate, primary in definitions:
        conditions = ["i.indrelid='" + REQUESTS + "'::regclass", "c.relname='" + name + "'", "a.amname='btree'",
                      "pg_get_userbyid(c.relowner)='postgres'", "c.relpersistence='p'", 'i.indisvalid', 'i.indisready',
                      'i.indisunique=' + str(primary).lower(), 'i.indisprimary=' + str(primary).lower(),
                      'i.indexprs IS NULL', 'i.indnatts=' + str(len(keys)), 'i.indnkeyatts=' + str(len(keys))]
        for index, field in enumerate(keys):
            conditions.append('i.indkey[' + str(index) + "]=(SELECT attnum FROM pg_attribute WHERE attrelid='"
                              + REQUESTS + "'::regclass AND attname='" + field + "')")
            conditions.append('i.indoption[' + str(index) + ']=' + ('3' if index in descending else '0'))
        conditions.append('i.indpred IS NULL' if predicate is None else
                          "regexp_replace(pg_get_expr(i.indpred,i.indrelid),'::character varying|::text|[()[:space:]]','','g')="
                          + f4.literal(predicate))
        terms.append('(SELECT count(*)=1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid '
                     'JOIN pg_am a ON a.oid=c.relam WHERE ' + ' AND '.join(conditions) + ')')
    return terms


def require_requests_fixture():
    shape = "('sender_id','bigint','NO',0,'',0),('request_id','uuid','NO',0,'',0)," \
            "('request_hash','character varying','YES',0,'',64),('note_id','uuid','YES',0,'',0)," \
            "('created_at','timestamp with time zone','NO',6,'',0)"
    where = " FROM information_schema.columns WHERE table_schema='pawbridge_community' AND table_name='private_note_requests'"
    if catalog("SELECT count(*)=5 AND bool_and(is_identity='NO' AND is_generated='NEVER') AND bool_and("
               "(column_name,data_type,is_nullable,COALESCE(datetime_precision,0),COALESCE(column_default,''),COALESCE(character_maximum_length,0)) IN ("
               + shape + ")) AND (SELECT relkind='r' AND relpersistence='p' AND pg_get_userbyid(relowner)='postgres' "
               "FROM pg_class WHERE oid='" + REQUESTS + "'::regclass)" + where, 'shape') != 't':
        raise SystemExit('F28 requests exact five-column shape/default/owner differs')
    clauses = ['count(*)=5', "bool_and(pg_get_serial_sequence('" + REQUESTS + "',column_name) IS NULL)"]
    for role in ('pawbridge_f2_reader','pawbridge_f3_writer'):
        clauses += ["bool_and(NOT has_column_privilege('" + role + "','" + REQUESTS
                    + "',column_name,'SELECT,INSERT,UPDATE,REFERENCES'))",
                    "NOT has_table_privilege('" + role + "','" + REQUESTS
                    + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')"]
    if catalog('SELECT ' + ' AND '.join(clauses) + where, 'acl') != 't':
        raise SystemExit('F28 requests requires zero reader/writer table/column ACL and no identity sequence')
    if catalog('SELECT ' + ' AND '.join(request_constraint_checks() + request_index_checks()), 'constraints') != 't':
        raise SystemExit('F28 requests validated PK/two FK/three original indexes differ')


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
    ('private_notes',NOTES,'note_id'), ('private_note_mailboxes',MAILBOXES,'member_id,note_id'),
    ('private_note_requests',REQUESTS,'sender_id,request_id'),
)


def database_snapshot():
    f2.require_hosted()
    fields = [f4.literal(name) + ',(SELECT COALESCE(json_agg(r ORDER BY ' + order
              + "),'[]'::json) FROM " + relation + ' r)' for name, relation, order in SNAPSHOT_TABLES]
    try:
        value = json.loads(owned_sql('SELECT json_build_object(' + ','.join(fields) + ')'))
    except (ValueError, TypeError):
        raise SystemExit('F28 complete seventeen-table snapshot is malformed') from None
    if (not isinstance(value, dict) or set(value) != {row[0] for row in SNAPSHOT_TABLES}
            or any(not isinstance(rows, list) for rows in value.values())):
        raise SystemExit('F28 requires exactly all seventeen business tables')
    return value


def prepare():
    f2.require_hosted()
    owner = f27.require_fixture()  # Last F27 strict before the new table and DELETE grants.
    target = EVIDENCE / 'f28-fixture-write.txt'
    proof = fixture_proof(owner)
    f21.check_receipt(target, proof, allow_missing=True)
    if target.exists():
        raise SystemExit('F28 refuses an existing deletion fixture receipt')
    ddl = verified_requests_ddl(verified_ddl_sources())
    before = f27.database_snapshot()  # Last sixteen-table snapshot, before requests exists.
    if (not isinstance(before, dict) or set(before) != {row[0] for row in SNAPSHOT_TABLES[:-1]}
            or any(not isinstance(rows, list) for rows in before.values())):
        raise SystemExit('F28 inherited sixteen-table baseline is incomplete')
    require_absent_requests()
    owned_sql(input_text=ddl)
    with target.open('x') as output:
        output.write(proof)
    require_fixture()
    expected = dict(before, private_note_requests=[])
    if database_snapshot() != expected:
        raise SystemExit('F28 extension changed prior rows or requests is not empty')
    print('Added only pinned synthetic requests and exact two-table DELETE after F27; all17 rows preserved')


@contextmanager
def denied_privilege(kind):
    clauses = {'mailbox-delete': ('DELETE ON ' + MAILBOXES, 'pawbridge_f3_writer'),
               'note-delete': ('DELETE ON ' + NOTES, 'pawbridge_f3_writer'),
               'nickname': ('SELECT(nickname) ON pawbridge_user.users', 'pawbridge_f2_reader')}
    if kind not in clauses:
        raise ValueError('Only planned F28 mailbox/note DELETE or User nickname denial is permitted')
    owner = require_fixture()
    clause, role = clauses[kind]
    owned_sql('REVOKE ' + clause + ' FROM ' + role)
    try:
        yield
    finally:
        require_prior_receipts(owner)
        f21.check_receipt(EVIDENCE / 'f28-fixture-write.txt', fixture_proof(owner))
        owned_sql('GRANT ' + clause + ' TO ' + role)
        require_fixture()


def require_measurements(kind='jar'):
    f2.require_hosted()
    if kind != 'jar':
        raise ValueError('F28 measurement gate is limited to the packaged journey')
    if (not COUNTERS or not isinstance(EXPECTED_JAR_COUNTS, dict)
            or set(EXPECTED_JAR_COUNTS) != set(COUNTERS)
            or any(type(n) is not int or n < 0 for n in EXPECTED_JAR_COUNTS.values())):
        raise SystemExit('F28 packaged journey counts remain unmeasured')


def smoke():
    f2.require_hosted()
    import sys
    from f28_stream_probe import run
    return run(sys.modules[__name__])


if __name__ == "__main__":
    import sys
    f2.require_hosted()
    {"prepare": prepare, "smoke": smoke}[sys.argv[1]]()
