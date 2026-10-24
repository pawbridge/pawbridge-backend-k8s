#!/usr/bin/env python3
"""Hosted-only nickname PUT after the original F3-F12 staged regressions."""
import base64
import copy
import hashlib
import json
import os
import pathlib
import re
import stat
import subprocess
import sys
import time

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f11_ci as f11
import f12_ci as f12

ROOT, EVIDENCE = f3.ROOT, f3.EVIDENCE
MARKER = 'pawbridge-f13-ci-only-v1'
SOURCE = 'c32ae0d84b69969abd35c541dbdd4e81738c4be5'
DDL_PREFIX = 'user-service/src/migration/resources/db/postgresql/'
DDL_HASHES = {'V1__user_schema.sql': '5b6c50062231952e625e56a7577c41b03d7d5a10041380d4b2ada960ef7449a1',
              'V2__rollback_charset_compatibility.sql': '2921e2a7b10c640ef24955902e38d649e5485af7df8656b10638ccf5f79521b8'}
OTHER, POST, COMMENT = 9980001, 9981001, 9981002
PATH = '/api/v1/users/me/nickname'


def verified_ddl():
    """Compare fixture clauses to hash-pinned real V1/V2, without executing migrations."""
    f2.require_hosted()
    inputs, entries = {}, []
    for name, expected in DDL_HASHES.items():
        raw = subprocess.check_output(['git', 'show', SOURCE + ':' + DDL_PREFIX + name], cwd=ROOT.parent)
        digest = hashlib.sha256(raw).hexdigest()
        if digest != expected:
            raise SystemExit('F13 original migration checksum mismatch: ' + name)
        inputs[name] = raw.decode('utf-8')
        entries.append(digest + '  ' + DDL_PREFIX + name)
    ddl = (ROOT / 'scripts/f13_fixture.sql').read_text()
    charset = inputs['V2__rollback_charset_compatibility.sql'].split('ALTER TABLE users ADD CONSTRAINT ck_rollback_charset_users', 1)[1]
    charset = 'ALTER TABLE pawbridge_user.users ADD CONSTRAINT ck_rollback_charset_users' + charset
    if (charset.strip() not in ddl or 'UNIQUE (nickname)' not in inputs['V1__user_schema.sql']
            or 'ADD CONSTRAINT users_nickname_key UNIQUE (nickname);' not in ddl
            or 'CREATE UNIQUE INDEX uk_users_nickname_folded ON users (lower(nickname));' not in inputs['V1__user_schema.sql']
            or 'CREATE UNIQUE INDEX uk_users_nickname_folded ON pawbridge_user.users (lower(nickname));' not in ddl):
        raise SystemExit('F13 fixture differs from pinned User uniqueness or eight-column charset DDL')
    return ddl, 'source_sha=' + SOURCE + '\n' + '\n'.join(entries) + '\n'


def fixture_acl(query):
    """Only the static catalog ACL query gets a bounded primary-error diagnostic."""
    try:
        return f3.sql(query)
    except subprocess.CalledProcessError as failure:
        stderr = failure.stderr if isinstance(failure.stderr, str) else ''
        primary = next((line.strip() for line in stderr.splitlines()
                        if re.match(r'^\s*(?:ERROR|FATAL):', line)), '(no PostgreSQL primary error captured)')
        # This catalog-only query never reads business values. Still redact any configured
        # credentials before bounding the line, and exclude SQL/DETAIL/CONTEXT/stdout/cmd.
        for key, value in os.environ.items():
            if value and any(label in key.upper() for label in ('PASSWORD', 'SECRET', 'TOKEN')):
                primary = primary.replace(value, '[redacted]')
        primary = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', primary)
        primary = ''.join(character for character in primary if character.isprintable())[:512]
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        (EVIDENCE / 'f13-fixture-acl-error.txt').write_text(
            'stage=f13-user-catalog-acl\nreturncode=' + str(failure.returncode) + '\nstderr=' + primary + '\n')
        raise SystemExit('F13 fixture ACL query failed; see bounded f13-fixture-acl-error.txt') from None


def require_fixture():
    # F12's no-User-writer assertion remains strict; never call it after this extension.
    owner = f11.require_fixture()
    for stage, marker in ((12, f12.MARKER), (13, MARKER)):
        if os.environ.get('F' + str(stage) + '_CI_OWNER') != owner:
            raise SystemExit('F' + str(stage) + ' fixture owner mismatch')
        if f3.sql("SELECT value || ':' || owner FROM pawbridge_f" + str(stage) + '_fixture.marker') != marker + ':' + owner:
            raise SystemExit('F' + str(stage) + ' fixture identity mismatch')
    if f3.sql("SELECT count(*)=11 AND bool_and((column_name,data_type,COALESCE(character_maximum_length,0),"
            "is_nullable,COALESCE(datetime_precision,0)) IN (('user_id','bigint',0,'NO',0),"
            "('nickname','character varying',30,'NO',0),('email','character varying',255,'NO',0),"
            "('name','character varying',20,'NO',0),('provider','character varying',20,'NO',0),"
            "('role','character varying',20,'NO',0),('created_at','timestamp without time zone',0,'YES',6),"
            "('password','character varying',255,'YES',0),('care_reg_no','character varying',50,'YES',0),"
            "('provider_id','character varying',100,'YES',0),('updated_at','timestamp without time zone',0,'YES',6))) "
            "FROM information_schema.columns WHERE table_schema='pawbridge_user' AND table_name='users'") != 't':
        raise SystemExit('F13 fixture lacks the exact eleven-column User shape')
    if fixture_acl("SELECT count(*)=11 AND bool_and("
            "has_column_privilege('pawbridge_f2_reader','pawbridge_user.users',column_name,'SELECT') "
            "= (column_name IN ('user_id','email','name','nickname','provider','role','created_at')) "
            "AND NOT has_column_privilege('pawbridge_f2_reader','pawbridge_user.users',column_name,'INSERT,UPDATE,REFERENCES') "
            "AND has_column_privilege('pawbridge_f3_writer','pawbridge_user.users',column_name,'SELECT') "
            "= (column_name IN ('user_id','nickname')) "
            "AND has_column_privilege('pawbridge_f3_writer','pawbridge_user.users',column_name,'UPDATE') "
            "= (column_name IN ('nickname','updated_at')) "
            "AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_user.users',column_name,'INSERT,REFERENCES')) "
            "AND NOT has_table_privilege('pawbridge_f2_reader','pawbridge_user.users','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
            "AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_user.users','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
            "AND has_schema_privilege('pawbridge_f3_writer','pawbridge_user','USAGE') "
            "AND NOT has_schema_privilege('pawbridge_f2_reader','pawbridge_user','CREATE') "
            "AND NOT has_schema_privilege('pawbridge_f3_writer','pawbridge_user','CREATE') "
            "AND NOT EXISTS(SELECT 1 FROM pg_class s JOIN pg_namespace n ON n.oid=s.relnamespace "
            "WHERE n.nspname='pawbridge_user' AND CASE WHEN s.relkind='S' "
            "THEN has_sequence_privilege('pawbridge_f3_writer',s.oid,'USAGE,SELECT,UPDATE') ELSE false END) "
            "FROM information_schema.columns WHERE table_schema='pawbridge_user' AND table_name='users'") != 't':
        raise SystemExit('F13 fixture lacks the exact reader-seven/writer-two-SELECT/two-UPDATE ACL')
    if f3.sql("SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_user.users'::regclass "
            "AND conname='ck_f12_users_role' AND contype='c' AND convalidated "
            "AND conkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid='pawbridge_user.users'::regclass AND attname='role')] "
            "AND regexp_replace(pg_get_expr(conbin,conrelid),'::text|::character varying|[()[:space:]]','','g') IN ("
            "'role=ANYARRAY[''ROLE_ADMIN'',''ROLE_SHELTER'',''ROLE_USER'']',"
            "'role=ANYARRAY[''ROLE_ADMIN'',''ROLE_SHELTER'',''ROLE_USER''][]')") != 't':
        raise SystemExit('F13 fixture lost the F12 three-role constraint')
    if f3.sql("SELECT (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_user.users'::regclass "
            "AND conname='users_nickname_key' AND contype='u' AND convalidated AND conkey=ARRAY["
            "(SELECT attnum FROM pg_attribute WHERE attrelid='pawbridge_user.users'::regclass AND attname='nickname')]) "
            "AND (SELECT count(*)=1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid "
            "WHERE i.indrelid='pawbridge_user.users'::regclass AND c.relname='uk_users_nickname_folded' "
            "AND i.indisunique AND i.indisvalid AND i.indisready AND i.indpred IS NULL AND i.indnatts=1 "
            "AND regexp_replace(pg_get_expr(i.indexprs,i.indrelid),'::text|[()[:space:]]','','g')='lowernickname') "
            "AND (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_user.users'::regclass "
            "AND conname='ck_rollback_charset_users' AND contype='c' AND convalidated "
            "AND cardinality(conkey)=8 AND pg_get_constraintdef(oid)="
            "(SELECT charset_definition FROM pawbridge_f13_fixture.marker))") != 't':
        raise SystemExit('F13 fixture lacks the pinned validated User unique/index/charset constraints')
    return owner


def prepare():
    f2.require_hosted()
    owner = f12.require_fixture()
    ddl, evidence = verified_ddl()
    f2.export_environment({'F13_CI_OWNER': owner})
    before = f3.sql(f12.IDENTITIES)
    f3.sql(input_text=ddl)
    if f3.sql(f12.IDENTITIES) != before:
        raise SystemExit('F13 fixture extension changed existing User IDs or nicknames')
    require_fixture()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / 'f13-ddl-sha256.txt').write_text(evidence)
    print('Added only pinned User nickname constraints and exact writer column permissions after F12')


def prepare_business_rows():
    require_fixture()
    # F12's existing principal, nickname and private columns are preserved until the real PUT.
    if f3.sql('SELECT count(*) FROM pawbridge_user.users WHERE user_id=42') != '1':
        raise SystemExit('F13 requires the preserved original HTTP principal')
    f3.sql("INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role,created_at,updated_at) "
           "VALUES(" + str(OTHER) + ",'F13타인','f13-other@fixture.invalid','F13 합성회원','GOOGLE','ROLE_USER',"
           "'2025-01-01 01:02:03.123456','2025-01-02 01:02:03.123456'); "
           "INSERT INTO pawbridge_community.posts(post_id,author_id,title,content,board_type,image_urls,created_at,updated_at) "
           "VALUES(" + str(POST) + ",42,'F13 합성게시글','F13 합성본문','COMMUNICATION','[]',"
           "'2025-01-01 01:02:03.123456','2025-01-02 01:02:03.123456'); "
           "INSERT INTO pawbridge_community.comments(comment_id,post_id,author_id,content,created_at,updated_at) "
           "VALUES(" + str(COMMENT) + ',' + str(POST) + ",42,'F13 합성댓글','2025-01-01 01:02:03.123456','2025-01-02 01:02:03.123456')")


def load_tokens():
    path = pathlib.Path(os.environ['F3_TEST_TOKENS_PATH'])
    mode = path.stat().st_mode
    if not stat.S_ISREG(mode) or stat.S_IMODE(mode) != 0o600:
        raise SystemExit('F13 synthetic token file must remain a regular 0600 file')
    tokens = json.loads(path.read_text())
    required = {'ROLE_USER', 'ROLE_ADMIN', 'ROLE_SHELTER', 'F13_OTHER', 'expired', 'forged', 'refresh',
                'missingId', 'zeroId', 'negativeId', 'stringId', 'missingRole', 'numericRole'}
    if not required.issubset(tokens):
        raise SystemExit('F13 requires refreshed real-issuer role, second-principal and rejection tokens')
    for key in sorted(required - {'expired', 'forged'}):
        try:
            raw = tokens[key].split('.')[1]
            claims = json.loads(base64.urlsafe_b64decode(raw + '=' * (-len(raw) % 4)))
            valid = claims['exp'] > time.time() + 60
        except (KeyError, IndexError, ValueError, TypeError):
            valid = False
        if not valid:
            raise SystemExit('F13 synthetic tokens must be refreshed before the packaged journey')
    return tokens


def smoke():
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():
        raise SystemExit('Packaged JAR missing')
    tokens = load_tokens()
    for value in tokens.values():
        print('::add-mask::' + value)
    bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
    controls = {'X-F3-Owner': owner}
    base_env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR') if key in os.environ}
    app_env = dict(base_env, PAWBRIDGE_DB_URL=os.environ['F2_TEST_JDBC_URL'], PAWBRIDGE_DB_USER=f3.APP,
        PAWBRIDGE_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'], PAWBRIDGE_DB_POOL_MAX_SIZE='4',
        PAWBRIDGE_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'],
        PAWBRIDGE_STORAGE_BUCKET=bucket, PAWBRIDGE_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', PAWBRIDGE_STORAGE_OBJECT_PREFIX=prefix,
        PAWBRIDGE_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret', PAWBRIDGE_STORAGE_REGION='us-east-1')
    result = {'status': 'incomplete', 'timezone': 'Asia/Seoul', 'pool_maximum': 4,
              'nickname_put_checks': 0, 'changed_put_checks': 0, 'same_put_checks': 0, 'rejected_put_checks': 0,
              'profile_get_checks': 0, 'community_get_checks': 0, 'unrelated_database_changes': 0, 's3_requests': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f13-packaged-startup.log', EVIDENCE / 'f13-s3-stub.log'
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
        if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') "
                  "AND (state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('F13 request left a DB transaction active')

    def put(nickname='F13변경', expected=200, message='닉네임이 변경되었습니다.', token='ROLE_USER', raw=None):
        before = snapshot()
        headers = {'Content-Type': 'application/json', 'X-User-Id': str(OTHER), 'X-User-Role': 'ROLE_ADMIN'}
        if token is not None:
            headers['Authorization'] = 'Bearer ' + tokens[token]
        response = f3.expect(base, PATH + '?userId=' + str(OTHER), expected, 'PUT', headers,
                             raw if raw is not None else json.dumps({'nickname': nickname, 'userId': OTHER}).encode())
        after = snapshot()
        result['nickname_put_checks'] += 1
        if expected == 200:
            if response != {'code': 200, 'message': message, 'data': None}:
                raise AssertionError('Nickname PUT lost its exact success envelope')
            previous = next(row for row in before[0]['users'] if row['user_id'] == 42)
            saved = next(row for row in after[0]['users'] if row['user_id'] == 42)
            expected_state = copy.deepcopy(before)
            target = next(row for row in expected_state[0]['users'] if row['user_id'] == 42)
            if previous['nickname'] == nickname:
                result['same_put_checks'] += 1
            else:
                if saved['updated_at'] is None or saved['updated_at'] == previous['updated_at']:
                    raise AssertionError('Changed nickname did not advance stored auditing time')
                target.update(nickname=nickname, updated_at=saved['updated_at'])
                result['changed_put_checks'] += 1
            if after != expected_state:
                raise AssertionError('Nickname PUT changed fields beyond this users nickname/updated_at or contacted S3')
        else:
            if response.get('code') != expected or response.get('data') is not None:
                raise AssertionError('Nickname rejection lost its safe envelope')
            if message is not None and response.get('message') != message:
                raise AssertionError('Nickname rejection lost its fixed message')
            if message is None and (not isinstance(response.get('message'), str) or 'nickname:' not in response['message']):
                raise AssertionError('Nickname validation lost its field prefix')
            if after != before:
                raise AssertionError('Rejected nickname PUT changed DB or contacted S3')
            result['rejected_put_checks'] += 1
        returned()

    def reads(nickname):
        before = snapshot()
        profile = f3.expect(base, '/api/v1/users/me', 200, headers={'Authorization': 'Bearer ' + tokens['ROLE_USER']})
        comments = f3.expect(base, '/api/v1/comments/posts/read/' + str(POST), 200)
        if (profile['data']['userId'] != 42 or profile['data']['nickname'] != nickname
                or not any(row['commentId'] == COMMENT and row['authorNickname'] == nickname for row in comments['data'])):
            raise AssertionError('Profile and existing Community author did not observe the committed nickname')
        if snapshot() != before:
            raise AssertionError('Nickname journey GET changed DB or contacted S3')
        returned()
        result['profile_get_checks'] += 1
        result['community_get_checks'] += 1

    with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
        app = stub = None
        app_offset = 0
        try:
            app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
            result['startup_attempts'] = attempts
            prepare_business_rows()
            put(); reads('F13변경'); put(); reads('F13변경')
            put('f13변경', 409, '이미 사용 중인 닉네임입니다.')
            put('F13타인', 409, '이미 사용 중인 닉네임입니다.')
            for value in ('', ' ', 'a', '123', 'F13_불가', '이모지😀'):
                put(value, 400, None)
            put(expected=400, message='요청 본문 형식이 올바르지 않습니다.', raw=b'{')
            for token, message in ((None, '인증 토큰이 필요합니다.'), ('expired', '유효하지 않거나 만료된 토큰입니다.'),
                                   ('forged', '유효하지 않거나 만료된 토큰입니다.'), ('refresh', '토큰 파싱에 실패했습니다.')):
                put(expected=401, message=message, token=token)
            for token in ('missingId', 'zeroId', 'negativeId', 'stringId', 'missingRole', 'numericRole'):
                put(expected=401, message='토큰 파싱에 실패했습니다.', token=token)
            f3.sql('DELETE FROM pawbridge_user.users WHERE user_id=' + str(OTHER))
            put(expected=400, message='존재하지 않는 회원입니다.', token='F13_OTHER')
            for column in ('nickname', 'updated_at'):
                f3.sql('REVOKE UPDATE(' + column + ') ON pawbridge_user.users FROM pawbridge_f3_writer')
                try:
                    put(); put('F13회복', 500, '서버 에러!')
                    put(expected=401, message='인증 토큰이 필요합니다.', token=None)
                finally:
                    f3.sql('GRANT UPDATE(' + column + ') ON pawbridge_user.users TO pawbridge_f3_writer')
                reads('F13변경')
            put('F13회복', token='ROLE_ADMIN'); reads('F13회복')
            put('F13보호소', token='ROLE_SHELTER'); reads('F13보호소')
            require_fixture()
        finally:
            try:
                if app is not None:
                    result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F13 packaged application')
            finally:
                if stub is not None:
                    result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F13 S3 stub')
                (EVIDENCE / 'f13-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
        raise AssertionError('F13 bounded journey exceeded its one-MiB-per-child log budget')
    text = f4.fresh_log(app_path, app_offset)
    if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
        raise AssertionError('F13 normal HTTP/single-pool shutdown evidence missing')
    forbidden = (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'], os.environ['F3_TEST_JWT_SECRET'],
                 'F13변경', 'F13회복', 'F13보호소', 'F13타인', 'F13 합성회원', 'f13-other@fixture.invalid',
                 'F12_SYNTHETIC_NAME', 'F12_SYNTHETIC_NICK', 'f12-synthetic-42@fixture.invalid',
                 *f12.PRIVATE_VALUES, *tokens.values())
    if any(value in text for value in forbidden):
        raise AssertionError('F13 application log exposed credentials, tokens or synthetic personal fields')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN ('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('F13 application connections remain after normal shutdown')
    result.update(status='passed', shutdown_pools=1, remaining_application_connections=0)
    (EVIDENCE / 'f13-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F13 nickname PUT, profile/Community visibility, no-op, rejection, recovery and shutdown passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'smoke': smoke}[sys.argv[1]]()
