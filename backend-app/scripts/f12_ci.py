#!/usr/bin/env python3
"""Hosted-only authenticated profile reads after the original F3-F11 fixture regressions."""
import json
import os
import pathlib
import sys
import xml.etree.ElementTree as ET

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f11_ci as f11

ROOT = f3.ROOT
EVIDENCE = f3.EVIDENCE
MARKER = 'pawbridge-f12-ci-only-v1'
COMPATIBILITY_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlUserProfileCompatibilityTest'
FIELDS = {'userId', 'email', 'name', 'nickname', 'provider', 'role', 'createdAt'}
OTHER = 9980001
PRIVATE_COLUMNS = ('password', 'care_reg_no', 'provider_id', 'updated_at')
PRIVATE_VALUES = ('F12_SYNTHETIC_PASSWORD_DO_NOT_EXPOSE', 'F12_SYNTHETIC_CARE_DO_NOT_EXPOSE',
                  'F12_SYNTHETIC_PROVIDER_ID_DO_NOT_EXPOSE')
IDENTITIES = "SELECT COALESCE(json_agg(json_build_array(user_id,nickname) ORDER BY user_id),'[]'::json) FROM pawbridge_user.users"


def verify_results():
    f2.require_hosted()
    counts = {}
    for task, zone in (('profileCompatibilityTest', 'UTC'),
                       ('profileCompatibilitySeoulTest', 'Asia/Seoul')):
        path = ROOT / 'boot/build/test-results' / task / ('TEST-' + COMPATIBILITY_CLASS + '.xml')
        try:
            suite = ET.parse(path).getroot()
            values = {key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
        except (OSError, ET.ParseError, KeyError, ValueError):
            raise SystemExit('F12 compatibility report missing or malformed: ' + task)
        cases = suite.findall('testcase')
        if (suite.tag != 'testsuite' or suite.attrib.get('name') != COMPATIBILITY_CLASS
                or values['tests'] <= 0 or any(values[key] != 0 for key in ('failures', 'errors', 'skipped'))
                or len(cases) != values['tests']
                or any(case.attrib.get('classname') != COMPATIBILITY_CLASS for case in cases)):
            raise SystemExit('F12 compatibility report must contain executed, successful, unskipped tests: ' + task)
        counts[zone] = values['tests']
    print('Verified separate F12 compatibility execution counts: ' + json.dumps(counts, sort_keys=True))
    return counts


def require_fixture():
    # Extra reader columns do not change F11's exact comment-writer ACL.
    # Its full target/port/owner/marker checks remain valid without running any ancestor smoke.
    owner = f11.require_fixture()
    if os.environ.get('F12_CI_OWNER') != owner:
        raise SystemExit('F12 fixture owner mismatch')
    if f3.sql("SELECT value || ':' || owner FROM pawbridge_f12_fixture.marker") != MARKER + ':' + owner:
        raise SystemExit('F12 fixture identity mismatch')
    if f3.sql("SELECT count(*)=11 AND bool_and((column_name,data_type,"
            "COALESCE(character_maximum_length,0),is_nullable,COALESCE(datetime_precision,0)) IN ("
            "('user_id','bigint',0,'NO',0),('nickname','character varying',30,'NO',0),"
            "('email','character varying',255,'NO',0),('name','character varying',20,'NO',0),"
            "('provider','character varying',20,'NO',0),('role','character varying',20,'NO',0),"
            "('created_at','timestamp without time zone',0,'YES',6),"
            "('password','character varying',255,'YES',0),('care_reg_no','character varying',50,'YES',0),"
            "('provider_id','character varying',100,'YES',0),('updated_at','timestamp without time zone',0,'YES',6))) "
            "FROM information_schema.columns WHERE table_schema='pawbridge_user' AND table_name='users'") != 't':
        raise SystemExit('F12 fixture lacks the exact eleven-column User shape')
    if f3.sql("SELECT count(*)=11 AND bool_and("
            "has_column_privilege('pawbridge_f2_reader','pawbridge_user.users',column_name,'SELECT') "
            "= (column_name IN ('user_id','email','name','nickname','provider','role','created_at')) "
            "AND NOT has_column_privilege('pawbridge_f2_reader','pawbridge_user.users',column_name,'INSERT,UPDATE,REFERENCES') "
            "AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_user.users',column_name,'SELECT,INSERT,UPDATE,REFERENCES')) "
            "AND NOT has_table_privilege('pawbridge_f2_reader','pawbridge_user.users','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
            "AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_user.users','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
            "AND NOT has_schema_privilege('pawbridge_f2_reader','pawbridge_user','CREATE') "
            "AND NOT has_schema_privilege('pawbridge_f3_writer','pawbridge_user','CREATE') "
            "FROM information_schema.columns WHERE table_schema='pawbridge_user' AND table_name='users'") != 't':
        raise SystemExit('F12 fixture must have exactly seven reader columns and no User writer privileges')
    # Ignore only PostgreSQL's display casts/spacing, not operators or allowed values.
    if f3.sql("SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_user.users'::regclass "
            "AND conname='ck_f12_users_role' AND contype='c' AND convalidated "
            "AND conkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid='pawbridge_user.users'::regclass AND attname='role')] "
            "AND regexp_replace(pg_get_expr(conbin,conrelid),'::text|::character varying|[()[:space:]]','','g') IN ("
            "'role=ANYARRAY[''ROLE_ADMIN'',''ROLE_SHELTER'',''ROLE_USER'']',"
            "'role=ANYARRAY[''ROLE_ADMIN'',''ROLE_SHELTER'',''ROLE_USER''][]')") != 't':
        raise SystemExit('F12 fixture lacks the validated exact three-role constraint')
    return owner


def prepare():
    owner = f11.require_fixture()
    f2.export_environment({'F12_CI_OWNER': owner})
    before = f3.sql(IDENTITIES)
    f3.sql(input_text=(ROOT / 'scripts/f12_fixture.sql').read_text())
    if f3.sql(IDENTITIES) != before:
        raise SystemExit('F12 fixture extension changed existing User IDs or nicknames')
    f3.sql('GRANT SELECT(email,name,provider,role,created_at) ON pawbridge_user.users TO pawbridge_f2_reader')
    require_fixture()
    print('Extended only the owned User fixture; retained identities and seven SELECT-only reader columns')


def prepare_business_rows():
    require_fixture()
    values = []
    for user_id, nickname in ((42, 'F12_SYNTHETIC_NICK'), (OTHER, 'F12_OTHER_SYNTHETIC_NICK')):
        values.append('(' + str(user_id) + ',' + f4.literal(nickname) + ','
                      + f4.literal('f12-synthetic-' + str(user_id) + '@fixture.invalid')
                      + ",'F12_SYNTHETIC_NAME','LOCAL','ROLE_SHELTER','2026-10-19 16:00:00.123456',"
                      + ','.join(f4.literal(value) for value in PRIVATE_VALUES)
                      + ",'1997-02-03 04:05:06.654321')")
    f3.sql('INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role,created_at,'
           'password,care_reg_no,provider_id,updated_at) VALUES' + ','.join(values)
           + ' ON CONFLICT(user_id) DO UPDATE SET nickname=EXCLUDED.nickname,email=EXCLUDED.email,'
           'name=EXCLUDED.name,provider=EXCLUDED.provider,role=EXCLUDED.role,created_at=EXCLUDED.created_at,'
           'password=EXCLUDED.password,care_reg_no=EXCLUDED.care_reg_no,provider_id=EXCLUDED.provider_id,updated_at=EXCLUDED.updated_at')


def smoke():
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():
        raise SystemExit('Packaged JAR missing')
    tokens = json.loads(pathlib.Path(os.environ['F3_TEST_TOKENS_PATH']).read_text())
    if 'F12_OTHER' not in tokens:
        raise SystemExit('F12 requires the real legacy issuer second-principal token')
    for value in tokens.values():
        print('::add-mask::' + value)
    bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
    controls = {'X-F3-Owner': owner}
    base_env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR')
                if key in os.environ}
    app_env = dict(base_env, F2_JDBC_URL=os.environ['F2_TEST_JDBC_URL'],
        F2_DB_USER=os.environ['F2_TEST_DB_USER'], F2_DB_PASSWORD=os.environ['F2_TEST_DB_PASSWORD'],
        F2_POOL_MAX_SIZE='1', F3_DB_WRITER_USER=f3.WRITER,
        F3_DB_WRITER_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'],
        F3_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'], F3_STORAGE_BUCKET=bucket,
        F3_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', F3_STORAGE_OBJECT_PREFIX=prefix,
        F3_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', F3_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret',
        F3_STORAGE_REGION='us-east-1')
    result = {'status': 'incomplete', 'timezone': 'Asia/Seoul',
              'reader_pool_maximum': 1, 'writer_pool_maximum': 1,
              'profile_get_checks': 0, 'successful_profile_checks': 0, 'rejected_profile_checks': 0,
              'denied_route_checks': 0, 'database_changes': 0, 's3_requests': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f12-packaged-startup.log', EVIDENCE / 'f12-s3-stub.log'
    base = endpoint = ''

    def snapshot():
        database = json.loads(f3.sql("SELECT json_build_object("
            "'users',(SELECT COALESCE(json_agg(u ORDER BY user_id),'[]'::json) FROM pawbridge_user.users u),"
            "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]'::json) FROM pawbridge_community.posts p),"
            "'comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]'::json) FROM pawbridge_community.comments c),"
            "'f4comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]'::json) FROM pawbridge_f4_fixture.comments c),"
            "'notes',(SELECT COALESCE(json_agg(n ORDER BY note_id),'[]'::json) FROM pawbridge_f4_fixture.private_notes n),"
            "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]'::json) FROM pawbridge_community.outbox_events e),"
            "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]'::json) FROM pawbridge_community.post_search_documents s))"))
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database, storage['counts'], len(storage['requests'])

    def returned():
        if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN "
                "('pawbridge_f2_reader','pawbridge_f3_writer') AND "
                "(state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('F12 request left a DB transaction active')

    def profile(token='ROLE_USER', expected=200, message=None, user_id=42,
                path='/api/v1/users/me', body=None):
        headers = {'X-User-Id': str(OTHER), 'X-User-Email': 'spoofed@fixture.invalid',
                   'X-User-Name': 'spoofed', 'X-User-Role': 'ROLE_ADMIN'}
        if token is not None:
            headers['Authorization'] = 'Bearer ' + tokens[token]
        before = snapshot()
        response = f3.expect(base, path, expected, headers=headers, body=body)
        if snapshot() != before:
            raise AssertionError('Profile GET changed DB or contacted S3')
        returned()
        result['profile_get_checks'] += 1
        if expected != 200:
            if response != {'code': expected, 'message': message, 'data': None}:
                raise AssertionError('Profile error lost its safe fixed whole response')
            result['rejected_profile_checks'] += 1
            return
        if (response['code'] != 200 or response['message'] != '요청이 성공적으로 처리되었습니다.'
                or set(response['data']) != FIELDS):
            raise AssertionError('Profile GET lost its exact seven-field response')
        row = next(row for row in before[0]['users'] if row['user_id'] == user_id)
        item = response['data']
        expected_fields = {'userId': row['user_id'], 'email': row['email'], 'name': row['name'],
                           'nickname': row['nickname'], 'provider': row['provider'], 'role': row['role']}
        if {key: value for key, value in item.items() if key != 'createdAt'} != expected_fields:
            raise AssertionError('Profile must use principal identity and current DB values')
        if row['created_at'] is None:
            if item['createdAt'] is not None:
                raise AssertionError('Nullable profile creation time must remain an explicit JSON null')
        else:
            f11.require_seoul_get_timestamp(item['createdAt'], row['created_at'])
        serialized = json.dumps(response, ensure_ascii=False)
        if any(value in serialized for value in PRIVATE_VALUES):
            raise AssertionError('Private synthetic User columns entered the profile response')
        result['successful_profile_checks'] += 1

    with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
        app = stub = None
        app_offset = 0
        try:
            app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
            result['startup_attempts'] = attempts
            prepare_business_rows()
            for token, message in ((None, '인증 토큰이 필요합니다.'),
                                   ('expired', '유효하지 않거나 만료된 토큰입니다.'),
                                   ('forged', '유효하지 않거나 만료된 토큰입니다.')):
                profile(token, 401, message)
            for token in ('refresh', 'missingId', 'zeroId', 'negativeId', 'stringId', 'missingRole', 'numericRole'):
                profile(token, 401, '토큰 파싱에 실패했습니다.')
            for role in ('ROLE_USER', 'ROLE_ADMIN', 'ROLE_SHELTER'):
                profile(role)
            profile(path='/api/v1/users/me?userId=' + str(OTHER), body=b'{"userId":9980001}')
            profile('F12_OTHER', user_id=OTHER)
            profile()
            for nickname in ('', '  ', 'F12_SYNTHETIC_NICK'):
                f3.sql('UPDATE pawbridge_user.users SET nickname=' + f4.literal(nickname) + ' WHERE user_id=42')
                profile()
            for role in ('ROLE_USER', 'ROLE_ADMIN', 'ROLE_SHELTER'):
                f3.sql('UPDATE pawbridge_user.users SET role=' + f4.literal(role) + ",provider='GOOGLE' WHERE user_id=42")
                profile('ROLE_USER')
            f3.sql('UPDATE pawbridge_user.users SET created_at=NULL WHERE user_id=42')
            profile()
            f3.sql("UPDATE pawbridge_user.users SET created_at='2026-10-19 23:59:59.654321' WHERE user_id=42")
            profile()
            # Temporary deletion affects only this run-owned second synthetic principal.
            f3.sql('DELETE FROM pawbridge_user.users WHERE user_id=' + str(OTHER))
            profile('F12_OTHER', 400, '존재하지 않는 회원입니다.')
            profile()
            for column in ('email', 'created_at'):
                f3.sql('REVOKE SELECT(' + column + ') ON pawbridge_user.users FROM pawbridge_f2_reader')
                try:
                    profile(expected=500, message='서버 에러!')
                    profile(None, 401, '인증 토큰이 필요합니다.')
                finally:
                    f3.sql('GRANT SELECT(' + column + ') ON pawbridge_user.users TO pawbridge_f2_reader')
                profile()
            for method, path in (('POST', '/api/v1/users/me'), ('PUT', '/api/v1/users/me'),
                                 ('DELETE', '/api/v1/users/me'), ('PATCH', '/api/v1/users/me'),
                                 ('HEAD', '/api/v1/users/me'), ('OPTIONS', '/api/v1/users/me'),
                                 ('GET', '/api/v1/users/me/'), ('GET', '/api/v1/users/me/extra'),
                                 ('GET', '/api/users/me'), ('GET', '/api/v1/users/internal/42/nickname')):
                before = snapshot()
                f3.expect(base, path, 403, method, {'Authorization': 'Bearer ' + tokens['ROLE_USER']})
                if snapshot() != before:
                    raise AssertionError('Denied profile route changed DB or contacted S3')
                returned()
                result['denied_route_checks'] += 1
            result['principal_fields_null_nickname_roles_missing_permissions_and_recovery'] = 'passed'
        finally:
            try:
                if app is not None:
                    result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F12 packaged application')
            finally:
                if stub is not None:
                    result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F12 S3 stub')
                (EVIDENCE / 'f12-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
        raise AssertionError('F12 bounded journey exceeded its one-MiB-per-child log budget')
    text = f4.fresh_log(app_path, app_offset)
    if text.count('Shutdown completed.') < 2 or 'Graceful shutdown complete' not in text:
        raise AssertionError('F12 normal HTTP/two-pool shutdown evidence missing')
    forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'],
                 os.environ['F3_TEST_JWT_SECRET'], 'F12_SYNTHETIC_NAME', 'F12_SYNTHETIC_NICK',
                 'f12-synthetic-42@fixture.invalid', '1997-02-03 04:05:06.654321',
                 '1997-02-03T04:05:06.654321', *PRIVATE_VALUES, *tokens.values())
    if any(value in text for value in forbidden):
        raise AssertionError('F12 application log exposed credentials, tokens or synthetic personal fields')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN "
            "('pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('F12 application connections remain after normal shutdown')
    result.update(status='passed', shutdown_pools=2, remaining_application_connections=0)
    (EVIDENCE / 'f12-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F12 actual-JAR authenticated profile reads, strict projection, recovery and shutdown passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'results': verify_results, 'smoke': smoke}[sys.argv[1]]()
