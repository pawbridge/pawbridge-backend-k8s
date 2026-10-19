#!/usr/bin/env python3
"""F26 hosted fixture/XML prefix. Runtime smoke is owned and appended separately.

No local PostgreSQL, original extraction, HTTP or JVM is started by preparation.
PG/MVC counts and the measured Seoul method deliberately remain fail-closed.
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
import f25_ci as f25
import prepare_f26_legacy_oracle as oracle

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
SOURCE = 'f06a72a06bd26662c647bde780dfc7a27bf7349c'
SOURCE_FILES = 20
STREAM_SOURCE = 'community-service/src/main/java/com/pawbridge/communityservice/contact/PrivateNoteStream.java'
STREAM_SHA256 = '3215dd7b9f3958049ea3065bc550883838bfeacc46d75e350cbc29f26a9504eb'
TASK = 'noteReadStreamCompatibilityTest'
SEOUL_TASK = 'noteReadStreamCompatibilitySeoulTest'
PG_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlNoteReadStreamCompatibilityTest'
MVC_CLASS = 'com.pawbridge.backend.compatibility.F26LegacyNoteReadStreamMvcTest'
# Original-only observer12 and formal MVC20 actually passed. PG19/Seoul1 are reviewed exact test expectations, not execution claims.
REPORTS = {TASK: {PG_CLASS: 19, MVC_CLASS: 20}, SEOUL_TASK: {PG_CLASS: 1}}
SEOUL_METHOD = 'givenMicrosecondReadTstzAndThreeClockSamples__whenReadingAndSerializingInEitherZone__thenKeepTheFirstInstantWithoutZoneShift'
COUNTERS = ('request_checks', 'nonstreaming_checks', 'put_checks', 'successful_put_checks', 'rejected_put_checks', 'get_checks', 'notification_checks', 'detail_checks', 'list_checks', 'stream_open_checks', 'stream_rejected_checks', 'stream_ready_checks', 'resync_checks', 'other_resync_checks', 'heartbeat_checks', 'short_expiry_checks', 'storage_unavailable_checks', 'member_unavailable_checks', 'recovery_checks', 'authentication_checks', 'adjacent_denied_checks', 'approved_database_write_checks')
EXPECTED_JAR_COUNTS = {'request_checks': 22, 'nonstreaming_checks': 19, 'put_checks': 7, 'successful_put_checks': 5, 'rejected_put_checks': 2, 'get_checks': 15, 'notification_checks': 3, 'detail_checks': 2, 'list_checks': 3, 'stream_open_checks': 3, 'stream_rejected_checks': 3, 'stream_ready_checks': 3, 'resync_checks': 5, 'other_resync_checks': 0, 'heartbeat_checks': 1, 'short_expiry_checks': 1, 'storage_unavailable_checks': 1, 'member_unavailable_checks': 1, 'recovery_checks': 2, 'authentication_checks': 3, 'adjacent_denied_checks': 1, 'approved_database_write_checks': 2}
NOTES = 'pawbridge_community.private_notes'
MAILBOXES = 'pawbridge_community.private_note_mailboxes'


def owned_sql(query='', *, input_text=None):
    f2.require_hosted()
    try:
        return f3.sql(query, input_text=input_text) if input_text is not None else f3.sql(query)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        raise SystemExit('F26 owned fixture SQL failed without exposing command, SQL or output') from None


def fixture_proof(owner):
    return ('owner=' + owner + '\nsource_sha=' + SOURCE + '\n'
            'fixture=F25 plus own-note read writer\n'
            'new_business_ddl=0\nnew_schema=0\nnew_marker=0\n'
            'writer_columns=members:SELECT,INSERT,UPDATE(member_id);'
            'blocks:SELECT(member_id,blocked_id),DELETE,INSERT(member_id,blocked_id,created_at);'
            'notes:SELECT(note_id,expires_at),UPDATE(note_id);'
            'mailboxes:SELECT(member_id,note_id,direction,read_at),UPDATE(read_at)\n'
            'new_sequence_acl=0\n')


def require_prior_receipts(owner):
    # Marker identity only during intentional User/writer denial, never an old strict ACL check.
    f2.require_hosted()
    f25.require_prior_receipts(owner)
    f21.check_receipt(EVIDENCE / 'f25-fixture-write.txt', f25.fixture_proof(owner))


def require_owner():
    f2.require_hosted()
    owner = f25.require_owner()  # User-seven/old identities remain valid after notes writer grants.
    f21.check_receipt(EVIDENCE / 'f26-fixture-write.txt', fixture_proof(owner))
    return owner


def verified_sources():
    """Read fixed Git blobs, not working-tree legacy paths; produce no generated file."""
    f2.require_hosted()
    if (oracle.SOURCE != SOURCE or len(oracle.EXPECTED) != SOURCE_FILES
            or len(oracle.SHARED) != 12 or len(oracle.OUTPUTS) != 4
            or oracle.EXPECTED.get(STREAM_SOURCE) != STREAM_SHA256):
        raise SystemExit('F26 fixed twenty-source closure or original Stream pin differs')
    inputs = {}
    for path, expected in oracle.EXPECTED.items():
        try:
            raw = subprocess.check_output(['git', 'show', SOURCE + ':' + path], cwd=ROOT.parent,
                                          stderr=subprocess.PIPE)
        except (OSError, subprocess.CalledProcessError):
            raise SystemExit('F26 fixed original source object is unavailable') from None
        if hashlib.sha256(raw).hexdigest() != expected:
            raise SystemExit('F26 fixed original source checksum differs')
        inputs[path] = raw
    return inputs


def catalog(query, stage):
    if stage not in ('shape', 'acl', 'constraints') or not query.startswith('SELECT '):
        raise ValueError('F26 catalog is restricted to static read-only metadata')
    return owned_sql(query)


def require_fixture():
    owner = require_owner()
    # Explicit F25 pending/member/block contract; no old writer-zero checker or monkeypatch.
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
            raise SystemExit('F26 inherited pending/member/block shape or owner differs')
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
            raise SystemExit('F26 inherited pending/member/block exact ACL differs')
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
            raise SystemExit('F26 inherited pending/member/block PK/FK/CHECK/index differs')
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
            raise SystemExit('F26 notes/mailboxes shape, defaults or owner differs')
        reader = 'column_name IN (' + ','.join(f4.literal(c) for c in f22.READABLE[table]) + ')'
        writer_read = "column_name IN ('note_id','expires_at')" if table == 'private_notes' else "column_name IN ('member_id','note_id','direction','read_at')"
        writer_update = "column_name='note_id'" if table == 'private_notes' else "column_name='read_at'"
        if catalog('SELECT count(*)=' + str(size) + " AND bool_and(has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'SELECT')=(" + reader + ") AND NOT has_column_privilege('pawbridge_f2_reader','" + relation
                   + "',column_name,'INSERT,UPDATE,REFERENCES') AND has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'SELECT')=(" + writer_read + ") AND has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'UPDATE')=(" + writer_update + ") AND NOT has_column_privilege('pawbridge_f3_writer','" + relation
                   + "',column_name,'INSERT,REFERENCES') AND pg_get_serial_sequence('" + relation + "',column_name) IS NULL) "
                   "AND NOT has_table_privilege('pawbridge_f2_reader','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                   "AND NOT has_table_privilege('pawbridge_f3_writer','" + relation + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')" + where, 'acl') != 't':
            raise SystemExit('F26 notes-two/mailboxes-four SELECT and exact one-column UPDATE ACL differs')
        if catalog('SELECT ' + ' AND '.join(f21.constraint_checks(table) + f21.index_checks(table)), 'constraints') != 't':
            raise SystemExit('F26 notes/mailboxes validated PK/FK/CHECK/index differs')
    return owner


def database_snapshot():
    f2.require_hosted()
    value = f25.database_snapshot()
    if not isinstance(value, dict) or len(value) != 16:
        raise SystemExit('F26 requires all sixteen inherited business tables')
    return value


def prepare():
    f2.require_hosted()
    owner = f25.require_fixture()  # The last old notes/mailboxes writer-zero strict check.
    target = EVIDENCE / 'f26-fixture-write.txt'
    proof = fixture_proof(owner)
    f21.check_receipt(target, proof, allow_missing=True)
    if target.exists():
        raise SystemExit('F26 refuses an existing writer extension receipt')
    verified_sources()  # No extractor receipt exists yet; only immutable input reads are required here.
    before = database_snapshot()
    owned_sql(input_text='BEGIN;\n'
        'GRANT SELECT(note_id,expires_at),UPDATE(note_id) ON ' + NOTES + ' TO pawbridge_f3_writer;\n'
        'GRANT SELECT(member_id,note_id,direction,read_at),UPDATE(read_at) ON ' + MAILBOXES + ' TO pawbridge_f3_writer;\n'
        'COMMIT;\n')
    with target.open('x') as output:
        output.write(proof)
    require_fixture()
    if database_snapshot() != before:
        raise SystemExit('F26 permission extension changed existing business rows')
    print('Extended only the planned notes/mailboxes writer columns after the complete F25 stage')


@contextmanager
def denied_privilege(kind):
    clauses = {'read': ('UPDATE(read_at) ON ' + MAILBOXES, 'pawbridge_f3_writer'),
               'nickname': ('SELECT(nickname) ON pawbridge_user.users', 'pawbridge_f2_reader')}
    if kind not in clauses:
        raise ValueError('Only the planned F26 read_at or User nickname denial is permitted')
    owner = require_fixture()
    clause, role = clauses[kind]
    owned_sql('REVOKE ' + clause + ' FROM ' + role)
    try:
        yield
    finally:
        require_prior_receipts(owner)
        f21.check_receipt(EVIDENCE / 'f26-fixture-write.txt', fixture_proof(owner))
        owned_sql('GRANT ' + clause + ' TO ' + role)
        require_fixture()


def require_measurements(kind='results'):
    f2.require_hosted()
    if kind not in ('results', 'jar'):
        raise ValueError('F26 measurement gate is limited to results or jar')
    if (not isinstance(REPORTS, dict) or set(REPORTS) != {TASK, SEOUL_TASK}
            or not isinstance(REPORTS.get(TASK), dict) or set(REPORTS[TASK]) != {PG_CLASS, MVC_CLASS}
            or REPORTS.get(SEOUL_TASK) != {PG_CLASS: 1}
            or any(type(n) is not int or n <= 0 for classes in REPORTS.values() for n in classes.values())
            or not isinstance(SEOUL_METHOD, str) or not re.fullmatch(r'given[A-Za-z0-9_]+', SEOUL_METHOD)):
        raise SystemExit('F26 original providers, PG/MVC counts or Seoul method remain unmeasured')
    if kind == 'jar' and (not COUNTERS or not isinstance(EXPECTED_JAR_COUNTS, dict)
            or set(EXPECTED_JAR_COUNTS) != set(COUNTERS)
            or any(type(n) is not int or n < 0 for n in EXPECTED_JAR_COUNTS.values())):
        raise SystemExit('F26 packaged journey counts remain unmeasured')


def _plain_file(path):
    if path.is_symlink() or path.resolve() != path or not path.is_file():
        raise SystemExit('F26 oracle evidence or generated path is missing or redirected')
    return path.read_bytes()


def require_oracle_receipt():
    """Match source/shared/generated identities; selected method/LF proofs remain extractor-owned."""
    f2.require_hosted()
    if (oracle.SOURCE != SOURCE or len(oracle.EXPECTED) != SOURCE_FILES
            or len(oracle.SHARED) != 12 or len(oracle.OUTPUTS) != 4
            or oracle.EXPECTED.get(STREAM_SOURCE) != STREAM_SHA256):
        raise SystemExit('F26 oracle manifest differs from the fixed twenty-source closure')
    try:
        lines = _plain_file(ROOT.parent / oracle.EVIDENCE_PATH).decode('utf-8').splitlines()
        if not lines or lines[0] != 'source_sha=' + SOURCE:
            raise SystemExit('F26 oracle evidence source identity differs')
        categories = {}
        for line in lines[1:]:
            match = re.fullmatch(r'([0-9a-f]{64})  (source|shared-byte-identity|shared-adapted-identity|generated) (.+)', line)
            if match:
                digest, category, name = match.groups()
                bucket = categories.setdefault(category, {})
                if name in bucket:
                    raise SystemExit('F26 oracle evidence repeats an identity entry')
                bucket[name] = digest
        if categories.get('source') != oracle.EXPECTED:
            raise SystemExit('F26 oracle evidence lacks the exact twenty full source hashes')
        shared = {relative: oracle.EXPECTED[source] for source, relative in oracle.SHARED.items()}
        if categories.get('shared-byte-identity') != shared:
            raise SystemExit('F26 oracle evidence shared source identities differ')
        for source, relative in oracle.SHARED.items():
            if hashlib.sha256(_plain_file(ROOT.parent / relative)).hexdigest() != oracle.EXPECTED[source]:
                raise SystemExit('F26 shared generated bytes differ from their fixed original')
        adapted = {oracle.F18_ADAPTED_PATH: oracle.F18_ADAPTED_SHA256}
        if (categories.get('shared-adapted-identity') != adapted
                or hashlib.sha256(_plain_file(ROOT.parent / oracle.F18_ADAPTED_PATH)).hexdigest() != oracle.F18_ADAPTED_SHA256):
            raise SystemExit('F26 existing adapted advice identity differs')
        generated = categories.get('generated', {})
        if set(generated) != set(oracle.OUTPUTS):
            raise SystemExit('F26 oracle evidence generated output set differs')
        for relative in oracle.OUTPUTS:
            if hashlib.sha256(_plain_file(ROOT.parent / oracle.OUTPUT_ROOT / relative)).hexdigest() != generated[relative]:
                raise SystemExit('F26 original wrapper bytes differ from extractor evidence')
    except (OSError, UnicodeError, KeyError, TypeError):
        raise SystemExit('F26 original oracle identity evidence is missing or malformed') from None


def verify_results():
    require_measurements()
    require_owner()
    require_oracle_receipt()
    result = {}
    try:
        for task, classes in REPORTS.items():
            directory = ROOT / 'boot/build/test-results' / task
            if {p.name for p in directory.glob('TEST-*.xml')} != {'TEST-' + name + '.xml' for name in classes}:
                raise SystemExit('F26 exact UTC/Seoul XML class set differs')
            result[task] = {}
            for name, expected in classes.items():
                target = directory / ('TEST-' + name + '.xml')
                suite = ET.fromstring(_plain_file(target))
                cases = suite.findall('testcase')
                if suite.tag != 'testsuite' or suite.get('name') != name:
                    raise SystemExit('F26 XML suite FQN differs')
                values = {k: int(suite.attrib[k]) for k in ('tests', 'failures', 'errors', 'skipped')}
                if (values != {'tests': expected, 'failures': 0, 'errors': 0, 'skipped': 0}
                        or len(cases) != expected or len(list(suite.iter('testcase'))) != expected
                        or int(suite.get('disabled', '0')) != 0):
                    raise SystemExit('F26 XML aggregate or actual testcase counts differ')
                if any(suite.find('.//' + tag) is not None for tag in ('failure', 'error', 'skipped')):
                    raise SystemExit('F26 XML contains a hidden unsuccessful result')
                if any(case.get('classname') != name for case in cases):
                    raise SystemExit('F26 XML actual testcase FQN differs')
                if task == SEOUL_TASK and cases[0].get('name') not in (SEOUL_METHOD, SEOUL_METHOD + '()'):
                    raise SystemExit('F26 Seoul must contain only its exact measured timestamp method')
                result[task][name] = values
    except (OSError, ET.ParseError, KeyError, ValueError):
        raise SystemExit('F26 compatibility XML is missing or malformed') from None
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / 'f26-results.json').write_text(json.dumps(result, sort_keys=True) + '\n')
    print('Verified measured F26 UTC PG/MVC and one exact Seoul timestamp XML')


# Root appends runtime seed/token/SSE smoke and the final CLI after this fixture/XML prefix.


def smoke():
    f2.require_hosted()
    import sys
    from f26_stream_probe import run
    return run(sys.modules[__name__])


if __name__ == "__main__":
    import sys
    f2.require_hosted()
    {"prepare": prepare, "results": verify_results, "smoke": smoke}[sys.argv[1]]()
