#!/usr/bin/env python3
"""Hosted-only favorite status reads after the original F3-F13 staged regressions."""
import base64
import hashlib
import json
import os
import pathlib
import re
import stat
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f13_ci as f13

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
MARKER = 'pawbridge-f14-ci-only-v1'
COMPATIBILITY_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlFavoriteStatusCompatibilityTest'
TASK = 'favoriteStatusCompatibilityTest'
SOURCE = '1458169254f71e34148711b7caf72ec75c35e5f2'
DDL_SOURCE = 'user-service/src/migration/resources/db/postgresql/V1__user_schema.sql'
DDL_HASH = '5b6c50062231952e625e56a7577c41b03d7d5a10041380d4b2ada960ef7449a1'
OTHER, FAVORITE, ANIMAL = 9980001, 9961001, 9961002
USERS = "SELECT COALESCE(json_agg(u ORDER BY user_id),'[]'::json) FROM pawbridge_user.users u"


def verify_results():
    f2.require_hosted()
    path = ROOT / 'boot/build/test-results' / TASK / ('TEST-' + COMPATIBILITY_CLASS + '.xml')
    try:
        suite = ET.parse(path).getroot()
        values = {key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
    except (OSError, ET.ParseError, KeyError, ValueError):
        raise SystemExit('F14 compatibility report missing or malformed')
    cases = suite.findall('testcase')
    if (suite.tag != 'testsuite' or suite.attrib.get('name') != COMPATIBILITY_CLASS
            or values['tests'] <= 0 or any(values[key] != 0 for key in ('failures', 'errors', 'skipped'))
            or len(cases) != values['tests']
            or any(case.attrib.get('classname') != COMPATIBILITY_CLASS for case in cases)):
        raise SystemExit('F14 compatibility report must contain executed, successful, unskipped tests')
    print('Verified F14 UTC compatibility execution count: ' + str(values['tests']))
    return {'UTC': values['tests']}


def verified_ddl():
    f2.require_hosted()
    raw = subprocess.check_output(['git', 'show', SOURCE + ':' + DDL_SOURCE], cwd=ROOT.parent)
    if hashlib.sha256(raw).hexdigest() != DDL_HASH:
        raise SystemExit('F14 original migration checksum mismatch')
    original = raw.decode('utf-8').split('CREATE TABLE favorites (', 1)[1].split('CREATE TABLE outbox_events', 1)[0]
    expected = ('CREATE TABLE favorites (' + original).strip()
    expected = expected.replace('CREATE TABLE favorites', 'CREATE TABLE pawbridge_user.favorites')
    expected = expected.replace('REFERENCES users(', 'REFERENCES pawbridge_user.users(')
    expected = expected.replace('ON favorites ', 'ON pawbridge_user.favorites ')
    ddl = (ROOT / 'scripts/f14_fixture.sql').read_text()
    if expected not in ddl:
        raise SystemExit('F14 fixture differs from pinned favorites PK/identity/UK/FK/index DDL')
    return ddl, 'source_sha=' + SOURCE + '\n' + DDL_HASH + '  ' + DDL_SOURCE + '\n'


def fixture_acl(query):
    """Only the static favorites catalog ACL query gets this bounded diagnostic."""
    try:
        return f3.sql(query)
    except subprocess.CalledProcessError as failure:
        stderr = failure.stderr if isinstance(failure.stderr, str) else ''
        primary = next((line.strip() for line in stderr.splitlines()
                        if re.match(r'^\s*(?:ERROR|FATAL):', line)), '(no PostgreSQL primary error captured)')
        for key, value in os.environ.items():
            if value and any(label in key.upper() for label in ('PASSWORD', 'SECRET', 'TOKEN')):
                primary = primary.replace(value, '[redacted]')
        primary = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', primary)
        primary = ''.join(character for character in primary if character.isprintable())[:512]
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        (EVIDENCE / 'f14-fixture-acl-error.txt').write_text(
            'stage=f14-favorites-catalog-acl\nreturncode=' + str(failure.returncode) + '\nstderr=' + primary + '\n')
        raise SystemExit('F14 fixture ACL query failed; see bounded f14-fixture-acl-error.txt') from None


def require_fixture():
    # F14 adds a separate table and reader columns; F13's User and writer checks stay valid.
    owner = f13.require_fixture()
    if os.environ.get('F14_CI_OWNER') != owner:
        raise SystemExit('F14 fixture owner mismatch')
    if f3.sql("SELECT value || ':' || owner FROM pawbridge_f14_fixture.marker") != MARKER + ':' + owner:
        raise SystemExit('F14 fixture identity mismatch')
    if f3.sql("SELECT count(*)=4 AND bool_and((column_name,data_type,is_nullable,COALESCE(datetime_precision,0),is_identity) IN ("
            "('favorite_id','bigint','NO',0,'YES'),('animal_id','bigint','NO',0,'NO'),"
            "('created_at','timestamp without time zone','NO',6,'NO'),('user_id','bigint','NO',0,'NO'))) "
            "AND bool_and(column_name<>'favorite_id' OR identity_generation='BY DEFAULT') "
            "FROM information_schema.columns WHERE table_schema='pawbridge_user' AND table_name='favorites'") != 't':
        raise SystemExit('F14 fixture lacks the exact four-column favorites identity shape')
    if fixture_acl("SELECT count(*)=4 AND bool_and("
            "has_column_privilege('pawbridge_f2_reader','pawbridge_user.favorites',column_name,'SELECT') "
            "= (column_name IN ('user_id','animal_id')) "
            "AND NOT has_column_privilege('pawbridge_f2_reader','pawbridge_user.favorites',column_name,'INSERT,UPDATE,REFERENCES') "
            "AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_user.favorites',column_name,'SELECT,INSERT,UPDATE,REFERENCES')) "
            "AND NOT has_table_privilege('pawbridge_f2_reader','pawbridge_user.favorites','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
            "AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_user.favorites','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
            "AND NOT has_sequence_privilege('pawbridge_f2_reader',pg_get_serial_sequence('pawbridge_user.favorites','favorite_id'),'USAGE,SELECT,UPDATE') "
            "AND NOT has_sequence_privilege('pawbridge_f3_writer',pg_get_serial_sequence('pawbridge_user.favorites','favorite_id'),'USAGE,SELECT,UPDATE') "
            "FROM information_schema.columns WHERE table_schema='pawbridge_user' AND table_name='favorites'") != 't':
        raise SystemExit('F14 fixture lacks the exact favorites reader-two/writer-zero/sequence-zero ACL')
    if f3.sql("SELECT (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_user.favorites'::regclass "
            "AND contype='p' AND convalidated AND conkey=ARRAY[(SELECT attnum FROM pg_attribute "
            "WHERE attrelid='pawbridge_user.favorites'::regclass AND attname='favorite_id')]) "
            "AND (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_user.favorites'::regclass "
            "AND conname='uk_user_animal' AND contype='u' AND convalidated AND conkey=ARRAY["
            "(SELECT attnum FROM pg_attribute WHERE attrelid='pawbridge_user.favorites'::regclass AND attname='user_id'),"
            "(SELECT attnum FROM pg_attribute WHERE attrelid='pawbridge_user.favorites'::regclass AND attname='animal_id')]) "
            "AND (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_user.favorites'::regclass "
            "AND conname='fk_favorites_user' AND contype='f' AND convalidated AND NOT condeferrable "
            "AND confrelid='pawbridge_user.users'::regclass AND confdeltype='r' AND confupdtype='a' AND confmatchtype='s' "
            "AND conkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid='pawbridge_user.favorites'::regclass AND attname='user_id')] "
            "AND confkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid='pawbridge_user.users'::regclass AND attname='user_id')]) "
            "AND (SELECT count(*)=1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_am a ON a.oid=c.relam "
            "WHERE i.indrelid='pawbridge_user.favorites'::regclass AND c.relname='idx_favorite_animal_id' "
            "AND i.indisvalid AND i.indisready AND NOT i.indisunique AND i.indpred IS NULL AND i.indexprs IS NULL "
            "AND i.indnatts=1 AND a.amname='btree' AND i.indkey[0]=(SELECT attnum FROM pg_attribute "
            "WHERE attrelid='pawbridge_user.favorites'::regclass AND attname='animal_id'))") != 't':
        raise SystemExit('F14 fixture lacks the exact favorites PK/UK/RESTRICT FK/index')
    return owner


def prepare():
    f2.require_hosted()
    owner = f13.require_fixture()
    ddl, evidence = verified_ddl()
    f2.export_environment({'F14_CI_OWNER': owner})
    before = f3.sql(USERS)
    f3.sql(input_text=ddl)
    if f3.sql(USERS) != before:
        raise SystemExit('F14 fixture extension changed existing User rows')
    require_fixture()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / 'f14-ddl-sha256.txt').write_text(evidence)
    print('Added only pinned favorites schema and exact two-column reader SELECT after F13')


def prepare_business_rows():
    require_fixture()
    if f3.sql('SELECT count(*) FROM pawbridge_user.users WHERE user_id=42') != '1':
        raise SystemExit('F14 requires the preserved original HTTP principal')
    f3.sql("BEGIN; INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role,created_at,updated_at) "
           "VALUES(" + str(OTHER) + ",'F14타인','f14-other@fixture.invalid','F14 합성회원','GOOGLE','ROLE_USER',"
           "'2025-01-01 01:02:03.123456','2025-01-02 01:02:03.123456'); "
           "INSERT INTO pawbridge_user.favorites(favorite_id,user_id,animal_id,created_at) VALUES("
           + str(FAVORITE) + ',42,' + str(ANIMAL) + ",'2025-01-01 01:02:03.123456'); COMMIT;")


def cleanup_business_rows():
    require_fixture()
    f3.sql('DELETE FROM pawbridge_user.favorites WHERE favorite_id=' + str(FAVORITE)
           + ' AND user_id=42 AND animal_id=' + str(ANIMAL) + '; DELETE FROM pawbridge_user.users WHERE user_id='
           + str(OTHER) + " AND email='f14-other@fixture.invalid'")


def load_tokens():
    owner = f2.require_hosted()
    path = pathlib.Path(os.environ['F3_TEST_TOKENS_PATH'])
    expected = pathlib.Path(os.environ['RUNNER_TEMP']).resolve() / ('f3-tokens-' + owner + '.json')
    if path.resolve() != expected or path.is_symlink():
        raise SystemExit('F14 token file must belong to this hosted run without a symlink')
    try:
        mode = path.stat().st_mode
        if not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
            raise SystemExit('F14 synthetic token file must remain a regular 0600 file')
        tokens = json.loads(path.read_text())
    except (OSError, ValueError):
        raise SystemExit('F14 synthetic token file is missing or malformed') from None
    if not isinstance(tokens, dict):
        raise SystemExit('F14 synthetic token file must contain the original token map')
    if not {'ROLE_USER', 'F14_OTHER', 'expired'}.issubset(tokens):
        raise SystemExit('F14 requires refreshed real-issuer principal and expired tokens')
    now = time.time()
    for key, identity in (('ROLE_USER', 42), ('F14_OTHER', OTHER), ('expired', 42)):
        try:
            raw = tokens[key].split('.')[1]
            claims = json.loads(base64.urlsafe_b64decode(raw + '=' * (-len(raw) % 4)))
            valid = (type(claims['userId']) is int and claims['userId'] == identity
                     and claims['role'] == 'ROLE_USER'
                     and (claims['exp'] < now if key == 'expired' else claims['exp'] > now + 60))
        except (KeyError, IndexError, ValueError, TypeError):
            valid = False
        if not valid:
            raise SystemExit('F14 synthetic token identity or final freshness differs from the fixture contract')
    return tokens


def smoke():
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():
        raise SystemExit('Packaged JAR missing')
    tokens = load_tokens()
    for value in tokens.values(): print('::add-mask::' + value)
    bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
    controls = {'X-F3-Owner': owner}
    base_env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR') if key in os.environ}
    app_env = dict(base_env, PAWBRIDGE_DB_URL=os.environ['F2_TEST_JDBC_URL'], PAWBRIDGE_DB_USER=f3.APP,
        PAWBRIDGE_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'], PAWBRIDGE_DB_POOL_MAX_SIZE='4',
        PAWBRIDGE_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
        PAWBRIDGE_STORAGE_BUCKET=bucket, PAWBRIDGE_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', PAWBRIDGE_STORAGE_OBJECT_PREFIX=prefix,
        PAWBRIDGE_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret', PAWBRIDGE_STORAGE_REGION='us-east-1')
    result = {'status': 'incomplete', 'timezone': 'Asia/Seoul', 'pool_maximum': 4,
              'favorite_get_checks': 0, 'true_checks': 0, 'false_checks': 0, 'rejected_get_checks': 0,
              'database_changes': 0, 's3_requests': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f14-packaged-startup.log', EVIDENCE / 'f14-s3-stub.log'
    base = endpoint = ''

    def snapshot():
        database = json.loads(f3.sql("SELECT json_build_object("
            "'users',(SELECT COALESCE(json_agg(u ORDER BY user_id),'[]'::json) FROM pawbridge_user.users u),"
            "'favorites',(SELECT COALESCE(json_agg(f ORDER BY favorite_id),'[]'::json) FROM pawbridge_user.favorites f),"
            "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]'::json) FROM pawbridge_community.posts p),"
            "'comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]'::json) FROM pawbridge_community.comments c),"
            "'f4comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]'::json) FROM pawbridge_f4_fixture.comments c),"
            "'notes',(SELECT COALESCE(json_agg(n ORDER BY note_id),'[]'::json) FROM pawbridge_f4_fixture.private_notes n),"
            "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]'::json) FROM pawbridge_community.outbox_events e),"
            "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]'::json) FROM pawbridge_community.post_search_documents s))"))
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database, storage['counts'], len(storage['requests'])

    def returned():
        if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') "
                  "AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('F14 request left a DB transaction active')

    def check(animal=ANIMAL, token='ROLE_USER', value=True, status=200, message='요청이 성공적으로 처리되었습니다.'):
        headers = {'X-User-Id': str(OTHER), 'X-User-Role': 'ROLE_ADMIN', 'Content-Type': 'application/json'}
        if token is not None: headers['Authorization'] = 'Bearer ' + tokens[token]
        before = snapshot()
        response = f3.expect(base, '/api/v1/favorites/' + str(animal) + '/check?userId=' + str(OTHER)
            + '&animalId=' + str(ANIMAL + 1), status, headers=headers,
            body=json.dumps({'userId': OTHER, 'animalId': ANIMAL + 1}).encode())
        if snapshot() != before:
            raise AssertionError('Favorite GET changed a business row or contacted S3')
        expected = {'code': status, 'message': message, 'data': value if status == 200 else None}
        if response != expected or (status == 200 and type(response['data']) is not bool):
            raise AssertionError('Favorite GET lost its exact Boolean or safe error envelope')
        result['favorite_get_checks'] += 1
        if status != 200: result['rejected_get_checks'] += 1
        else: result['true_checks' if value else 'false_checks'] += 1
        returned()

    with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
        app = stub = None
        app_offset, seeded = 0, False
        try:
            app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
            result['startup_attempts'] = attempts
            prepare_business_rows(); seeded = True
            check(); check(ANIMAL + 1, value=False); check(token='F14_OTHER', value=False)
            f3.sql('DELETE FROM pawbridge_user.users WHERE user_id=' + str(OTHER) + " AND email='f14-other@fixture.invalid'")
            check(token='F14_OTHER', value=False)
            check(token=None, status=401, message='인증 토큰이 필요합니다.')
            check(token='expired', status=401, message='유효하지 않거나 만료된 토큰입니다.')
            check('not-a-number', status=500, message='서버 에러!')
            for column in ('user_id', 'animal_id'):
                f3.sql('REVOKE SELECT(' + column + ') ON pawbridge_user.favorites FROM pawbridge_f2_reader')
                try: check(status=500, message='서버 에러!')
                finally: f3.sql('GRANT SELECT(' + column + ') ON pawbridge_user.favorites TO pawbridge_f2_reader')
                check()
            require_fixture()
        finally:
            try:
                if app is not None: result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F14 packaged application')
            finally:
                try:
                    if stub is not None: result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F14 S3 stub')
                finally:
                    try:
                        if seeded: cleanup_business_rows()
                    finally:
                        (EVIDENCE / 'f14-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
        raise AssertionError('F14 bounded journey exceeded its one-MiB-per-child log budget')
    text = f4.fresh_log(app_path, app_offset)
    if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
        raise AssertionError('F14 normal HTTP/single-pool shutdown evidence missing')
    forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                 'F14타인', 'F14 합성회원', 'f14-other@fixture.invalid', *tokens.values())
    if any(value in text for value in forbidden):
        raise AssertionError('F14 application log exposed credentials, tokens or synthetic personal fields')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('F14 application connections remain after normal shutdown')
    result.update(status='passed', shutdown_pools=1, remaining_application_connections=0)
    (EVIDENCE / 'f14-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F14 Boolean GET, identity, missing-user false, permission recovery and shutdown passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'results': verify_results, 'smoke': smoke}[sys.argv[1]]()
