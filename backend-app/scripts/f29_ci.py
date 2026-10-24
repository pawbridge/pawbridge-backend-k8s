#!/usr/bin/env python3
"""F29 hosted fixture/XML prefix. Runtime smoke is owned separately; import starts no resource.

No local PostgreSQL, original extraction, HTTP or JVM is started by preparation.
Provider/Seoul/JAR counters are unmeasured and fail closed; no original extraction or resource run occurred.
"""
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f20_ci as f20
import f21_ci as f21
import f22_ci as f22
import f28_ci as f28
import prepare_f29_legacy_oracle as oracle

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
SOURCE = 'f06a72a06bd26662c647bde780dfc7a27bf7349c'
SOURCE_FILES = 22
STREAM_SOURCE = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteStream.java'
STREAM_SHA256 = '3215dd7b9f3958049ea3065bc550883838bfeacc46d75e350cbc29f26a9504eb'
TASK = 'noteSendCompatibilityTest'
SEOUL_TASK = 'noteSendCompatibilitySeoulTest'
SEOUL_METHOD = 'givenMicrosecondLeapYearSend__whenPersistingAndSerializingInEitherZone__thenKeepOneInstantAcrossBothMailboxesRequestAndNotification'
PG_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlNoteSendCompatibilityTest'
MVC_CLASS = 'com.pawbridge.backend.compatibility.F29LegacyNoteSendMvcTest'
# Reviewed expected PG26 and Seoul1; local original-measured MVC12 passed.
# Hosted results must still provide these exact actual XML suites and children.
REPORTS = {TASK: {PG_CLASS: 26, MVC_CLASS: 12}, SEOUL_TASK: {PG_CLASS: 1}}
COUNTERS = ('request_checks', 'nonstreaming_checks', 'post_checks', 'successful_post_checks', 'rejected_post_checks', 'get_checks', 'stream_open_checks', 'stream_ready_checks', 'recipient_note_checks', 'sender_note_checks', 'resync_checks', 'replay_checks', 'conflict_checks', 'storage_unavailable_checks', 'member_unavailable_checks', 'recovery_checks', 'authentication_checks', 'adjacent_denied_checks', 'notification_checks', 'detail_checks', 'list_checks', 'approved_database_write_checks', 'inserted_note_rows', 'inserted_mailbox_rows', 'inserted_request_rows')
# Reviewed planned journey only; actual hosted PG/JAR execution remains pending.
EXPECTED_JAR_COUNTS = dict(zip(COUNTERS, (15, 13, 9, 4, 5, 6, 2, 2, 3, 0, 0, 1, 1, 1, 1, 2, 1, 1, 1, 1, 2, 3, 3, 6, 3)))
NOTES = 'pawbridge_community.private_notes'
MAILBOXES = 'pawbridge_community.private_note_mailboxes'
REQUESTS = 'pawbridge_community.private_note_requests'
REPORTS_TABLE = 'pawbridge_community.animal_reports'
REPORT_SEQUENCE = 'pawbridge_community.animal_reports_report_id_seq'


def owned_sql(query='', *, input_text=None):
    f2.require_hosted()
    try:
        return f3.sql(query, input_text=input_text) if input_text is not None else f3.sql(query)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        raise SystemExit('F29 owned fixture SQL failed without exposing command, SQL or output') from None


MIGRATIONS = 'community-service/src/migration/resources/db/postgresql/'
DDL_HASHES = {
    MIGRATIONS + 'V4__animal_reports.sql': '6f1d2a9c75e1fb2ec836f5e4cd8fecbf68fd0c2018a20a79193edd27747dd71a',
    MIGRATIONS + 'V5__animal_report_search_fields.sql': 'ff29faf3026342725bf585cb5feebf33f617a34c14e0b038c35ea29276c1f213',
}
FRAGMENT_HASHES = (
    '22b35d7107312096c32c3ae7023a7e78fd6d97d396c286bdae17bb1b50b9c8ea',
    '8896b1fa54b0859fe32deef8fc7d959d69a7c297e185692e4018d5121c4294e7',
    'bd354d203af70dc5afaf89b25d78322cd751a9c8e199d6ef7e2cba49df9f7b76',
    '83acfa67eaf9e0f0a4c75c48b393ca45020724d239d13269bef5cc4f5c68f198',
    '591e30398f83e66a3e77f01d1ff8fbb0e68bf8a1946c89c646ee6f99c622d707',
)
SEND_GRANTS = (
    'GRANT SELECT(sender_id),INSERT(note_id,sender_id,recipient_id,body,reply_to,context_type,context_id,created_at,expires_at) ON ' + NOTES + ' TO pawbridge_f3_writer;\n'
    'GRANT INSERT(member_id,note_id,direction,read_at,created_at) ON ' + MAILBOXES + ' TO pawbridge_f3_writer;\n'
    'GRANT SELECT(sender_id,request_id,request_hash,note_id,created_at),INSERT(sender_id,request_id,request_hash,note_id,created_at) ON ' + REQUESTS + ' TO pawbridge_f3_writer;\n'
    'GRANT SELECT(report_id,author_id,deleted_at) ON ' + REPORTS_TABLE + ' TO pawbridge_f3_writer;\n'
)


def source_proof():
    return (''.join(digest + '  ddl-source ' + path + '\n' for path, digest in DDL_HASHES.items())
            + ''.join(digest + '  report-fragment-' + str(index) + '\n'
                      for index, digest in enumerate(FRAGMENT_HASHES)))


def fixture_proof(owner):
    return ('owner=' + owner + '\nsource_sha=' + SOURCE + '\n'
            'fixture=F28 plus pinned report context and exact note-send columns\n'
            'new_business_ddl=0\nsynthetic_tables_added=1\nsynthetic_identity_sequences_added=1\n'
            'new_schema=0\nnew_marker=0\nsnapshot_tables=18\nreader_delta=0\n'
            'writer_columns=members:SELECT,INSERT,UPDATE(member_id);'
            'blocks:SELECT(member_id,blocked_id),DELETE,INSERT(member_id,blocked_id,created_at);'
            'notes:SELECT(note_id,expires_at,sender_id),INSERT(note_id,sender_id,recipient_id,body,reply_to,context_type,context_id,created_at,expires_at),UPDATE(note_id),DELETE;'
            'mailboxes:SELECT(member_id,note_id,direction,read_at),INSERT(member_id,note_id,direction,read_at,created_at),UPDATE(read_at,favorite),DELETE;'
            'requests:SELECT(sender_id,request_id,request_hash,note_id,created_at),INSERT(sender_id,request_id,request_hash,note_id,created_at);'
            'reports:SELECT(report_id,author_id,deleted_at)\n'
            'reports_reader_acl=0\nnew_sequence_acl=0\n' + source_proof())


def verified_reports_ddl(inputs):
    """Five pinned source LF statements; only table schema qualification changes."""
    if any(oracle.EXPECTED.get(path) != digest for path, digest in DDL_HASHES.items()):
        raise SystemExit('F29 report migration pins differ from the fixed source closure')
    try:
        fragments = []
        for suffix, anchors in (
                ('V4__animal_reports.sql', ('CREATE TABLE animal_reports (', 'CREATE INDEX animal_reports_kind_created_idx')),
                ('V5__animal_report_search_fields.sql', ('ALTER TABLE animal_reports', 'CREATE INDEX animal_reports_region_occurred_idx', 'CREATE INDEX animal_reports_type_occurred_idx'))):
            path = MIGRATIONS + suffix
            if hashlib.sha256(inputs[path]).hexdigest() != DDL_HASHES[path]:
                raise SystemExit('F29 fixed report migration bytes differ')
            text = inputs[path].decode('utf-8')
            for anchor in anchors:
                if text.count(anchor) != 1:
                    raise SystemExit('F29 original report statement is not unique')
                start = text.index(anchor)
                fragments.append(text[start:text.index(';\n', start) + 2])
        if tuple(hashlib.sha256(part.encode()).hexdigest() for part in fragments) != FRAGMENT_HASHES:
            raise SystemExit('F29 original report statement LF identity differs')
    except (KeyError, UnicodeError, ValueError):
        raise SystemExit('F29 fixed report source fragments are missing or malformed') from None
    ddl = ''.join(fragments)
    if ddl.count('CREATE TABLE ') != 1 or ddl.count('ALTER TABLE ') != 1 or ddl.count('CREATE INDEX ') != 3:
        raise SystemExit('F29 report DDL exceeds its original one-table/alter/three-index boundary')
    for prefix in ('CREATE TABLE ', 'ALTER TABLE ', 'ON '):
        ddl = ddl.replace(prefix + 'animal_reports', prefix + REPORTS_TABLE)
    return 'BEGIN;\n' + ddl + SEND_GRANTS + 'COMMIT;\n'


def require_absent_reports():
    names = ('animal_reports', 'animal_reports_report_id_seq', 'animal_reports_pkey',
             'animal_reports_kind_created_idx', 'animal_reports_region_occurred_idx', 'animal_reports_type_occurred_idx')
    checks = (
        "SELECT NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pawbridge_community' AND c.relname IN (" + ','.join(f4.literal(name) for name in names) + '))',
        "SELECT NOT EXISTS(SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='pawbridge_community' AND t.typname IN ('animal_reports','_animal_reports'))",
        "SELECT NOT EXISTS(SELECT 1 FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='pawbridge_community' AND c.conname IN ('animal_reports_pkey','animal_reports_report_kind_check','animal_reports_animal_type_check','animal_reports_district_province_check'))",
    )
    for query in checks:
        if catalog(query, 'absence') != 't':
            raise SystemExit('F29 refuses an existing report table/identity/index/check/type namespace')


def require_prior_receipts(owner):
    # Marker identity only during intentional User/writer denial, never an old strict ACL check.
    f2.require_hosted()
    f28.require_prior_receipts(owner)
    f21.check_receipt(EVIDENCE / 'f28-fixture-write.txt', f28.fixture_proof(owner))


def require_owner():
    f2.require_hosted()
    owner = f28.require_owner()  # User-seven/old identities remain valid after notes writer grants.
    f21.check_receipt(EVIDENCE / 'f29-fixture-write.txt', fixture_proof(owner))
    return owner


def verified_sources():
    """Read fixed Git blobs, not working-tree legacy paths; produce no generated file."""
    f2.require_hosted()
    if (oracle.SOURCE != SOURCE or len(oracle.EXPECTED) != SOURCE_FILES
            or len(oracle.SHARED) != 13 or len(oracle.OUTPUTS) != 3
            or oracle.EXPECTED.get(STREAM_SOURCE) != STREAM_SHA256
            or oracle.EXPECTED != {**f28.oracle.EXPECTED, **DDL_HASHES}):
        raise SystemExit('F29 fixed twenty-two-source closure or original Stream pin differs')
    inputs = {}
    for path, expected in oracle.EXPECTED.items():
        try:
            raw = subprocess.check_output(['git', 'show', SOURCE + ':' + path], cwd=ROOT.parent,
                                          stderr=subprocess.PIPE)
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            raise SystemExit('F29 fixed original source object is unavailable') from None
        if hashlib.sha256(raw).hexdigest() != expected:
            raise SystemExit('F29 fixed original source checksum differs')
        inputs[path] = raw
    return inputs


def catalog(query, stage):
    if stage not in ('absence', 'shape', 'acl', 'constraints', 'sequence') or not query.startswith('SELECT '):
        raise ValueError('F29 catalog is restricted to static read-only metadata')
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
            raise SystemExit('F29 inherited pending/member/block shape or owner differs')
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
            raise SystemExit('F29 inherited pending/member/block exact ACL differs')
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
            raise SystemExit('F29 inherited pending/member/block PK/FK/CHECK/index differs')
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
            raise SystemExit('F29 notes/mailboxes shape, defaults or owner differs')
        reader = 'column_name IN (' + ','.join(f4.literal(c) for c in f22.READABLE[table]) + ')'
        writer_read = "column_name IN ('note_id','expires_at','sender_id')" if table == 'private_notes' else "column_name IN ('member_id','note_id','direction','read_at')"
        writer_update = "column_name='note_id'" if table == 'private_notes' else "column_name IN ('read_at','favorite')"
        writer_insert = "true" if table == "private_notes" else "column_name IN ('member_id','note_id','direction','read_at','created_at')"
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and(has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'SELECT')=(" + reader + ") AND NOT has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'INSERT,UPDATE,REFERENCES') AND has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'SELECT')=(" + writer_read + ") AND has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'UPDATE')=(" + writer_update + ") AND NOT has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'REFERENCES') AND has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'INSERT')=(" + writer_insert + ") AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL) "
                   "AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                   "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER') "
                   "AND has_table_privilege('pawbridge_f3_writer','" + relation + "','DELETE')" + where, 'acl') != 't':
            raise SystemExit('F29 notes-three/mailboxes-four SELECT, exact send INSERT and inherited UPDATE/DELETE ACL differs')
        if catalog('SELECT ' + ' AND '.join(f21.constraint_checks(table) + f21.index_checks(table)), 'constraints') != 't':
            raise SystemExit('F29 notes/mailboxes validated PK/FK/CHECK/index differs')
    require_requests_fixture()
    require_reports_fixture()
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
        raise SystemExit('F29 requests exact five-column shape/default/owner differs')
    clauses = ['count(*)=5', "bool_and(pg_get_serial_sequence('" + REQUESTS + "',column_name) IS NULL)",
               "bool_and(NOT has_column_privilege('pawbridge_f2_reader','" + REQUESTS + "',column_name,'SELECT,INSERT,UPDATE,REFERENCES'))",
               "bool_and(has_column_privilege('pawbridge_f3_writer','" + REQUESTS + "',column_name,'SELECT') AND has_column_privilege('pawbridge_f3_writer','" + REQUESTS + "',column_name,'INSERT') AND NOT has_column_privilege('pawbridge_f3_writer','" + REQUESTS + "',column_name,'UPDATE,REFERENCES'))"]
    for role in ('pawbridge_f2_reader','pawbridge_f3_writer'):
        clauses.append("NOT has_table_privilege('" + role + "','" + REQUESTS + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')")
    if catalog('SELECT ' + ' AND '.join(clauses) + where, 'acl') != 't':
        raise SystemExit('F29 requests requires exact SELECT5/INSERT5, zero reader/other writer rights and no sequence')
    if catalog('SELECT ' + ' AND '.join(request_constraint_checks() + request_index_checks()), 'constraints') != 't':
        raise SystemExit('F29 requests validated PK/two FK/three original indexes differ')


def report_constraint_checks():
    terms = ["(SELECT count(*)=4 FROM pg_constraint WHERE conrelid='" + REPORTS_TABLE + "'::regclass)",
             "(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + REPORTS_TABLE + "'::regclass "
             "AND contype='p' AND convalidated AND NOT condeferrable AND conkey="
             + f20._columns(REPORTS_TABLE, ('report_id',)) + ')']
    for fields, expression in (
            (('report_kind',), "report_kind=ANYARRAY['MISSING','SIGHTING']"),
            (('animal_type',), "animal_typeISNULLORanimal_type=ANYARRAY['DOG','CAT','OTHER']"),
            (('district','province'), 'districtISNULLORprovinceISNOTNULL')):
        terms.append("(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + REPORTS_TABLE
                     + "'::regclass AND contype='c' AND convalidated AND NOT connoinherit AND conkey="
                     + f20._columns(REPORTS_TABLE, fields) + ' AND ' + f21.expr() + '=' + f4.literal(expression) + ')')
    terms.append("NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='" + REPORTS_TABLE + "'::regclass AND NOT tgisinternal)")
    return terms


def report_index_checks():
    definitions = (
        ('animal_reports_pkey', ('report_id',), (), None, True),
        ('animal_reports_kind_created_idx', ('report_kind','created_at','report_id'), (1,2), 'deleted_atISNULL', False),
        ('animal_reports_region_occurred_idx', ('province','district','occurred_on'), (), 'deleted_atISNULL', False),
        ('animal_reports_type_occurred_idx', ('animal_type','occurred_on'), (), 'deleted_atISNULL', False),
    )
    terms = ["(SELECT count(*)=4 FROM pg_index WHERE indrelid='" + REPORTS_TABLE + "'::regclass)"]
    for name, keys, descending, predicate, primary in definitions:
        conditions = ["i.indrelid='" + REPORTS_TABLE + "'::regclass", "c.relname='" + name + "'", "a.amname='btree'",
                      "pg_get_userbyid(c.relowner)='postgres'", "c.relpersistence='p'", 'i.indisvalid', 'i.indisready',
                      'i.indisunique=' + str(primary).lower(), 'i.indisprimary=' + str(primary).lower(),
                      'i.indexprs IS NULL', 'i.indnatts=' + str(len(keys)), 'i.indnkeyatts=' + str(len(keys))]
        for index, field in enumerate(keys):
            conditions.append('i.indkey[' + str(index) + "]=(SELECT attnum FROM pg_attribute WHERE attrelid='"
                              + REPORTS_TABLE + "'::regclass AND attname='" + field + "')")
            conditions.append('i.indoption[' + str(index) + ']=' + ('3' if index in descending else '0'))
        conditions.append('i.indpred IS NULL' if predicate is None else
                          "regexp_replace(pg_get_expr(i.indpred,i.indrelid),'[()[:space:]]','','g')=" + f4.literal(predicate))
        terms.append('(SELECT count(*)=1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid '
                     'JOIN pg_am a ON a.oid=c.relam WHERE ' + ' AND '.join(conditions) + ')')
    return terms


def require_reports_fixture():
    columns = (
        ('report_id','bigint','NO',0,'',0), ('author_id','bigint','NO',0,'',0),
        ('report_kind','character varying','NO',0,'',16), ('description','text','NO',0,'',0),
        ('image_urls','json','NO',0,"'[]'::json",0), ('occurred_on','date','NO',0,'',0),
        ('approximate_time','character varying','YES',0,'',40), ('region','character varying','NO',0,'',120),
        ('landmark','character varying','YES',0,'',200), ('species','character varying','NO',0,'',40),
        ('animal_name','character varying','YES',0,'',80), ('coat_color','character varying','YES',0,'',100),
        ('animal_size','character varying','YES',0,'',40), ('distinguishing_features','character varying','YES',0,'',500),
        ('direction','character varying','YES',0,'',200), ('created_at','timestamp without time zone','NO',6,'',0),
        ('updated_at','timestamp without time zone','NO',6,'',0), ('deleted_at','timestamp without time zone','YES',6,'',0),
        ('province','character varying','YES',0,'',40), ('district','character varying','YES',0,'',40),
        ('animal_type','character varying','YES',0,'',16),
    )
    shape = ','.join('(' + ','.join(str(value) if type(value) is int else f4.literal(value) for value in row) + ')' for row in columns)
    where = " FROM information_schema.columns WHERE table_schema='pawbridge_community' AND table_name='animal_reports'"
    if catalog("SELECT count(*)=21 AND bool_and(is_generated='NEVER') AND bool_and("
               "is_identity=CASE WHEN column_name='report_id' THEN 'YES' ELSE 'NO' END AND "
               "identity_generation IS NOT DISTINCT FROM CASE WHEN column_name='report_id' THEN 'BY DEFAULT' ELSE NULL END) "
               "AND bool_and((column_name,data_type,is_nullable,COALESCE(datetime_precision,0),COALESCE(column_default,''),COALESCE(character_maximum_length,0)) IN ("
               + shape + ")) AND (SELECT relkind='r' AND relpersistence='p' AND pg_get_userbyid(relowner)='postgres' "
               "FROM pg_class WHERE oid='" + REPORTS_TABLE + "'::regclass)" + where, 'shape') != 't':
        raise SystemExit('F29 reports exact original21 columns/defaults/identity/owner differ')
    if catalog("SELECT count(*)=21 AND bool_and(NOT has_column_privilege('pawbridge_f2_reader','" + REPORTS_TABLE
               + "',column_name,'SELECT,INSERT,UPDATE,REFERENCES') AND has_column_privilege('pawbridge_f3_writer','" + REPORTS_TABLE
               + "',column_name,'SELECT')=(column_name IN ('report_id','author_id','deleted_at')) AND NOT has_column_privilege('pawbridge_f3_writer','"
               + REPORTS_TABLE + "',column_name,'INSERT,UPDATE,REFERENCES')) "
               "AND NOT has_table_privilege('pawbridge_f2_reader','" + REPORTS_TABLE + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
               "AND NOT has_table_privilege('pawbridge_f3_writer','" + REPORTS_TABLE + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')"
               + where, 'acl') != 't':
        raise SystemExit('F29 reports requires writer SELECT3 and zero reader/other writer ACL')
    if catalog('SELECT ' + ' AND '.join(report_constraint_checks() + report_index_checks()), 'constraints') != 't':
        raise SystemExit('F29 report PK/CHECK3/index4 or absence of extra triggers differs')
    if catalog("SELECT count(*)=1 AND bool_and(c.relkind='S' AND c.relpersistence='p' AND pg_get_userbyid(c.relowner)='postgres' "
               "AND s.seqtypid='bigint'::regtype AND s.seqstart=1 AND s.seqincrement=1 AND s.seqmin=1 "
               "AND s.seqmax=9223372036854775807 AND s.seqcache=1 AND NOT s.seqcycle "
               "AND pg_get_serial_sequence('" + REPORTS_TABLE + "','report_id')='" + REPORT_SEQUENCE + "' "
               "AND EXISTS(SELECT 1 FROM pg_depend d WHERE d.classid='pg_class'::regclass AND d.objid=c.oid "
               "AND d.refclassid='pg_class'::regclass AND d.refobjid='" + REPORTS_TABLE + "'::regclass "
               "AND d.refobjsubid=(SELECT attnum FROM pg_attribute WHERE attrelid='" + REPORTS_TABLE + "'::regclass AND attname='report_id') "
               "AND d.deptype='i') AND CASE WHEN c.relkind='S' THEN "
               "NOT has_sequence_privilege('pawbridge_f2_reader',c.oid,'SELECT,UPDATE,USAGE') "
               "AND NOT has_sequence_privilege('pawbridge_f3_writer',c.oid,'SELECT,UPDATE,USAGE') ELSE false END) "
               "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_sequence s ON s.seqrelid=c.oid "
               "WHERE n.nspname='pawbridge_community' AND c.relname='animal_reports_report_id_seq'", 'sequence') != 't':
        raise SystemExit('F29 reports requires one exact owned identity sequence and zero app sequence ACL')


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
    ('animal_reports',REPORTS_TABLE,'report_id'),
)


def database_snapshot():
    f2.require_hosted()
    fields = [f4.literal(name) + ',(SELECT COALESCE(json_agg(r ORDER BY ' + order
              + "),'[]'::json) FROM " + relation + ' r)' for name, relation, order in SNAPSHOT_TABLES]
    try:
        value = json.loads(owned_sql('SELECT json_build_object(' + ','.join(fields) + ')'))
    except (ValueError, TypeError):
        raise SystemExit('F29 complete eighteen-table snapshot is malformed') from None
    if (not isinstance(value, dict) or set(value) != {row[0] for row in SNAPSHOT_TABLES}
            or any(not isinstance(rows, list) for rows in value.values())):
        raise SystemExit('F29 requires exactly all eighteen business tables')
    return value


def prepare():
    f2.require_hosted()
    owner = f28.require_fixture()  # Last old17 strict before send privileges and the report table.
    target = EVIDENCE / 'f29-fixture-write.txt'
    proof = fixture_proof(owner)
    f21.check_receipt(target, proof, allow_missing=True)
    if target.exists():
        raise SystemExit('F29 refuses an existing send fixture receipt')
    ddl = verified_reports_ddl(verified_sources())
    before = f28.database_snapshot()  # Last old17 rows; never called after expansion.
    if (not isinstance(before, dict) or set(before) != {row[0] for row in SNAPSHOT_TABLES[:-1]}
            or any(not isinstance(rows, list) for rows in before.values())):
        raise SystemExit('F29 inherited seventeen-table baseline is incomplete')
    require_absent_reports()
    owned_sql(input_text=ddl)
    with target.open('x') as output:
        output.write(proof)
    require_fixture()
    if database_snapshot() != dict(before, animal_reports=[]):
        raise SystemExit('F29 extension changed prior rows or reports is not empty')
    print('Added only pinned synthetic report context and exact send columns after F28; all18 rows preserved')


@contextmanager
def denied_privilege(kind):
    clauses = {'note-insert': ('INSERT(body) ON ' + NOTES, 'pawbridge_f3_writer'),
               'mailbox-insert': ('INSERT(direction) ON ' + MAILBOXES, 'pawbridge_f3_writer'),
               'request-insert': ('INSERT(request_hash) ON ' + REQUESTS, 'pawbridge_f3_writer'),
               'report-context': ('SELECT(author_id) ON ' + REPORTS_TABLE, 'pawbridge_f3_writer'),
               'nickname': ('SELECT(nickname) ON pawbridge_user.users', 'pawbridge_f2_reader')}
    if kind not in clauses:
        raise ValueError('Only planned F29 three-insert, report context or User nickname denial is permitted')
    owner = require_fixture()
    clause, role = clauses[kind]
    owned_sql('REVOKE ' + clause + ' FROM ' + role)
    try:
        yield
    finally:
        require_prior_receipts(owner)
        f21.check_receipt(EVIDENCE / 'f29-fixture-write.txt', fixture_proof(owner))
        owned_sql('GRANT ' + clause + ' TO ' + role)
        require_fixture()


def require_measurements(kind='results'):
    f2.require_hosted()
    if kind not in ('results', 'jar'):
        raise ValueError('F29 measurement gate is limited to results or jar')
    if (not isinstance(REPORTS, dict) or set(REPORTS) != {TASK, SEOUL_TASK}
            or not isinstance(REPORTS.get(TASK), dict) or set(REPORTS[TASK]) != {PG_CLASS, MVC_CLASS}
            or REPORTS.get(SEOUL_TASK) != {PG_CLASS: 1}
            or any(type(n) is not int or n <= 0 for classes in REPORTS.values() for n in classes.values())
            or not isinstance(SEOUL_METHOD, str) or not re.fullmatch(r'given[A-Za-z0-9_]+', SEOUL_METHOD)):
        raise SystemExit('F29 original providers, PG/MVC counts or Seoul method remain unmeasured')
    if kind == 'jar' and (not COUNTERS or not isinstance(EXPECTED_JAR_COUNTS, dict)
            or set(EXPECTED_JAR_COUNTS) != set(COUNTERS)
            or any(type(n) is not int or n < 0 for n in EXPECTED_JAR_COUNTS.values())):
        raise SystemExit('F29 packaged journey counts remain unmeasured')


def _plain_file(path):
    if path.is_symlink() or path.resolve() != path or not path.is_file():
        raise SystemExit('F29 oracle evidence or generated path is missing or redirected')
    return path.read_bytes()


def require_oracle_receipt():
    """Match source/shared/generated identities; selected method/LF proofs remain extractor-owned."""
    f2.require_hosted()
    if (oracle.SOURCE != SOURCE or len(oracle.EXPECTED) != SOURCE_FILES
            or len(oracle.SHARED) != 13 or len(oracle.OUTPUTS) != 3
            or oracle.EXPECTED.get(STREAM_SOURCE) != STREAM_SHA256
            or oracle.EXPECTED != {**f28.oracle.EXPECTED, **DDL_HASHES}):
        raise SystemExit('F29 oracle manifest differs from the fixed twenty-two-source closure')
    try:
        lines = _plain_file(ROOT.parent / oracle.EVIDENCE_PATH).decode('utf-8').splitlines()
        if not lines or lines[0] != 'source_sha=' + SOURCE:
            raise SystemExit('F29 oracle evidence source identity differs')
        categories = {}
        for line in lines[1:]:
            match = re.fullmatch(r'([0-9a-f]{64})  (source|shared-byte-identity|shared-adapted-identity|generated) (.+)', line)
            if match:
                digest, category, name = match.groups()
                bucket = categories.setdefault(category, {})
                if name in bucket:
                    raise SystemExit('F29 oracle evidence repeats an identity entry')
                bucket[name] = digest
        if categories.get('source') != oracle.EXPECTED:
            raise SystemExit('F29 oracle evidence lacks the exact twenty-two full source hashes')
        shared = {relative: oracle.EXPECTED[source] for source, relative in oracle.SHARED.items()}
        if categories.get('shared-byte-identity') != shared:
            raise SystemExit('F29 oracle evidence shared source identities differ')
        for source, relative in oracle.SHARED.items():
            if hashlib.sha256(_plain_file(ROOT.parent / relative)).hexdigest() != oracle.EXPECTED[source]:
                raise SystemExit('F29 shared generated bytes differ from their fixed original')
        adapted = {oracle.F18_ADAPTED_PATH: oracle.F18_ADAPTED_SHA256}
        if (categories.get('shared-adapted-identity') != adapted
                or hashlib.sha256(_plain_file(ROOT.parent / oracle.F18_ADAPTED_PATH)).hexdigest() != oracle.F18_ADAPTED_SHA256):
            raise SystemExit('F29 existing adapted advice identity differs')
        generated = categories.get('generated', {})
        if set(generated) != set(oracle.OUTPUTS):
            raise SystemExit('F29 oracle evidence generated output set differs')
        for relative in oracle.OUTPUTS:
            if hashlib.sha256(_plain_file(ROOT.parent / oracle.OUTPUT_ROOT / relative)).hexdigest() != generated[relative]:
                raise SystemExit('F29 original wrapper bytes differ from extractor evidence')
    except (OSError, UnicodeError, KeyError, TypeError):
        raise SystemExit('F29 original oracle identity evidence is missing or malformed') from None


def verify_results():
    require_measurements()
    require_owner()
    require_oracle_receipt()
    result = {}
    try:
        for task, classes in REPORTS.items():
            directory = ROOT / 'boot/build/test-results' / task
            if {p.name for p in directory.glob('TEST-*.xml')} != {'TEST-' + name + '.xml' for name in classes}:
                raise SystemExit('F29 exact UTC/Seoul XML class set differs')
            result[task] = {}
            for name, expected in classes.items():
                target = directory / ('TEST-' + name + '.xml')
                suite = ET.fromstring(_plain_file(target))
                cases = suite.findall('testcase')
                if suite.tag != 'testsuite' or suite.get('name') != name:
                    raise SystemExit('F29 XML suite FQN differs')
                values = {k: int(suite.attrib[k]) for k in ('tests', 'failures', 'errors', 'skipped')}
                if (values != {'tests': expected, 'failures': 0, 'errors': 0, 'skipped': 0}
                        or len(cases) != expected or len(list(suite.iter('testcase'))) != expected
                        or int(suite.get('disabled', '0')) != 0):
                    raise SystemExit('F29 XML aggregate or actual testcase counts differ')
                if any(suite.find('.//' + tag) is not None for tag in ('failure', 'error', 'skipped')):
                    raise SystemExit('F29 XML contains a hidden unsuccessful result')
                if any(case.get('classname') != name for case in cases):
                    raise SystemExit('F29 XML actual testcase FQN differs')
                if task == SEOUL_TASK and cases[0].get('name') not in (SEOUL_METHOD, SEOUL_METHOD + '()'):
                    raise SystemExit('F29 Seoul must contain only its exact measured timestamp method')
                result[task][name] = values
    except (OSError, ET.ParseError, KeyError, ValueError):
        raise SystemExit('F29 compatibility XML is missing or malformed') from None
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / 'f29-results.json').write_text(json.dumps(result, sort_keys=True) + '\n')
    print('Verified measured F29 UTC PG/MVC and one exact Seoul timestamp XML')


# Runtime send/token/note-SSE probe is separately owned; import it only for smoke.


def smoke():
    f2.require_hosted()
    import sys
    from f29_stream_probe import run
    return run(sys.modules[__name__])



# Late member-cleanup fixture extension only: ordinary prepare/results/smoke stay unchanged.
MEMBER_RECEIPT = 'member-cleanup-fixture-write.txt'
MEMBER_TABLES = (
    ('refresh_tokens','pawbridge_user.refresh_tokens','refresh_token_id'),
    ('shelter_applications','pawbridge_user.shelter_applications','id'),
    ('chat_rooms','pawbridge_community.member_chat_rooms','room_id'),
    ('chat_members','pawbridge_community.member_chat_room_members','room_id,member_id'),
    ('chat_messages','pawbridge_community.member_chat_messages','room_id,sequence'),
    ('chat_requests','pawbridge_community.member_chat_requests','sender_id,request_id'),
)
MEMBER_FRAGMENTS = ('cf93ed908b18f63f59832cd0fe5bcf500ccf9e4c39972deeda5de446c4dd953d', 'eb3fe612fe9d4aff8372539b0d59e1da4c34266036ce88f1ed0ba51b88f5e139', 'b705222e7a9c9a6964628c937cff63470e1dd30103b27111ae3c5d94d284e1ec', '7afb8a3d992eba1258d61a610a418133f56766e9def3eb2e105c584bedcd2fdb')
MEMBER_GRANTS = """GRANT SELECT(user_id),UPDATE(user_id),DELETE ON pawbridge_user.users TO pawbridge_f3_writer;
GRANT INSERT(user_id) ON pawbridge_user.contact_deletions TO pawbridge_f3_writer;
GRANT SELECT(user_id),DELETE ON pawbridge_user.favorites,pawbridge_user.refresh_tokens,pawbridge_user.shelter_applications TO pawbridge_f3_writer;
GRANT DELETE ON pawbridge_community.private_note_members TO pawbridge_f3_writer;
GRANT SELECT(recipient_id),UPDATE(context_type,context_id) ON pawbridge_community.private_notes TO pawbridge_f3_writer;
GRANT UPDATE(request_hash) ON pawbridge_community.private_note_requests TO pawbridge_f3_writer;
GRANT SELECT(member_id,room_id),DELETE ON pawbridge_community.member_chat_room_members TO pawbridge_f3_writer;
GRANT SELECT(sender_id),DELETE ON pawbridge_community.member_chat_requests TO pawbridge_f3_writer;
GRANT SELECT(sender_id),UPDATE(sender_id) ON pawbridge_community.member_chat_messages TO pawbridge_f3_writer;
GRANT SELECT(room_id,first_member_id,second_member_id),UPDATE(first_member_id,second_member_id),DELETE ON pawbridge_community.member_chat_rooms TO pawbridge_f3_writer;
"""


def member_fixture_proof(owner):
    return ('owner=' + owner + '\nsource_sha=' + SOURCE + '\n'
            'fixture=F29 plus User deletion and installed-chat cleanup\n'
            'new_business_ddl=0\nsynthetic_tables_added=6\nsynthetic_identity_sequences_added=2\n'
            'new_schema=0\nnew_marker=0\nsnapshot_tables=24\nreader_delta=0\nnew_sequence_acl=0\n'
            + ''.join(digest + '  member-ddl-fragment-' + str(i) + '\n'
                      for i, digest in enumerate(MEMBER_FRAGMENTS))
            + hashlib.sha256(MEMBER_GRANTS.encode()).hexdigest() + '  member-exact-grants\n')


def member_require_oracle_receipt():
    # Canonical source40/raw-shared22/generated6 receipt, independent of owner/ACL denial.
    # Its immutable bytes also bind the unchanged original F29 source22/generated3 receipt.
    try:
        extra = EVIDENCE / 'member-cleanup-oracle-sha256.txt'
        if extra.is_symlink() or extra.resolve() != extra or not extra.is_file():
            raise ValueError('receipt path')
        raw = extra.read_bytes()
        if hashlib.sha256(raw).hexdigest() != 'df12ad2ab091706dd0e28762acec7825a1d345d4948f5805b128e3e52f367c9a':
            raise ValueError('receipt identity')
        lines = raw.decode('utf-8').splitlines()
        if lines[0] != 'source_sha=' + SOURCE or any(
                sum('  ' + kind + ' ' in line for line in lines) != count
                for kind, count in (('source', 40), ('shared-byte-identity', 22), ('generated', 6), ('base-F29-receipt', 1))):
            raise ValueError('receipt inventory')
        base = EVIDENCE / 'f29-oracle-sha256.txt'
        if base.is_symlink() or base.resolve() != base or not base.is_file():
            raise ValueError('base receipt path')
        base_raw = base.read_bytes()
        if hashlib.sha256(base_raw).hexdigest() + '  base-F29-receipt ' + oracle.EVIDENCE_PATH not in lines:
            raise ValueError('base receipt identity')
        base_lines = base_raw.decode('utf-8').splitlines()
        if sum('  generated ' in line for line in base_lines) != 3 or sum('  shared-adapted-identity ' in line for line in base_lines) != 1:
            raise ValueError('base artifact inventory')
        output = ROOT / 'boot/build/generated/sources/f29LegacyOracle/java'
        for line in lines + base_lines:
            match = re.fullmatch(r'([0-9a-f]{64})  (shared-byte-identity|shared-adapted-identity|generated) (.+)', line)
            if match is None:
                continue
            digest, kind, relative = match.groups()
            relative = Path(relative)
            if relative.is_absolute() or '..' in relative.parts:
                raise ValueError('artifact path')
            artifact = (output if kind == 'generated' else ROOT.parent) / relative
            if artifact.is_symlink() or artifact.resolve() != artifact or not artifact.is_file() or hashlib.sha256(artifact.read_bytes()).hexdigest() != digest:
                raise ValueError('artifact identity')
    except (OSError, UnicodeError, ValueError, IndexError):
        raise SystemExit('Member cleanup oracle receipt or source artifacts differ') from None


def member_require_identity():
    # Safe even while deliberately denying User/read or cleanup writer columns.
    owner = f2.require_hosted()
    require_prior_receipts(owner)  # marker-only chain, not User-seven/full ACL
    f21.check_receipt(EVIDENCE / 'f29-fixture-write.txt', fixture_proof(owner))
    f21.check_receipt(EVIDENCE / MEMBER_RECEIPT, member_fixture_proof(owner))
    member_require_oracle_receipt()
    return owner


def member_verified_ddl():
    inputs = {}
    for path in ('user-service/src/migration/resources/db/postgresql/V1__user_schema.sql','user-service/src/migration/resources/db/postgresql/V2__rollback_charset_compatibility.sql','community-service/src/migration/resources/db/postgresql/V10__member_chat.sql'):
        raw = subprocess.check_output(['git', 'show', SOURCE + ':' + path], cwd=ROOT.parent, stderr=subprocess.PIPE)
        if hashlib.sha256(raw).hexdigest() != oracle.MEMBER_INPUTS.get(path):
            raise SystemExit('Member cleanup original DDL source differs')
        inputs[path] = raw.decode('utf-8')
    user = inputs['user-service/src/migration/resources/db/postgresql/V1__user_schema.sql']; charset = inputs['user-service/src/migration/resources/db/postgresql/V2__rollback_charset_compatibility.sql']
    parts = [user[user.index('CREATE TABLE refresh_tokens ('):user.index('CREATE TABLE shelter_applications (')],
             user[user.index('CREATE TABLE shelter_applications ('):]]
    start = charset.index('ALTER TABLE refresh_tokens ')
    parts.append(charset[start:charset.index('\n\n', start) + 1])
    parts.append(inputs['community-service/src/migration/resources/db/postgresql/V10__member_chat.sql'])
    if tuple(hashlib.sha256(p.encode()).hexdigest() for p in parts) != MEMBER_FRAGMENTS:
        raise SystemExit('Member cleanup four original LF fragments differ')
    result=[]
    for part in parts:
        for name in ('refresh_tokens','shelter_applications'):
            part=part.replace('CREATE TABLE '+name,'CREATE TABLE pawbridge_user.'+name).replace(
                'ALTER TABLE '+name,'ALTER TABLE pawbridge_user.'+name).replace('ON '+name+' ','ON pawbridge_user.'+name+' ')
        result.append(part)
    return 'BEGIN;\n' + '\n'.join(result) + '\n' + MEMBER_GRANTS + 'COMMIT;\n'


def member_database_snapshot():
    # Full raw values/order for original18 + new6; no fallback to earlier strict/snapshot.
    member_require_identity()
    tables=SNAPSHOT_TABLES + MEMBER_TABLES
    fields=[f4.literal(key)+',(SELECT COALESCE(json_agg(r ORDER BY '+order+"),'[]'::json) FROM "+relation+' r)'
            for key,relation,order in tables]
    value=json.loads(owned_sql('SELECT json_build_object('+','.join(fields)+')'))
    if not isinstance(value,dict) or set(value)!={t[0] for t in tables} or any(not isinstance(v,list) for v in value.values()):
        raise SystemExit('Member cleanup requires exactly twenty-four raw tables')
    return value


def _member_require_fixture_without_user_writer_acl():
    owner=member_require_identity()
    # Initial DDL is hash-pinned and all six relations must be absent before creation.
    # Recheck complete column inventory, owner, PK/FK/index counts and exact app ACL.
    inventory={
      'refresh_tokens':(('refresh_token_id','created_at','expires_at','token','user_id'),3,2,0),
      'shelter_applications':(('id','user_id','shelter_name','status','requested_at','reviewed_by','reviewed_at','review_note','care_reg_no'),2,4,0),
      'member_chat_rooms':(('room_id','first_member_id','second_member_id','latest_sequence'),3,2,0),
      'member_chat_room_members':(('room_id','member_id','read_through','hidden_through'),3,2,1),
      'member_chat_messages':(('room_id','sequence','sender_id','body','context_type','context_id','created_at','expires_at'),5,2,1),
      'member_chat_requests':(('sender_id','request_id','request_hash','room_id','sequence','created_at'),1,2,0),
    }
    selects={'refresh_tokens':('user_id',),'shelter_applications':('user_id',),'member_chat_rooms':('room_id','first_member_id','second_member_id'),
             'member_chat_room_members':('member_id','room_id'),'member_chat_messages':('sender_id',),'member_chat_requests':('sender_id',)}
    updates={'member_chat_rooms':('first_member_id','second_member_id'),'member_chat_messages':('sender_id',)}
    deletes=set(inventory)-{'member_chat_messages'}
    for _,relation,_ in MEMBER_TABLES:
        table=relation.split('.')[1];columns,constraints,indexes,fks=inventory[table]
        expected='ARRAY['+','.join(f4.literal(c) for c in columns)+']::text[]'
        sql="SELECT (SELECT array_agg(attname::text ORDER BY attnum)="+expected+" FROM pg_attribute WHERE attrelid='"+relation+"'::regclass AND attnum>0 AND NOT attisdropped) AND (SELECT relkind='r' AND relpersistence='p' AND pg_get_userbyid(relowner)='postgres' FROM pg_class WHERE oid='"+relation+"'::regclass) AND (SELECT count(*)="+str(constraints)+" AND bool_and(convalidated AND NOT condeferrable) AND count(*) FILTER(WHERE contype='p')=1 AND count(*) FILTER(WHERE contype='f')="+str(fks)+" FROM pg_constraint WHERE conrelid='"+relation+"'::regclass) AND (SELECT count(*)="+str(indexes)+" AND bool_and(indisvalid AND indisready) FROM pg_index WHERE indrelid='"+relation+"'::regclass)"
        if owned_sql(sql)!='t':raise SystemExit('Member cleanup new table inventory/owner/constraints/indexes differs')
        for mode,allowed in [('SELECT',selects[table]),('INSERT',()),('UPDATE',updates.get(table,())) ,('REFERENCES',())]:
            predicate='column_name IN ('+','.join(f4.literal(c) for c in allowed)+')' if allowed else 'false'
            sql="SELECT bool_and(has_column_privilege('pawbridge_f3_writer','"+relation+"',column_name,'"+mode+"')=("+predicate+")) FROM information_schema.columns WHERE table_schema='"+relation.split('.')[0]+"' AND table_name='"+table+"'"
            if owned_sql(sql)!='t':raise SystemExit('Member cleanup new-table writer column ACL differs')
        sql="SELECT NOT has_table_privilege('pawbridge_f2_reader','"+relation+"','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_table_privilege('pawbridge_f3_writer','"+relation+"','SELECT,INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER') AND has_table_privilege('pawbridge_f3_writer','"+relation+"','DELETE')="+str(table in deletes).lower()+" AND (SELECT bool_and(NOT has_column_privilege('pawbridge_f2_reader','"+relation+"',column_name,'SELECT,INSERT,UPDATE,REFERENCES')) FROM information_schema.columns WHERE table_schema='"+relation.split('.')[0]+"' AND table_name='"+table+"')"
        if owned_sql(sql)!='t':raise SystemExit('Member cleanup reader/table ACL differs')
    for relation,field in [('pawbridge_user.refresh_tokens','refresh_token_id'),('pawbridge_user.shelter_applications','id')]:
        if owned_sql("SELECT pg_get_serial_sequence('"+relation+"','"+field+"') IS NOT NULL AND NOT has_sequence_privilege('pawbridge_f2_reader',pg_get_serial_sequence('"+relation+"','"+field+"'),'USAGE,SELECT,UPDATE') AND NOT has_sequence_privilege('pawbridge_f3_writer',pg_get_serial_sequence('"+relation+"','"+field+"'),'USAGE,SELECT,UPDATE')")!='t':raise SystemExit('Member cleanup identity requires zero app sequence ACL')
    # Preserve inherited nickname writes/readers; grant only cleanup deltas, never a User-wide SELECT.
    changed_acl={
      'pawbridge_user.favorites':(('user_id',),(),(),True),
      'pawbridge_user.contact_deletions':((),('user_id',),(),False),
      'pawbridge_community.private_note_members':(('member_id',),('member_id',),('member_id',),True),
      NOTES:(('note_id','expires_at','sender_id','recipient_id'),('note_id','sender_id','recipient_id','body','reply_to','context_type','context_id','created_at','expires_at'),('note_id','context_type','context_id'),True),
      REQUESTS:(('sender_id','request_id','request_hash','note_id','created_at'),('sender_id','request_id','request_hash','note_id','created_at'),('request_hash',),False),
    }
    for relation,(read,create,change,delete) in changed_acl.items():
        schema,table=relation.split('.')
        for mode,columns in [('SELECT',read),('INSERT',create),('UPDATE',change),('REFERENCES',())]:
            allowed='column_name IN ('+','.join(f4.literal(c) for c in columns)+')' if columns else 'false'
            query="SELECT bool_and(has_column_privilege('pawbridge_f3_writer','"+relation+"',column_name,'"+mode+"')=("+allowed+")) FROM information_schema.columns WHERE table_schema='"+schema+"' AND table_name='"+table+"'"
            if owned_sql(query)!='t':raise SystemExit('Member cleanup exact inherited-plus-delta writer columns differ')
        query="SELECT NOT has_table_privilege('pawbridge_f3_writer','"+relation+"','SELECT,INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER') AND has_table_privilege('pawbridge_f3_writer','"+relation+"','DELETE')="+str(delete).lower()
        if owned_sql(query)!='t':raise SystemExit('Member cleanup exact inherited-plus-delta writer table rights differ')
    require_reports_fixture()  # Unchanged relation-only checker, never old F29 global strict.
    return owner


def _member_require_user_writer_acl(read, change):
    relation='pawbridge_user.users'
    for mode,columns in [('SELECT',read),('INSERT',()),('UPDATE',change),('REFERENCES',())]:
        predicate='column_name IN ('+','.join(f4.literal(c) for c in columns)+')' if columns else 'false'
        if owned_sql("SELECT count(*)=11 AND bool_and(has_column_privilege('pawbridge_f3_writer','"+relation+"',column_name,'"+mode+"')=("+predicate+")) FROM information_schema.columns WHERE table_schema='pawbridge_user' AND table_name='users'")!='t':
            raise SystemExit('Member cleanup exact User writer columns differ')
    if owned_sql("SELECT NOT has_table_privilege('pawbridge_f3_writer','pawbridge_user.users','SELECT,INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER') AND has_table_privilege('pawbridge_f3_writer','pawbridge_user.users','DELETE')")!='t':
        raise SystemExit('Member cleanup exact User writer table rights differ')


def member_require_fixture():
    owner=_member_require_fixture_without_user_writer_acl()
    _member_require_user_writer_acl(('user_id','nickname'),('user_id','nickname','updated_at'))
    return owner


def member_cleanup_prepare():
    f2.require_hosted()
    owner=require_fixture()  # The final old F29 strict call: before new privileges/relations.
    member_require_oracle_receipt()  # Reject missing/changed source evidence before any DDL/GRANT.
    target=EVIDENCE/MEMBER_RECEIPT
    f21.check_receipt(target,member_fixture_proof(owner),allow_missing=True)
    if target.exists():raise SystemExit('Refusing an existing member cleanup receipt')
    ddl=member_verified_ddl()
    for _,relation,_ in MEMBER_TABLES:
        if owned_sql('SELECT to_regclass('+f4.literal(relation)+') IS NULL')!='t':raise SystemExit('Refusing existing member cleanup relation')
    for relation in ('pawbridge_user.refresh_tokens_refresh_token_id_seq','pawbridge_user.shelter_applications_id_seq'):
        if owned_sql('SELECT to_regclass('+f4.literal(relation)+') IS NULL')!='t':raise SystemExit('Refusing existing member cleanup sequence')
    before=database_snapshot()
    owned_sql(input_text=ddl)
    with target.open('x') as out:out.write(member_fixture_proof(owner))
    member_require_fixture()
    if member_database_snapshot()!=dict(before,**{key:[] for key,_,_ in MEMBER_TABLES}):raise SystemExit('Member cleanup extension changed prior rows')

# Late runtime only: never re-enter the pre-extension F29 strict/18-table smoke.
@contextmanager
def member_denied_privilege(kind):
    clauses = {'chat-update': 'UPDATE(sender_id) ON pawbridge_community.member_chat_messages',
               'user-delete': 'DELETE ON pawbridge_user.users'}
    if kind not in clauses:
        raise SystemExit('Unknown member cleanup privilege case')
    member_require_fixture()
    clause = clauses[kind]
    # Include a failed/lost REVOKE acknowledgement in the restoration boundary.
    try:
        owned_sql('REVOKE ' + clause + ' FROM pawbridge_f3_writer')
        yield
    finally:
        member_require_identity()  # Marker/receipt identity only while ACL is intentionally absent.
        owned_sql('GRANT ' + clause + ' TO pawbridge_f3_writer')
        member_require_fixture()


def member_require_runtime_results():
    member_require_identity()
    folder = ROOT / 'boot/build/test-results/memberCleanupCompatibilityTest'
    paths = list(folder.glob('TEST-*.xml'))
    if len(paths) != 1 or paths[0].is_symlink() or paths[0].resolve() != paths[0]:
        raise SystemExit('Member cleanup requires its one exact completed PG XML')
    try:
        suite = ET.parse(paths[0]).getroot()
        cases = suite.findall('testcase')
        if (suite.tag != 'testsuite' or suite.get('name') != PG_CLASS
                or paths[0].name != 'TEST-' + PG_CLASS + '.xml'
                or int(suite.get('tests', '-1')) != 12 or len(cases) != 12
                or len(list(suite.iter('testcase'))) != 12 or int(suite.get('disabled', '0')) != 0
                or any(int(suite.get(k, '-1')) != 0 for k in ('failures', 'errors', 'skipped'))
                or any(c.get('classname') != PG_CLASS or not c.get('name') for c in cases)
                or len({c.get('name') for c in cases}) != 12
                or any(c.get('status') not in (None, 'run', 'passed')
                       or c.get('result') not in (None, 'completed', 'passed', 'success') for c in cases)
                or any(node.tag in ('failure', 'error', 'skipped') for node in suite.iter())):
            raise ValueError()
    except (OSError, ET.ParseError, ValueError, TypeError):
        raise SystemExit('Member cleanup PG XML is incomplete, skipped or unsuccessful') from None


def member_cleanup_smoke():
    f2.require_hosted()
    import sys
    from f29_stream_probe import run_member_cleanup
    return run_member_cleanup(sys.modules[__name__])


# Optional late administrator-write fixture: no preceding member PG/JAR is required.
ADMIN_UPDATE_RECEIPT = 'admin-user-update-fixture-write.txt'
ADMIN_UPDATE_GRANTS = ('GRANT SELECT(role,care_reg_no),UPDATE(role,care_reg_no) '
                       'ON pawbridge_user.users TO pawbridge_f3_writer;')


def admin_update_fixture_proof(owner):
    return ('owner=' + owner + '\nsource_sha=c32ae0d84b69969abd35c541dbdd4e81738c4be5\n'
            'fixture=member cleanup plus administrator User update\nnew_business_ddl=0\nnew_marker=0\n'
            'snapshot_tables=24\nreader_delta=0\nnew_sequence_acl=0\n'
            'writer_user_select=user_id,nickname,role,care_reg_no\n'
            'writer_user_update=user_id,nickname,updated_at,role,care_reg_no\n'
            + hashlib.sha256(ADMIN_UPDATE_GRANTS.encode()).hexdigest() + '  admin-user-exact-grants\n')


def admin_update_require_oracle_receipt():
    import prepare_f13_legacy_oracle as admin
    try:
        outputs = {}
        for source, relative in admin.ADMIN_SHARED.items():
            artifact = ROOT.parent / relative
            if (artifact.is_symlink() or artifact.resolve() != artifact or not artifact.is_file()
                    or hashlib.sha256(artifact.read_bytes()).hexdigest() != admin.ADMIN_EXPECTED[source]):
                raise ValueError()
        for relative, expected in admin.ADMIN_GENERATED.items():
            artifact = ROOT / 'boot/build/generated/sources/f13LegacyOracle/java' / relative
            if (artifact.is_symlink() or artifact.resolve() != artifact or not artifact.is_file()
                    or hashlib.sha256(artifact.read_bytes()).hexdigest() != expected):
                raise ValueError()
            outputs[relative] = artifact.read_bytes()
        target = EVIDENCE / 'f13-admin-update-oracle-sha256.txt'
        if (target.is_symlink() or target.resolve() != target or not target.is_file()
                or target.read_text() != admin.admin_update_receipt(outputs)):
            raise ValueError()
    except (OSError, UnicodeError, ValueError):
        raise SystemExit('Administrator User update oracle receipt or source artifacts differ') from None


def admin_update_require_identity():
    owner = member_require_identity()  # Marker/receipt identity, safe during ACL denial.
    f21.check_receipt(EVIDENCE / ADMIN_UPDATE_RECEIPT, admin_update_fixture_proof(owner))
    admin_update_require_oracle_receipt()
    return owner


def admin_update_require_fixture():
    owner = admin_update_require_identity()
    _member_require_fixture_without_user_writer_acl()
    _member_require_user_writer_acl(('user_id','nickname','role','care_reg_no'),
                                    ('user_id','nickname','updated_at','role','care_reg_no'))
    return owner


def admin_update_prepare():
    f2.require_hosted()
    owner = member_require_fixture()  # Final old strict before the four new column rights.
    admin_update_require_oracle_receipt()  # Reject every artifact conflict before GRANT.
    target = EVIDENCE / ADMIN_UPDATE_RECEIPT
    f21.check_receipt(target, admin_update_fixture_proof(owner), allow_missing=True)
    if target.exists():
        raise SystemExit('Refusing an existing administrator User update fixture receipt')
    before = member_database_snapshot()
    owned_sql(input_text='BEGIN;\n' + ADMIN_UPDATE_GRANTS + '\nCOMMIT;\n')
    with target.open('x') as out:
        out.write(admin_update_fixture_proof(owner))
    admin_update_require_fixture()
    if member_database_snapshot() != before:
        raise SystemExit('Administrator User update extension changed the existing twenty-four tables')


@contextmanager
def admin_update_denied_privilege(kind):
    owner = admin_update_require_fixture()
    clauses = {'care-read': 'SELECT(care_reg_no)', 'role-write': 'UPDATE(role)'}
    if kind not in clauses:
        raise SystemExit('Unknown administrator User update privilege check')
    clause = clauses[kind] + ' ON pawbridge_user.users'
    try:
        owned_sql('REVOKE ' + clause + ' FROM pawbridge_f3_writer')
        yield
    finally:
        if admin_update_require_identity() != owner:
            raise SystemExit('Administrator User update owner changed before privilege restoration')
        owned_sql('GRANT ' + clause + ' TO pawbridge_f3_writer')
        admin_update_require_fixture()


# Visit collection has two explicit parents. The selected lane does not acquire admin User privileges.
VISIT_COLLECTION_RECEIPT = 'visit-collection-fixture-write.txt'
VISIT_COLLECTION_TABLES = (('daily_visitor','pawbridge_community.daily_visitor','visit_date,visitor_hash'),
                           ('visit_collection','pawbridge_community.visit_collection','singleton'))
VISIT_COLLECTION_GRANTS = """GRANT SELECT(visit_date,visitor_hash),INSERT(visit_date,visitor_hash),DELETE ON pawbridge_community.daily_visitor TO pawbridge_f3_writer;
GRANT SELECT(visit_date,unique_visitors),INSERT(visit_date,unique_visitors),UPDATE(unique_visitors) ON pawbridge_community.daily_visit_count TO pawbridge_f3_writer;
GRANT SELECT(singleton,started_at),INSERT(singleton,started_at) ON pawbridge_community.visit_collection TO pawbridge_f3_writer;
"""


def _visit_collection_proof(owner, parent):
    if parent not in ('member-cleanup', 'admin-user-update'):
        raise ValueError('Unknown fixed visit collection parent')
    return ('owner=' + owner + '\nsource_sha=33cad5567767f8730fc2c4389815ecd031f41dc0\n'
            'fixture=' + parent + ' plus visit collection\nnew_business_ddl=0\nsynthetic_tables_added=2\n'
            'new_schema=0\nnew_marker=0\nsnapshot_tables=26\nreader_delta=0\nnew_sequence_acl=0\n'
            + hashlib.sha256(VISIT_COLLECTION_GRANTS.encode()).hexdigest() + '  visit-collection-exact-grants\n')


def visit_collection_fixture_proof(owner):
    return _visit_collection_proof(owner, 'member-cleanup')


def admin_visit_collection_fixture_proof(owner):
    return _visit_collection_proof(owner, 'admin-user-update')


def visit_collection_require_oracle_receipt():
    import prepare_f16_legacy_oracle as visits
    try:
        for source, relative in visits.COLLECTION_SHARED.items():
            target = ROOT.parent / relative
            if (target.is_symlink() or target.resolve() != target or not target.is_file()
                    or hashlib.sha256(target.read_bytes()).hexdigest() != visits.COLLECTION_EXPECTED[source]):
                raise ValueError()
        for name, expected in (('f16-oracle-sha256.txt', visits.collection_base_receipt()),
                               ('f16-collection-oracle-sha256.txt', visits.collection_receipt())):
            target = EVIDENCE / name
            if (target.is_symlink() or target.resolve() != target or not target.is_file() or target.read_text() != expected):
                raise ValueError()
    except (OSError, UnicodeError, ValueError):
        raise SystemExit('Visit collection raw F16/F7 artifacts or separate source receipt differ') from None


def visit_collection_require_identity():
    owner = member_require_identity()
    f21.check_receipt(EVIDENCE / VISIT_COLLECTION_RECEIPT, visit_collection_fixture_proof(owner))
    visit_collection_require_oracle_receipt()
    return owner


def admin_visit_collection_require_identity():
    owner = admin_update_require_identity()
    f21.check_receipt(EVIDENCE / VISIT_COLLECTION_RECEIPT, admin_visit_collection_fixture_proof(owner))
    visit_collection_require_oracle_receipt()
    return owner


def _visit_collection_require_tables():
    expected = {
        'daily_visitor': (2, "('visit_date','date',0,'NO',0),('visitor_hash','character',64,'NO',0)", 'true', 'true', 'false', True, 1),
        'daily_visit_count': (2, "('visit_date','date',0,'NO',0),('unique_visitors','bigint',0,'NO',0)", 'true', 'true', "column_name='unique_visitors'", False, 2),
        'visit_collection': (2, "('singleton','boolean',0,'NO',0),('started_at','timestamp with time zone',0,'NO',6)", 'true', 'true', 'false', False, 2),
    }
    for table, (columns, shape, read, create, change, delete, constraints) in expected.items():
        relation = 'pawbridge_community.' + table
        where = " FROM information_schema.columns WHERE table_schema='pawbridge_community' AND table_name='" + table + "'"
        defaults = "CASE WHEN column_name='singleton' THEN column_default='true' ELSE column_default IS NULL END" if table == 'visit_collection' else 'column_default IS NULL'
        if owned_sql("SELECT count(*)=" + str(columns) + " AND bool_and(is_identity='NO' AND is_generated='NEVER' AND " + defaults
                     + " AND (column_name,data_type,COALESCE(character_maximum_length,0),is_nullable,COALESCE(datetime_precision,0)) IN (" + shape + "))" + where) != 't':
            raise SystemExit('Visit collection exact V11 column/default shape differs')
        reader = 'true' if table == 'daily_visit_count' else 'false'
        if owned_sql("SELECT count(*)=2 AND bool_and(has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'SELECT')=(" + read
                     + ") AND has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'INSERT')=(" + create
                     + ") AND has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'UPDATE')=(" + change
                     + ") AND NOT has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'REFERENCES')"
                     + " AND has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'SELECT')=" + reader
                     + " AND NOT has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'INSERT,UPDATE,REFERENCES')"
                     + " AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL)" + where) != 't':
            raise SystemExit('Visit collection exact writer/unchanged reader/no-sequence columns differ')
        if owned_sql("SELECT NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER')"
                     + " AND has_table_privilege('pawbridge_f3_writer','" + relation + "','DELETE')=" + str(delete).lower()
                     + " AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')") != 't':
            raise SystemExit('Visit collection exact table ACL differs')
        keys = ('visit_date','visitor_hash') if table == 'daily_visitor' else ('visit_date',) if table == 'daily_visit_count' else ('singleton',)
        names = 'ARRAY[' + ','.join(f4.literal(key) for key in keys) + ']::text[]'
        if owned_sql("SELECT (SELECT relkind='r' AND relpersistence='p' AND pg_get_userbyid(relowner)='postgres' FROM pg_class WHERE oid='" + relation + "'::regclass)"
                     + " AND (SELECT count(*)=" + str(constraints) + " AND bool_and(convalidated AND NOT condeferrable) AND count(*) FILTER(WHERE contype='p')=1 AND count(*) FILTER(WHERE contype='f')=0 FROM pg_constraint WHERE conrelid='" + relation + "'::regclass)"
                     + " AND (SELECT ARRAY(SELECT a.attname::text FROM unnest(c.conkey) WITH ORDINALITY k(num,ord) JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num ORDER BY k.ord)=" + names + " FROM pg_constraint c WHERE conrelid='" + relation + "'::regclass AND contype='p')"
                     + " AND (SELECT count(*)=1 AND bool_and(indisprimary AND indisunique AND indisvalid AND indisready AND indpred IS NULL AND indexprs IS NULL) FROM pg_index WHERE indrelid='" + relation + "'::regclass)") != 't':
            raise SystemExit('Visit collection owner/PK/constraint/index inventory differs')
        if table != 'daily_visitor':
            check = 'unique_visitors>=0' if table == 'daily_visit_count' else 'singleton'
            if owned_sql("SELECT count(*)=1 AND bool_and(convalidated AND regexp_replace(pg_get_expr(conbin,conrelid),'::bigint|[()[:space:]]','','g')=" + f4.literal(check) + ") FROM pg_constraint WHERE conrelid='" + relation + "'::regclass AND contype='c'") != 't':
                raise SystemExit('Visit collection original V11 CHECK differs')


def visit_collection_require_fixture():
    owner = visit_collection_require_identity()
    _member_require_fixture_without_user_writer_acl()
    _member_require_user_writer_acl(('user_id','nickname'), ('user_id','nickname','updated_at'))
    _visit_collection_require_tables()
    return owner


def admin_visit_collection_require_fixture():
    owner = admin_visit_collection_require_identity()
    _member_require_fixture_without_user_writer_acl()
    _member_require_user_writer_acl(('user_id','nickname','role','care_reg_no'), ('user_id','nickname','updated_at','role','care_reg_no'))
    _visit_collection_require_tables()
    return owner


def _visit_collection_snapshot():
    tables = SNAPSHOT_TABLES + MEMBER_TABLES + VISIT_COLLECTION_TABLES
    fields = [f4.literal(key) + ',(SELECT COALESCE(json_agg(r ORDER BY ' + order + "),'[]'::json) FROM " + relation + ' r)' for key, relation, order in tables]
    value = json.loads(owned_sql('SELECT json_build_object(' + ','.join(fields) + ')'))
    if not isinstance(value,dict) or set(value) != {key for key,_,_ in tables} or any(not isinstance(v,list) for v in value.values()):
        raise SystemExit('Visit collection requires exactly twenty-six raw tables')
    return value


def visit_collection_database_snapshot():
    visit_collection_require_identity()
    return _visit_collection_snapshot()


def admin_visit_collection_database_snapshot():
    admin_visit_collection_require_identity()
    return _visit_collection_snapshot()


def _visit_collection_extend(owner, proof, require_new, snapshot_new):
    import prepare_f16_legacy_oracle as visits
    visit_collection_require_oracle_receipt()  # Before any new table, grant or row write.
    target = EVIDENCE / VISIT_COLLECTION_RECEIPT
    f21.check_receipt(target, proof, allow_missing=True)
    if target.exists():
        raise SystemExit('Refusing an existing visit collection fixture receipt')
    raw = subprocess.check_output(['git','show',visits.SOURCE + ':' + visits.COLLECTION_DDL], cwd=ROOT.parent, stderr=subprocess.PIPE)
    fragments = visits.collection_fragments(raw)
    if owned_sql("SELECT count(*)=2 AND bool_and(has_column_privilege('pawbridge_f2_reader','pawbridge_community.daily_visit_count',column_name,'SELECT') AND NOT has_column_privilege('pawbridge_f2_reader','pawbridge_community.daily_visit_count',column_name,'INSERT,UPDATE,REFERENCES') AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_community.daily_visit_count',column_name,'SELECT,INSERT,UPDATE,REFERENCES')) AND NOT has_table_privilege('pawbridge_f2_reader','pawbridge_community.daily_visit_count','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_community.daily_visit_count','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') FROM information_schema.columns WHERE table_schema='pawbridge_community' AND table_name='daily_visit_count'") != 't':
        raise SystemExit('Visit collection requires unchanged parent reader-two/writer-zero count ACL before grant')
    for _, relation, _ in VISIT_COLLECTION_TABLES:
        if owned_sql('SELECT to_regclass(' + f4.literal(relation) + ') IS NULL') != 't':
            raise SystemExit('Refusing an existing visit collection business table')
    before = member_database_snapshot()
    owned_sql(input_text='BEGIN;\n' + ''.join(fragments) + '\n' + VISIT_COLLECTION_GRANTS + 'COMMIT;\n')
    with target.open('x') as out:
        out.write(proof)
    if require_new() != owner:
        raise SystemExit('Visit collection owner changed after preparation')
    if snapshot_new() != dict(before, **{key:[] for key,_,_ in VISIT_COLLECTION_TABLES}):
        raise SystemExit('Visit collection extension changed existing twenty-four rows')


def visit_collection_prepare():
    f2.require_hosted()
    owner = member_require_fixture()  # Last old member24 strict, selected lane only.
    _visit_collection_extend(owner, visit_collection_fixture_proof(owner), visit_collection_require_fixture, visit_collection_database_snapshot)


def admin_visit_collection_prepare():
    f2.require_hosted()
    owner = admin_update_require_fixture()  # Last old admin24 strict, full lane only.
    _visit_collection_extend(owner, admin_visit_collection_fixture_proof(owner), admin_visit_collection_require_fixture, admin_visit_collection_database_snapshot)


# Optional late auth-session fixture: the reviewed V4 auth_sessions table plus exact writer rights.
# Earlier member/admin strict paths remain exact and unchanged; restore drops the table again.
AUTH_SESSIONS_RECEIPT = 'auth-sessions-fixture-write.txt'
AUTH_SESSIONS_DDL = 'user-service/src/migration/resources/db/postgresql/V4__auth_sessions.sql'
AUTH_SESSIONS_DDL_SHA = 'fca4151b1fc5a966fef9514c0d3a6eb3562670f55a6cbd13955ffbd7e25dd0c1'
AUTH_SESSIONS_COLUMNS = ('session_id', 'user_id', 'token_hash', 'previous_token_hash', 'rotated_at', 'created_at',
                         'last_used_at', 'expires_at', 'absolute_expires_at', 'revoked_at')
AUTH_SESSIONS_INSERT = ('user_id', 'token_hash', 'created_at', 'last_used_at', 'expires_at', 'absolute_expires_at')
AUTH_SESSIONS_UPDATE = ('token_hash', 'previous_token_hash', 'rotated_at', 'last_used_at', 'expires_at', 'revoked_at')
AUTH_SESSIONS_GRANTS = ('GRANT SELECT(email,name) ON pawbridge_user.users TO pawbridge_f3_writer;\n'
                        'GRANT SELECT(' + ','.join(AUTH_SESSIONS_COLUMNS) + '),'
                        'INSERT(' + ','.join(AUTH_SESSIONS_INSERT) + '),'
                        'UPDATE(' + ','.join(AUTH_SESSIONS_UPDATE) + '),DELETE '
                        'ON pawbridge_user.auth_sessions TO pawbridge_f3_writer;')


def auth_sessions_ddl():
    artifact = ROOT.parent / AUTH_SESSIONS_DDL
    try:
        if artifact.is_symlink() or not artifact.is_file():
            raise ValueError()
        raw = artifact.read_bytes()
        if hashlib.sha256(raw).hexdigest() != AUTH_SESSIONS_DDL_SHA:
            raise ValueError()
        return raw.decode('utf-8')
    except (OSError, UnicodeError, ValueError):
        raise SystemExit('Auth session V4 DDL differs from the reviewed bytes') from None


def auth_sessions_fixture_proof(owner):
    return ('owner=' + owner + '\nsource_sha=f06a72a06bd26662c647bde780dfc7a27bf7349c\n'
            'fixture=administrator User update plus refresh session table and writer\n'
            'new_business_ddl=1\nnew_marker=0\nsnapshot_tables=24\nreader_delta=0\nnew_sequence_acl=0\n'
            'writer_user_select=user_id,nickname,role,care_reg_no,email,name\n'
            'writer_session_select=' + ','.join(AUTH_SESSIONS_COLUMNS) + '\n'
            'writer_session_insert=' + ','.join(AUTH_SESSIONS_INSERT) + '\n'
            'writer_session_update=' + ','.join(AUTH_SESSIONS_UPDATE) + '\n'
            'writer_session_delete=1\n'
            + AUTH_SESSIONS_DDL_SHA + '  auth-session-ddl\n'
            + hashlib.sha256(AUTH_SESSIONS_GRANTS.encode()).hexdigest() + '  auth-session-exact-grants\n')


def auth_sessions_require_oracle_receipt():
    import prepare_f13_legacy_oracle as auth
    try:
        for path, relative in auth.AUTH_SHARED.items():
            artifact = ROOT.parent / relative
            if (artifact.is_symlink() or artifact.resolve() != artifact or not artifact.is_file()
                    or hashlib.sha256(artifact.read_bytes()).hexdigest() != auth.AUTH_EXPECTED[path]):
                raise ValueError()
        outputs = {}
        for relative, expected in auth.AUTH_GENERATED.items():
            artifact = ROOT / 'boot/build/generated/sources/f13LegacyOracle/java' / relative
            if (artifact.is_symlink() or artifact.resolve() != artifact or not artifact.is_file()
                    or hashlib.sha256(artifact.read_bytes()).hexdigest() != expected):
                raise ValueError()
            outputs[relative] = artifact.read_bytes()
        target = EVIDENCE / 'f13-auth-sessions-oracle-sha256.txt'
        if (target.is_symlink() or target.resolve() != target or not target.is_file()
                or target.read_text() != auth.auth_sessions_receipt(outputs)):
            raise ValueError()
    except (OSError, UnicodeError, ValueError):
        raise SystemExit('Auth session oracle receipt or fixed sources differ') from None


def auth_sessions_require_identity():
    owner = admin_update_require_identity()  # marker/receipt only; safe while exact new rights are denied.
    f21.check_receipt(EVIDENCE / AUTH_SESSIONS_RECEIPT, auth_sessions_fixture_proof(owner))
    auth_sessions_require_oracle_receipt()
    return owner


def _auth_sessions_require_table_acl():
    relation = 'pawbridge_user.auth_sessions'
    expected = 'ARRAY[' + ','.join(f4.literal(c) for c in AUTH_SESSIONS_COLUMNS) + ']::text[]'
    inventory = ("SELECT (SELECT array_agg(attname::text ORDER BY attnum)=" + expected
                 + " FROM pg_attribute WHERE attrelid='" + relation + "'::regclass AND attnum>0 AND NOT attisdropped)"
                 + " AND (SELECT relkind='r' AND relpersistence='p' AND pg_get_userbyid(relowner)='postgres'"
                 + " FROM pg_class WHERE oid='" + relation + "'::regclass)")
    if owned_sql(inventory) != 't':
        raise SystemExit('Auth session table inventory or owner differs')
    for mode, allowed in [('SELECT', AUTH_SESSIONS_COLUMNS), ('INSERT', AUTH_SESSIONS_INSERT),
                          ('UPDATE', AUTH_SESSIONS_UPDATE), ('REFERENCES', ())]:
        predicate = 'column_name IN (' + ','.join(f4.literal(c) for c in allowed) + ')' if allowed else 'false'
        query = ("SELECT bool_and(has_column_privilege('pawbridge_f3_writer','" + relation + "',column_name,'" + mode
                 + "')=(" + predicate + ")) FROM information_schema.columns"
                 + " WHERE table_schema='pawbridge_user' AND table_name='auth_sessions'")
        if owned_sql(query) != 't':
            raise SystemExit('Auth session writer column ACL differs')
    rights = ("SELECT has_table_privilege('pawbridge_f3_writer','" + relation + "','DELETE')"
              + " AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER')"
              + " AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')"
              + " AND (SELECT bool_and(NOT has_column_privilege('pawbridge_f2_reader','" + relation + "',column_name,'SELECT,INSERT,UPDATE,REFERENCES'))"
              + " FROM information_schema.columns WHERE table_schema='pawbridge_user' AND table_name='auth_sessions')"
              + " AND NOT has_sequence_privilege('pawbridge_f3_writer',pg_get_serial_sequence('" + relation + "','session_id'),'USAGE,SELECT,UPDATE')")
    if owned_sql(rights) != 't':
        raise SystemExit('Auth session table rights differ')


def auth_sessions_require_fixture():
    owner = auth_sessions_require_identity()
    _member_require_fixture_without_user_writer_acl()
    _member_require_user_writer_acl(('user_id','nickname','role','care_reg_no','email','name'),
                                  ('user_id','nickname','updated_at','role','care_reg_no'))
    _auth_sessions_require_table_acl()
    return owner


def auth_sessions_prepare():
    f2.require_hosted()
    owner = admin_update_require_fixture()  # Final old strict, before the seven exact new column rights.
    auth_sessions_require_oracle_receipt()
    target = EVIDENCE / AUTH_SESSIONS_RECEIPT
    f21.check_receipt(target, auth_sessions_fixture_proof(owner), allow_missing=True)
    if target.exists():
        raise SystemExit('Refusing an existing auth session fixture receipt')
    ddl = auth_sessions_ddl()
    if owned_sql("SELECT to_regclass('pawbridge_user.auth_sessions') IS NULL") != 't':
        raise SystemExit('Refusing an existing auth session table')
    before = member_database_snapshot()
    # V4 uses unqualified names as Flyway runs it with the pawbridge_user default schema.
    owned_sql(input_text='BEGIN;\nSET LOCAL search_path TO pawbridge_user;\n' + ddl + '\n'
              + AUTH_SESSIONS_GRANTS + '\nCOMMIT;\n')
    with target.open('x') as output:
        output.write(auth_sessions_fixture_proof(owner))
    auth_sessions_require_fixture()
    if member_database_snapshot() != before:
        raise SystemExit('Auth session extension changed existing twenty-four tables')


# Remove only the auth-only user column rights and the auth_sessions table; retain all original
# administrator/member privileges.
AUTH_SESSIONS_REVOKES = ('REVOKE SELECT(email,name) ON pawbridge_user.users FROM pawbridge_f3_writer;\n'
                         'DROP TABLE IF EXISTS pawbridge_user.auth_sessions;')


def auth_sessions_restore():
    f2.require_hosted()
    owner = auth_sessions_require_identity()  # No full ACL check before recovery; PG may have failed mid-denial.
    before = member_database_snapshot()
    owned_sql(input_text='BEGIN;\n' + AUTH_SESSIONS_REVOKES + '\nCOMMIT;\n')
    if admin_update_require_fixture() != owner:
        raise SystemExit('Auth restoration changed the prior administrator fixture owner')
    if member_database_snapshot() != before:
        raise SystemExit('Auth privilege restoration changed twenty-four table rows')


@contextmanager
def auth_sessions_denied_privilege(kind):
    owner = auth_sessions_require_fixture()
    clauses = {'email-read': 'SELECT(email) ON pawbridge_user.users',
               'rotate-write': 'UPDATE(token_hash) ON pawbridge_user.auth_sessions',
               'cleanup-delete': 'DELETE ON pawbridge_user.auth_sessions'}
    if kind not in clauses:
        raise SystemExit('Unknown auth session privilege check')
    clause = clauses[kind]
    try:
        owned_sql('REVOKE ' + clause + ' FROM pawbridge_f3_writer')
        yield
    finally:
        if auth_sessions_require_identity() != owner:
            raise SystemExit('Auth session owner changed before privilege restoration')
        owned_sql('GRANT ' + clause + ' TO pawbridge_f3_writer')
        auth_sessions_require_fixture()


if __name__ == "__main__":
    import sys
    f2.require_hosted()
    {"prepare": prepare, "results": verify_results, "smoke": smoke, "member-cleanup-prepare": member_cleanup_prepare, "member-cleanup-smoke": member_cleanup_smoke, "admin-update-prepare": admin_update_prepare, "visit-collection-prepare": visit_collection_prepare, "admin-visit-collection-prepare": admin_visit_collection_prepare, "auth-sessions-prepare": auth_sessions_prepare, "auth-sessions-restore": auth_sessions_restore}[sys.argv[1]]()
