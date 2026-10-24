#!/usr/bin/env python3
"""Private F21 draft: expected counts require actual hosted-owned XML/JAR evidence."""
from contextlib import contextmanager
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
import urllib.parse
from uuid import UUID

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f17_ci as f17
import f19_ci as f19
import f20_ci as f20

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
SOURCE = 'd436e3c43fa220cd4004d9f39040f809915ec6ae'
MIGRATIONS = 'community-service/src/migration/resources/db/postgresql/'
DDL_HASHES = {
    MIGRATIONS + 'V6__private_notes.sql': '3ec7def5eed39ddbf963839c7fd8648ecca9bc168570403778c3341e8f31c119',
    MIGRATIONS + 'V7__private_note_cleanup_indexes.sql': 'f1dcd3476bf56eb7862ffcdbdaa0546a59c94a0fb637c04c7a5d6fb677da1cc6',
    MIGRATIONS + 'V8__private_note_reference_indexes.sql': 'd2572afc2644df3f932872bcd757e47937cdf7d5b89789439cb103de6c71512d',
}
FRAGMENT_HASHES = (
    'ca49dcb049a07c9d7487496cb5a0d7a4e33fe10a3d77255353ca1defe6d5f7f1',
    'ec1b4aeea247dde28a2992ba714e40682abcbc7ef2181bbb49432cb22ba66a29',
    'abaad1e5d3b3de943761406121fb89fd76746736267f64d2cd4f5dfb5487be2f',
    '435db5c5bf80440787d75f6409306e6f8829a8ee63a98284003c1f14aac6c021',
    '4419cbf1b5cfc7cc097c21230fa1e616a3496addee756954b8f6b004789b34d4',
    '2f5c96cfa237a5f3ac3e5fa647181e175e95342f7ca2db99f9bab02aebfc88b3',
)
# Expected packaged request counts reviewed with root; only actual successful JAR observations can pass.
EXPECTED_JAR_COUNTS = {'notifications_get_checks': 8, 'successful_checks': 5, 'rejected_checks': 3,
                       'cursor_checks': 2, 'empty_checks': 1, 'storage_unavailable_checks': 1,
                       'member_unavailable_checks': 1, 'authentication_checks': 1, 'denied_route_checks': 1}
COUNTERS = ('notifications_get_checks', 'successful_checks', 'rejected_checks', 'cursor_checks',
            'empty_checks', 'storage_unavailable_checks', 'member_unavailable_checks',
            'authentication_checks', 'denied_route_checks')
CALLER, NORMAL, RAW, PENDING, MISSING, OTHER = range(9975001, 9975007)
USERS = (CALLER, NORMAL, RAW, PENDING, OTHER)
MEMBERS = (CALLER, NORMAL, RAW, PENDING, MISSING, OTHER)
NAMES = {CALLER: 'F21caller', NORMAL: 'F21normal', RAW: '  ', PENDING: 'F21pending', OTHER: 'F21other'}
STORED_TIME, EXPIRES = '2026-10-18T15:00:00.123456Z', '2099-01-01T00:00:00Z'
BODY = 'F21 synthetic private body'
PATH, SUCCESS = '/api/v1/notes/notifications', '요청이 성공적으로 처리되었습니다.'
WITHDRAWN = '탈퇴한 회원'
MEMBER_UNAVAILABLE = '회원 정보를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.'
STORAGE_UNAVAILABLE = '쪽지 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
TABLES = (('pawbridge_community', 'private_notes'), ('pawbridge_community', 'private_note_mailboxes'))
READABLE = {'private_notes': ('note_id', 'sender_id', 'created_at', 'expires_at'),
            'private_note_mailboxes': ('member_id', 'note_id', 'direction', 'read_at', 'created_at')}
NOTE_IDS = tuple(str(UUID(int=210000 + i)) for i in range(1, 24))
FOREIGN, SENT, EXPIRED = (str(UUID(int=210000 + i)) for i in (101, 102, 103))
ALL_NOTES = (*NOTE_IDS, FOREIGN, SENT, EXPIRED)
MAILBOXES = tuple((CALLER, note) for note in NOTE_IDS) + ((OTHER, FOREIGN), (CALLER, SENT), (CALLER, EXPIRED))
OWNED = (USERS, MEMBERS, ALL_NOTES, MAILBOXES, (PENDING,))


def owned_sql(query='', *, input_text=None):
    try:
        return f3.sql(query, input_text=input_text) if input_text is not None else f3.sql(query)
    except subprocess.CalledProcessError:
        raise SystemExit('F21 owned fixture SQL failed without exposing command, SQL or output') from None


def f20_proof(owner):
    return ('owner=' + owner + '\nfixture=F17/F18/F19 reused\nnew_business_ddl=0\n'
            'added_reader_column=private_note_blocks.created_at\nnew_writer_acl=0\nnew_sequence_acl=0\nnew_marker=0\n')


def check_receipt(path, expected, *, allow_missing=False):
    if path.is_symlink() or path.resolve() != path or (path.exists() and not path.is_file()):
        raise SystemExit('F21 receipt path is redirected or not a regular file')
    if not path.exists():
        if allow_missing: return
        raise SystemExit('F21 required owned receipt is missing')
    if path.read_text() != expected:
        raise SystemExit('F21 receipt content or owner differs')


def require_owner():
    owner = f20.require_owner()  # F16/User-seven + F17 marker + F19 evidence; never old blocks-two ACL.
    check_receipt(EVIDENCE / 'f20-fixture-reuse.txt', f20_proof(owner))
    return owner


def verified_ddl():
    f2.require_hosted()
    for path in (MIGRATIONS + 'V6__private_notes.sql', MIGRATIONS + 'V8__private_note_reference_indexes.sql'):
        if DDL_HASHES[path] != f17.DDL_HASHES.get(path):
            raise SystemExit('F21 original migration hashes disagree with the existing F17 pins')
    inputs = {}
    for path, digest in DDL_HASHES.items():
        raw = subprocess.check_output(['git', 'show', SOURCE + ':' + path], cwd=ROOT.parent)
        if hashlib.sha256(raw).hexdigest() != digest:
            raise SystemExit('F21 original migration checksum mismatch: ' + path)
        inputs[Path(path).name] = raw.decode('utf-8')
    source = inputs['V6__private_notes.sql']
    fragments = [source[source.index('CREATE TABLE private_notes ('):source.index('CREATE TABLE private_note_mailboxes (')].strip(),
                 source[source.index('CREATE TABLE private_note_mailboxes ('):source.index('CREATE TABLE private_note_requests (')].strip()]
    names = ('idx_private_note_mailbox_note', 'idx_private_note_sender', 'idx_private_note_recipient', 'idx_private_note_reply_to')
    for name in names:
        text = inputs['V8__private_note_reference_indexes.sql' if name.endswith('reply_to') else 'V7__private_note_cleanup_indexes.sql']
        lines = [line for line in text.splitlines() if line.startswith('CREATE INDEX ' + name + ' ')]
        if len(lines) != 1: raise SystemExit('F21 original index fragment is not unique')
        fragments.append(lines[0])
    if tuple(hashlib.sha256(fragment.encode()).hexdigest() for fragment in fragments) != FRAGMENT_HASHES:
        raise SystemExit('F21 exact original DDL/index fragment hash mismatch')
    unqualified = '\n'.join(fragments) + '\n'
    if (unqualified.count('CREATE TABLE ') != 2 or unqualified.count('CREATE INDEX ') != 8
            or 'CREATE TABLE private_note_members' in unqualified or 'private_note_requests' in unqualified):
        raise SystemExit('F21 original fragments exceed the two-table/eight-index boundary')
    qualified = unqualified
    for table in ('private_note_members', 'private_notes', 'private_note_mailboxes'):
        for prefix in ('CREATE TABLE ', 'REFERENCES ', 'ON '):
            qualified = qualified.replace(prefix + table, prefix + 'pawbridge_community.' + table)
    grants = ''.join('GRANT SELECT(' + ','.join(columns) + ') ON pawbridge_community.' + table
                     + ' TO pawbridge_f2_reader;\n' for table, columns in READABLE.items())
    return 'BEGIN;\n' + qualified + grants + 'COMMIT;\n', source_proof()


def source_proof():
    return ('source_sha=' + SOURCE + '\n' + ''.join(digest + '  ' + path + '\n' for path, digest in DDL_HASHES.items())
            + ''.join(digest + '  original-fragment-' + str(index) + '\n' for index, digest in enumerate(FRAGMENT_HASHES)))


def fixture_proof(owner, source_proof):
    return ('owner=' + owner + '\nfixture=F20 plus original notes/mailboxes\nnew_business_tables=2\n'
            'new_schema=0\nnew_marker=0\nreader_columns=notes4/mailboxes5\nwriter_acl=0\nsequence_acl=0\n' + source_proof)


def require_f21_receipt(owner):
    # Identity/source receipts remain exact and independent of temporarily revoked grants.
    check_receipt(EVIDENCE / 'f21-fixture-reuse.txt', fixture_proof(owner, source_proof()))


def catalog(query, stage):
    if stage not in ('absence', 'shape', 'acl', 'constraints') or not query.startswith('SELECT '):
        raise ValueError('F21 diagnostics are limited to static read-only catalog checks')
    try:
        return f3.sql(query)
    except subprocess.CalledProcessError as failure:
        stderr = failure.stderr if isinstance(failure.stderr, str) else ''
        primary = next((line.strip() for line in stderr.splitlines() if re.match(r'^\s*(?:ERROR|FATAL):', line)),
                       '(no PostgreSQL primary error captured)')
        for key, value in os.environ.items():
            if value and any(label in key.upper() for label in ('PASSWORD', 'SECRET', 'TOKEN')):
                primary = primary.replace(value, '[redacted]')
        primary = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', primary)
        primary = ''.join(char for char in primary if char.isprintable())[:512]
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        (EVIDENCE / 'f21-fixture-catalog-error.txt').write_text('stage=f21-notifications-' + stage
            + '\nreturncode=' + str(failure.returncode) + '\nstderr=' + primary + '\n')
        raise SystemExit('F21 fixture query failed; see bounded f21-fixture-catalog-error.txt') from None


def expr():
    return "regexp_replace(pg_get_expr(conbin,conrelid),'::character varying|::text\\[\\]|::text|::bigint|[()[:space:]]','','g')"


def constraint_checks(table):
    relation = 'pawbridge_community.' + table
    columns = f17._columns
    primary = ('note_id',) if table == 'private_notes' else ('member_id', 'note_id')
    foreign = ((('sender_id',), 'private_note_members', ('member_id',), 'n'),
               (('recipient_id',), 'private_note_members', ('member_id',), 'n'),
               (('reply_to',), 'private_notes', ('note_id',), 'n')) if table == 'private_notes' else (
               (('member_id',), 'private_note_members', ('member_id',), 'c'),
               (('note_id',), 'private_notes', ('note_id',), 'c'))
    checks = ((('body',), 'char_lengthbody>=1ANDchar_lengthbody<=5000'),
              (('context_type',), "context_type=ANYARRAY['POST','REPORT']"),
              (('context_type', 'context_id'), 'context_typeISNULL=context_idISNULL'),
              (('sender_id', 'recipient_id'), 'sender_idISNULLORrecipient_idISNULLORsender_id<>recipient_id')) if table == 'private_notes' else (
              (('direction',), "direction=ANYARRAY['INBOX','SENT']"),)
    terms = ["(SELECT count(*)=" + str(1 + len(foreign) + len(checks)) + " FROM pg_constraint WHERE conrelid='" + relation + "'::regclass)",
             "(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + relation + "'::regclass AND contype='p' AND convalidated "
             'AND NOT condeferrable AND conkey=' + columns(relation, primary) + ')']
    for local, target, remote, deletion in foreign:
        target = 'pawbridge_community.' + target
        terms.append("(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + relation + "'::regclass AND contype='f' "
                     "AND convalidated AND NOT condeferrable AND NOT condeferred AND confrelid='" + target + "'::regclass "
                     "AND confdeltype='" + deletion + "' AND confupdtype='a' AND confmatchtype='s' AND conkey="
                     + columns(relation, local) + ' AND confkey=' + columns(target, remote) + ')')
    for fields, expected in checks:
        terms.append("(SELECT count(*)=1 FROM pg_constraint WHERE conrelid='" + relation + "'::regclass AND contype='c' "
                     'AND convalidated AND conkey=' + columns(relation, fields) + ' AND ' + expr() + '=' + f4.literal(expected) + ')')
    return terms


def index_checks(table):
    relation = 'pawbridge_community.' + table
    definitions = [(table + '_pkey', ('note_id',), (), None, True)] if table == 'private_notes' else [
        (table + '_pkey', ('member_id', 'note_id'), (), None, True)]
    definitions += [('idx_private_notes_expiry', ('expires_at', 'note_id'), (), None, False),
                    ('idx_private_note_sender', ('sender_id',), (), 'sender_idISNOTNULL', False),
                    ('idx_private_note_recipient', ('recipient_id',), (), 'recipient_idISNOTNULL', False),
                    ('idx_private_note_reply_to', ('reply_to',), (), 'reply_toISNOTNULL', False)] if table == 'private_notes' else [
                    ('idx_private_note_mailbox_page', ('member_id', 'direction', 'created_at', 'note_id'), (2, 3), None, False),
                    ('idx_private_note_mailbox_favorite', ('member_id', 'created_at', 'note_id'), (1, 2), 'favorite', False),
                    ('idx_private_note_mailbox_unread', ('member_id', 'created_at', 'note_id'), (1, 2), "direction='INBOX'ANDread_atISNULL", False),
                    ('idx_private_note_mailbox_note', ('note_id',), (), None, False)]
    terms = ["(SELECT count(*)=5 FROM pg_index WHERE indrelid='" + relation + "'::regclass)"]
    for name, keys, desc, predicate, primary in definitions:
        conditions = ["i.indrelid='" + relation + "'::regclass", "c.relname='" + name + "'", "a.amname='btree'",
                      "pg_get_userbyid(c.relowner)='postgres'", "c.relpersistence='p'", 'i.indisvalid', 'i.indisready',
                      'i.indisunique=' + str(primary).lower(), 'i.indisprimary=' + str(primary).lower(),
                      'i.indexprs IS NULL', 'i.indnatts=' + str(len(keys)), 'i.indnkeyatts=' + str(len(keys))]
        for index, field in enumerate(keys):
            conditions.append('i.indkey[' + str(index) + "]=(SELECT attnum FROM pg_attribute WHERE attrelid='" + relation
                              + "'::regclass AND attname='" + field + "')")
            conditions.append('i.indoption[' + str(index) + ']=' + ('3' if index in desc else '0'))
        conditions.append('i.indpred IS NULL' if predicate is None else
                          "regexp_replace(pg_get_expr(i.indpred,i.indrelid),'::character varying|::text|[()[:space:]]','','g')=" + f4.literal(predicate))
        terms.append('(SELECT count(*)=1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid '
                     'JOIN pg_am a ON a.oid=c.relam WHERE ' + ' AND '.join(conditions) + ')')
    return terms


def require_fixture():
    owner = require_owner()
    if f20.require_fixture() != owner:  # Still exact blocks-three; no old-table ACL extension.
        raise SystemExit('F21 owner changed during inherited fixture validation')
    require_f21_receipt(owner)
    shapes = {
        'private_notes': "('note_id','uuid','NO',0,'',0),('sender_id','bigint','YES',0,'',0),('recipient_id','bigint','YES',0,'',0),"
                         "('body','text','NO',0,'',0),('reply_to','uuid','YES',0,'',0),('context_type','character varying','YES',0,'',10),"
                         "('context_id','bigint','YES',0,'',0),('created_at','timestamp with time zone','NO',6,'',0),('expires_at','timestamp with time zone','NO',6,'',0)",
        'private_note_mailboxes': "('member_id','bigint','NO',0,'',0),('note_id','uuid','NO',0,'',0),('direction','character varying','NO',0,'',5),"
                                 "('read_at','timestamp with time zone','YES',6,'',0),('favorite','boolean','NO',0,'false',0),('created_at','timestamp with time zone','NO',6,'',0)",
    }
    for schema, table in TABLES:
        relation, size = schema + '.' + table, 9 if table == 'private_notes' else 6
        where = " FROM information_schema.columns WHERE table_schema='" + schema + "' AND table_name='" + table + "'"
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and(is_identity='NO' AND is_generated='NEVER') AND bool_and("
                   '(column_name,data_type,is_nullable,COALESCE(datetime_precision,0),COALESCE(column_default,\'\'),COALESCE(character_maximum_length,0)) IN ('
                   + shapes[table] + ")) AND (SELECT relkind='r' AND relpersistence='p' AND pg_get_userbyid(relowner)='postgres' "
                   "FROM pg_class WHERE oid='" + relation + "'::regclass)" + where, 'shape') != 't':
            raise SystemExit('F21 fixture differs from the exact original table shape: ' + table)
        allowed = 'column_name IN (' + ','.join(f4.literal(c) for c in READABLE[table]) + ')'
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and(has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'SELECT')=(" + allowed + ") AND NOT has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'INSERT,UPDATE,REFERENCES') AND NOT has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'SELECT,INSERT,UPDATE,REFERENCES') AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL) "
                   "AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                   "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')" + where, 'acl') != 't':
            raise SystemExit('F21 fixture lacks exact notes-four/mailboxes-five/writer-zero/no-sequence privileges')
        if catalog('SELECT ' + ' AND '.join(constraint_checks(table) + index_checks(table)), 'constraints') != 't':
            raise SystemExit('F21 fixture differs from the original validated PK/FK/CHECK/index definitions: ' + table)
    return owner


def database_snapshot():
    previous = f20.database_snapshot()
    if len(previous) != 14: raise SystemExit('F21 requires the complete prior fourteen-table snapshot')
    for schema, table in TABLES:
        if table in previous: raise SystemExit('F21 snapshot table key collides with a previous stage')
        order = 'note_id' if table == 'private_notes' else 'member_id,note_id'
        previous[table] = json.loads(owned_sql("SELECT COALESCE(json_agg(r ORDER BY " + order + "),'[]'::json) FROM " + schema + '.' + table + ' r'))
    if len(previous) != 16: raise SystemExit('F21 requires all sixteen business tables')
    return previous


def prepare():
    f2.require_hosted()
    owner = f20.require_fixture()
    check_receipt(EVIDENCE / 'f20-fixture-reuse.txt', f20_proof(owner))
    ddl, source_proof = verified_ddl()
    proof = fixture_proof(owner, source_proof)
    target = EVIDENCE / 'f21-fixture-reuse.txt'
    check_receipt(target, proof, allow_missing=True)
    before = f20.database_snapshot()
    if len(before) != 14: raise SystemExit('F21 prior snapshot is incomplete')
    if catalog("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='pawbridge_community' "
               "AND c.relname IN ('private_notes','private_note_mailboxes')", 'absence') != '0':
        raise SystemExit('F21 will not replace or claim existing note tables')
    # All input/source/owner/receipt/absence checks finish before the first DB/filesystem write.
    owned_sql(input_text=ddl)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    target.write_text(proof)
    require_fixture()
    after = database_snapshot()
    if any(after[table] != rows for table, rows in before.items()) or any(after[table] for _, table in TABLES):
        raise SystemExit('F21 fixture extension changed existing rows or was not initially empty')
    print('Added only pinned original note/mailbox tables and nine reader columns after F20')


@contextmanager
def denied_select(relation, column):
    if (relation, column) not in (('pawbridge_community.private_notes', 'expires_at'), ('pawbridge_user.users', 'nickname')):
        raise ValueError('F21 denial is limited to note expiry or User nickname SELECT')
    owner = require_fixture()
    owned_sql('REVOKE SELECT(' + column + ') ON ' + relation + ' FROM pawbridge_f2_reader')
    try:
        yield
    finally:
        # Never invoke the User-seven/extended strict ACL chain while its grant is missing.
        f19.require_identity(owner)
        check_receipt(EVIDENCE / 'f20-fixture-reuse.txt', f20_proof(owner))
        require_f21_receipt(owner)
        owned_sql('GRANT SELECT(' + column + ') ON ' + relation + ' TO pawbridge_f2_reader')
        require_fixture()


def prepare_business_rows():
    require_fixture()
    values = ','.join(map(str, MEMBERS))
    quoted_notes = ','.join(f4.literal(note) + '::uuid' for note in ALL_NOTES)
    if owned_sql('SELECT (SELECT count(*) FROM pawbridge_user.users WHERE user_id IN (' + values + ')) + '
                 '(SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id IN (' + values + ')) + '
                 '(SELECT count(*) FROM pawbridge_community.private_notes WHERE note_id IN (' + quoted_notes + ')) + '
                 '(SELECT count(*) FROM pawbridge_community.private_note_mailboxes WHERE member_id IN (' + values + '))') != '0':
        raise SystemExit('F21 synthetic User/member/note/mailbox keys must all be absent')
    users = ','.join('(' + str(identity) + ',' + f4.literal(NAMES[identity]) + ',' + f4.literal('f21-' + str(identity) + '@fixture.invalid')
                     + ",'F21 synthetic','GOOGLE','ROLE_USER')" for identity in USERS)
    note_rows, mailbox_rows = [], []
    for index, note in enumerate(NOTE_IDS, 1):
        sender = (NORMAL, RAW, PENDING, MISSING, None)[(index - 1) % 5]
        note_rows.append((note, sender, CALLER, EXPIRES))
        mailbox_rows.append((CALLER, note, 'INBOX', STORED_TIME if index % 3 == 0 else None))
    note_rows.extend(((FOREIGN, NORMAL, OTHER, EXPIRES), (SENT, CALLER, OTHER, EXPIRES),
                      (EXPIRED, NORMAL, CALLER, '1970-01-01T00:00:00Z')))
    mailbox_rows.extend(((OTHER, FOREIGN, 'INBOX', None), (CALLER, SENT, 'SENT', None), (CALLER, EXPIRED, 'INBOX', None)))
    literal_id = lambda identity: 'NULL' if identity is None else str(identity)
    notes = ','.join('(' + f4.literal(note) + '::uuid,' + literal_id(sender) + ',' + str(recipient) + ',' + f4.literal(BODY)
                     + ',' + f4.literal(STORED_TIME) + '::timestamptz,' + f4.literal(expiry) + '::timestamptz)'
                     for note, sender, recipient, expiry in note_rows)
    mailboxes = ','.join('(' + str(member) + ',' + f4.literal(note) + '::uuid,' + f4.literal(direction) + ','
                         + ('NULL' if read is None else f4.literal(read) + '::timestamptz') + ',false,'
                         + f4.literal(STORED_TIME) + '::timestamptz)' for member, note, direction, read in mailbox_rows)
    owned_sql('BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role) VALUES' + users + '; '
              'INSERT INTO pawbridge_community.private_note_members(member_id) VALUES' + ','.join('(' + str(i) + ')' for i in MEMBERS) + '; '
              'INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES(' + str(PENDING) + '); '
              'INSERT INTO pawbridge_community.private_notes(note_id,sender_id,recipient_id,body,created_at,expires_at) VALUES' + notes + '; '
              'INSERT INTO pawbridge_community.private_note_mailboxes(member_id,note_id,direction,read_at,favorite,created_at) VALUES' + mailboxes + '; COMMIT;')
    return OWNED


def cleanup_business_rows(owned):
    f2.require_hosted()
    if owned != OWNED: raise ValueError('F21 cleanup requires only the successfully inserted exact keys')
    pairs = ','.join('(' + str(member) + ',' + f4.literal(note) + '::uuid)' for member, note in MAILBOXES)
    queries = ('DELETE FROM pawbridge_community.private_note_mailboxes WHERE (member_id,note_id) IN (' + pairs + ')',
               'DELETE FROM pawbridge_community.private_notes WHERE body=' + f4.literal(BODY) + ' AND note_id IN ('
               + ','.join(f4.literal(note) + '::uuid' for note in ALL_NOTES) + ')',
               'DELETE FROM pawbridge_community.private_note_members WHERE member_id IN (' + ','.join(map(str, MEMBERS)) + ')',
               'DELETE FROM pawbridge_user.contact_deletions WHERE user_id=' + str(PENDING),
               'DELETE FROM pawbridge_user.users WHERE (user_id,email) IN (' + ','.join('(' + str(i) + ','
               + f4.literal('f21-' + str(i) + '@fixture.invalid') + ')' for i in USERS) + ')')
    for query in queries:
        owner = require_owner()
        require_f21_receipt(owner)
        owned_sql(query)


def load_tokens():
    owner = f2.require_hosted()
    path = Path(os.environ['F3_TEST_TOKENS_PATH'])
    expected = Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    if path.is_symlink() or path.resolve() != expected: raise SystemExit('F21 token path is not owned by this run')
    try:
        mode = path.stat().st_mode
        if not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600: raise SystemExit('F21 token file must remain regular 0600')
        tokens = json.loads(path.read_text())
        parts = tokens['F21_CALLER'].split('.')
        claims = json.loads(base64.urlsafe_b64decode(parts[1] + '=' * (-len(parts[1]) % 4)))
        valid = (len(parts) == 3 and type(claims['userId']) is int and claims['userId'] == CALLER
                 and claims['role'] == 'ROLE_USER' and type(claims['exp']) in (int, float)
                 and math.isfinite(claims['exp']) and claims['exp'] >= time.time() + 60)
    except (OSError, AttributeError, KeyError, IndexError, ValueError, TypeError):
        raise SystemExit('F21 used token identity or freshness is invalid') from None
    if not valid: raise SystemExit('F21 used token identity, role or final freshness differs')
    return {'F21_CALLER': tokens['F21_CALLER']}


def require_measurements(kind):
    f2.require_hosted()
    if kind == 'jar':
        if (not isinstance(EXPECTED_JAR_COUNTS, dict) or set(EXPECTED_JAR_COUNTS) != set(COUNTERS)
                or any(type(n) is not int or n < 0 for n in EXPECTED_JAR_COUNTS.values())):
            raise SystemExit('F21 packaged request counts are unmeasured')
    else:
        raise ValueError('Unknown F21 measurement gate')


def expected_notifications(cursor=None):
    if cursor is None: indexes = list(range(23, 3, -1))
    elif cursor == NOTE_IDS[3]: indexes = [3, 2, 1]
    else: indexes = []
    content = []
    for index in indexes:
        sender = (NORMAL, RAW, PENDING, MISSING, None)[(index - 1) % 5]
        active = sender in (NORMAL, RAW)
        note = NOTE_IDS[index - 1]
        content.append({'noteId': note, 'kind': 'PRIVATE_NOTE', 'actorId': sender if active else None,
                        'actorNickname': NAMES[sender] if active else WITHDRAWN, 'createdAt': STORED_TIME,
                        'read': index % 3 == 0, 'href': '/notes/' + note})
    return {'content': content, 'nextCursor': NOTE_IDS[3] if cursor is None else None, 'unreadCount': 16}


def smoke():
    require_measurements('jar')
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file(): raise SystemExit('F21 packaged JAR missing')
    tokens = load_tokens()
    for value in tokens.values(): print('::add-mask::' + value)
    bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
    controls = {'X-F3-Owner': owner}
    base_env = {k: os.environ[k] for k in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR') if k in os.environ}
    app_env = dict(base_env, PAWBRIDGE_DB_URL=os.environ['F2_TEST_JDBC_URL'], PAWBRIDGE_DB_USER=f3.APP,
        PAWBRIDGE_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'], PAWBRIDGE_DB_POOL_MAX_SIZE='4',
        PAWBRIDGE_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
        PAWBRIDGE_STORAGE_BUCKET=bucket, PAWBRIDGE_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', PAWBRIDGE_STORAGE_OBJECT_PREFIX=prefix,
        PAWBRIDGE_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret', PAWBRIDGE_STORAGE_REGION='us-east-1')
    result = dict.fromkeys(COUNTERS, 0)
    result.update(status='incomplete', timezone='Asia/Seoul', pool_maximum=4,
                  database_changes=0, s3_requests=0)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f21-packaged-startup.log', EVIDENCE / 'f21-s3-stub.log'
    baseline = database_snapshot()
    base = endpoint = ''

    def snapshot():
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database_snapshot(), storage['counts'], storage['objects'], len(storage['requests'])

    def check(cursor=None, status=200, token='F21_CALLER', message=SUCCESS, layer='business', kind=None, method='GET'):
        headers = {'X-User-Id': str(OTHER), 'X-User-Role': 'ROLE_ADMIN'}
        path = PATH + ('?cursor=' + urllib.parse.quote(cursor) if cursor is not None else '')
        before = snapshot()
        if token is not None: headers['Authorization'] = 'Bearer ' + load_tokens()[token]
        actual, raw, received = f3.request(base, path, method=method, headers=headers)
        if snapshot() != before: raise AssertionError('F21 request changed any business row or contacted S3')
        if (actual != status or received.get('Set-Cookie')
                or received.get('Cache-Control') != ('no-store' if layer == 'business' else f19.CACHE_CONTROL)):
            raise AssertionError('F21 request lost status/cache/cookie boundary')
        expected = ({'code': 200, 'message': SUCCESS, 'data': expected_notifications(cursor)} if status == 200 else
                    {'message': message} if layer == 'business' else {'code': status, 'message': message, 'data': None})
        response = json.loads(raw)
        if response != expected: raise AssertionError('F21 lost its exact original notification/cursor/unread/error contract')
        if status == 200 and (type(response['data']['unreadCount']) is not int
                or any(type(item['read']) is not bool or (item['actorId'] is not None and type(item['actorId']) is not int)
                       for item in response['data']['content'])):
            raise AssertionError('F21 numeric/boolean JSON values differ from the original types')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') "
                     "AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('F21 request left an active DB transaction')
        if method == 'GET':
            result['notifications_get_checks'] += 1
            result['successful_checks' if status == 200 else 'rejected_checks'] += 1
            if status == 200:
                if cursor is not None: result['cursor_checks'] += 1
                if not response['data']['content']: result['empty_checks'] += 1
        if kind: result[kind + '_checks'] += 1

    owned = ()
    try:
        with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
            app = stub = None
            app_offset = 0
            try:
                app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                    jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
                result['startup_attempts'] = attempts
                if database_snapshot() != baseline: raise AssertionError('F21 startup changed business rows')
                state = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
                if any(state['counts'].values()) or state['requests'] or state['objects']:
                    raise AssertionError('F21 startup contacted S3')
                owned = prepare_business_rows()
                check(); check(cursor=NOTE_IDS[3]); check(cursor=FOREIGN)
                with denied_select('pawbridge_community.private_notes', 'expires_at'):
                    check(status=503, message=STORAGE_UNAVAILABLE, kind='storage_unavailable')
                check()
                with denied_select('pawbridge_user.users', 'nickname'):
                    check(status=503, message=MEMBER_UNAVAILABLE, kind='member_unavailable')
                check()
                check(status=401, token=None, message='인증 토큰이 필요합니다.', layer='auth', kind='authentication')
                check(status=403, message='접근할 수 없습니다.', layer='route', kind='denied_route', method='POST')
                require_fixture()
            finally:
                try:
                    if app is not None: result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F21 packaged application')
                finally:
                    try:
                        if stub is not None: result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F21 S3 stub')
                    finally:
                        if owned: cleanup_business_rows(owned)
        if database_snapshot() != baseline: raise AssertionError('F21 owned seed cleanup did not restore all sixteen tables')
        if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
            raise AssertionError('F21 exceeded the one-MiB-per-child log budget')
        text = f4.fresh_log(app_path, app_offset)
        if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
            raise AssertionError('F21 normal HTTP/single-pool shutdown evidence is missing')
        forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                     BODY, 'F21 synthetic', *(name for name in NAMES.values() if name.strip()), *tokens.values(),
                     *('f21-' + str(i) + '@fixture.invalid' for i in USERS))
        if any(value in text for value in forbidden): raise AssertionError('F21 log exposed credentials, tokens or synthetic private data')
        if owned_sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
            raise AssertionError('F21 application connections remain after shutdown')
        if {key: result[key] for key in COUNTERS} != EXPECTED_JAR_COUNTS:
            raise AssertionError('F21 packaged request counts differ from the reviewed measured contract')
        result.update(status='passed', shutdown_pools=1, remaining_application_connections=0)
    finally:
        (EVIDENCE / 'f21-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F21 notification/cursor/unread journey and unchanged sixteen-table/S3 snapshots passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'smoke': smoke}[sys.argv[1]]()
