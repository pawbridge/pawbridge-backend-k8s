#!/usr/bin/env python3
"""Hosted-only authenticated comment creation in the existing owned PG/S3 fixture."""
import datetime
import json
import os
import pathlib
import sys

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f8_ci as f8

ROOT = f3.ROOT
EVIDENCE = f3.EVIDENCE
MARKER = 'pawbridge-f9-ci-only-v1'
INSERT_COLUMNS = 'post_id,author_id,content,created_at,updated_at'
POST = 9930001
NICKNAME = 'F9 합성 댓글작성자'


def require_fixture():
    owner = f8.require_fixture()
    if os.environ.get('F9_CI_OWNER') != owner:
        raise SystemExit('F9 fixture owner mismatch')
    if f3.sql("SELECT value || ':' || owner FROM pawbridge_f9_fixture.marker") != MARKER + ':' + owner:
        raise SystemExit('F9 fixture identity mismatch')
    if f3.sql("SELECT count(*)=7 AND bool_and("
            "has_column_privilege('pawbridge_f3_writer','pawbridge_community.comments',column_name,'INSERT') "
            "= (column_name IN ('post_id','author_id','content','created_at','updated_at')) "
            "AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.comments',column_name,'SELECT') "
            "= (column_name='comment_id') "
            "AND NOT has_column_privilege('pawbridge_f3_writer','pawbridge_community.comments',column_name,'UPDATE')) "
            "AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_community.comments','DELETE') "
            "AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_user.users','INSERT,UPDATE,DELETE') "
            "AND NOT has_schema_privilege('pawbridge_f3_writer','pawbridge_community','CREATE') "
            "AND NOT has_sequence_privilege('pawbridge_f3_writer','pawbridge_community.comments_comment_id_seq','USAGE,SELECT,UPDATE') "
            "AND (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_community.comments'::regclass "
            "AND conname='ck_rollback_charset_comments' AND contype='c' AND convalidated) "
            "FROM information_schema.columns WHERE table_schema='pawbridge_community' AND table_name='comments'") != 't':
        raise SystemExit('F9 fixture lacks the exact comment writer grants or validated charset constraint')
    return owner


def prepare():
    owner = f8.require_fixture()
    f2.export_environment({'F9_CI_OWNER': owner})
    f3.sql(input_text=(ROOT / 'scripts/f9_fixture.sql').read_text())
    require_fixture()
    print('Added only the owned comments charset constraint and minimal INSERT/RETURNING grants; no sequence privileges')


def prepare_business_rows():
    require_fixture()
    values = []
    for index, board in enumerate(('COMMUNICATION', 'ADOPTION', 'PROTECTION', 'MISSING', 'REPORT')):
        values.append('(' + str(POST + index) + ",9930043,'F9 합성 부모','F9 부모 본문',"
                      + f4.literal(board) + ",'[]','2025-01-01 01:02:03.123456',"
                      "'2025-01-02 01:02:03.123456',NULL)")
    values.append('(' + str(POST + 5) + ",9930043,'F9 삭제 부모','F9 부모 본문','COMMUNICATION',"
                  "'[]','2025-01-01 01:02:03.123456','2025-01-02 01:02:03.123456','2025-01-03 01:02:03')")
    f3.sql("INSERT INTO pawbridge_user.users(user_id,nickname) VALUES(42," + f4.literal(NICKNAME)
           + ') ON CONFLICT(user_id) DO UPDATE SET nickname=EXCLUDED.nickname; '
           'INSERT INTO pawbridge_community.posts(post_id,author_id,title,content,board_type,'
           'image_urls,created_at,updated_at,deleted_at) VALUES' + ','.join(values))


def smoke():
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():
        raise SystemExit('Packaged JAR missing')
    tokens = json.loads(pathlib.Path(os.environ['F3_TEST_TOKENS_PATH']).read_text())
    for value in tokens.values():
        print('::add-mask::' + value)
    bucket, prefix = 'f3-' + owner, 'f3-' + owner + '/'
    controls = {'X-F3-Owner': owner}
    base_env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR')
                if key in os.environ}
    app_env = dict(base_env, F2_JDBC_URL=os.environ['F2_TEST_JDBC_URL'],
        F2_DB_USER=f3.APP, F2_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'],
        F2_POOL_MAX_SIZE='4', F3_DB_WRITER_USER=f3.WRITER,
        F3_DB_WRITER_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'],
        F3_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'], F3_STORAGE_BUCKET=bucket,
        F3_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', F3_STORAGE_OBJECT_PREFIX=prefix,
        F3_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', F3_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret',
        F3_STORAGE_REGION='us-east-1')
    result = {'status': 'incomplete', 'timezone': 'Asia/Seoul',
              'pool_maximum': 4,
              'comment_post_checks': 0, 'comments_created': 0, 'rejected_post_checks': 0,
              'comment_get_checks': 0, 'unrelated_database_changes': 0, 's3_requests': 0,
              'timestamp_contract': 'Immediate precision retained; TIMESTAMP(6) GET round-trip within one microsecond'}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f9-packaged-startup.log', EVIDENCE / 'f9-s3-stub.log'
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
                "('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') AND "
                "(state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('Comment request left a DB transaction active')

    def read(post_id):
        before = snapshot()
        response = f3.expect(base, '/api/v1/comments/posts/read/' + str(post_id), 200)
        if snapshot() != before:
            raise AssertionError('F8 GET after comment POST changed DB or contacted S3')
        if response['code'] != 200 or response['message'] != '요청이 성공적으로 처리되었습니다.':
            raise AssertionError('F8 public GET envelope changed')
        for row in response['data']:
            if set(row) != f8.FIELDS:
                raise AssertionError('F8 GET lost the exact seven-field comment contract')
        result['comment_get_checks'] += 1
        returned()
        return {row['commentId']: row for row in response['data']}

    def create(post_id=POST, value=None, expected=200, message=None, token='ROLE_USER', raw=None,
               nickname=NICKNAME, absent_body=False):
        body = None if absent_body else raw if raw is not None else json.dumps(value).encode()
        headers = {'Content-Type': 'application/json', 'X-User-Id': '9930999', 'X-User-Role': 'ROLE_ADMIN'}
        if token is not None:
            headers['Authorization'] = 'Bearer ' + tokens[token]
        before = snapshot()
        response = f3.expect(base, '/api/v1/comments/posts/' + str(post_id), expected, 'POST', headers, body)
        after = snapshot()
        result['comment_post_checks'] += 1
        returned()
        if expected != 200:
            if after != before:
                raise AssertionError('Rejected comment POST changed DB or contacted S3')
            if response['code'] != expected or response['data'] is not None:
                raise AssertionError('Comment failure did not return the whole safe envelope')
            if message is not None and response['message'] != message:
                raise AssertionError('Comment failure message changed')
            result['rejected_post_checks'] += 1
            return response
        if (response['code'] != 200 or response['message'] != '댓글이 생성되었습니다.'
                or set(response['data']) != f8.FIELDS):
            raise AssertionError('Comment creation must return HTTP/code200 and exact seven-field data')
        item = response['data']
        if (not isinstance(item['commentId'], int) or item['commentId'] <= 0
                or item['postId'] != post_id or item['authorId'] != 42
                or item['authorNickname'] != nickname or item['content'] != value['content']):
            raise AssertionError('Generated ID, JWT author, original content or nickname changed')
        old_rows = {row['comment_id']: row for row in before[0]['comments']}
        new_rows = {row['comment_id']: row for row in after[0]['comments']}
        if (set(new_rows) - set(old_rows) != {item['commentId']}
                or any(new_rows[key] != row for key, row in old_rows.items())):
            raise AssertionError('A successful POST must insert exactly one new comment and preserve old rows')
        stored = new_rows[item['commentId']]
        if (stored['post_id'] != post_id or stored['author_id'] != 42
                or stored['content'] != value['content'] or stored['deleted_at'] is not None):
            raise AssertionError('Stored comment differs from the authenticated input')
        if ({key: rows for key, rows in before[0].items() if key != 'comments'}
                != {key: rows for key, rows in after[0].items() if key != 'comments'} or before[1:] != after[1:]):
            raise AssertionError('Comment POST changed User/posts/F4 rows/outbox/search or contacted S3')
        fetched = read(post_id)[item['commentId']]
        for field in f8.FIELDS - {'createdAt', 'updatedAt'}:
            if fetched[field] != item[field]:
                raise AssertionError('Committed comment was not immediately visible through F8 GET')
        for field in ('createdAt', 'updatedAt'):
            delta = abs(datetime.datetime.fromisoformat(fetched[field])
                        - datetime.datetime.fromisoformat(item[field]))
            if delta > datetime.timedelta(microseconds=1):
                raise AssertionError('Immediate comment time and stored TIMESTAMP(6) round-trip differ beyond precision')
        result['comments_created'] += 1
        return item

    with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
        app = stub = None
        app_offset = 0
        try:
            app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
            result['startup_attempts'] = attempts
            prepare_business_rows()
            if read(POST) != {}:
                raise AssertionError('Dedicated F9 parent must start with no comments')
            for token in (None, 'refresh', 'expired', 'forged', 'missingId', 'zeroId', 'negativeId',
                          'stringId', 'missingRole', 'numericRole'):
                create(value={'content': 'F9_PRIVATE_BODY_DO_NOT_LOG'}, expected=401, token=token)
            for value in ({}, {'content': None}, {'content': ''}, {'content': ' \t\r\n\f'}):
                create(value=value, expected=400, message='content: 내용은 필수입니다')
            for raw, absent in ((b'{"content":', False), (None, True)):
                create(raw=raw, absent_body=absent, expected=400, message='요청 본문 형식이 올바르지 않습니다.')
            for post_id in (POST + 5, 9930999, 0, -1):
                create(post_id, {'content': 'F9 부모 부재'}, 404, '게시글을 찾을 수 없습니다.')
            for invalid in ('not-a-long', '9223372036854775808'):
                create(invalid, {'content': 'F9 잘못된 경로'}, 500, '서버 에러!')
            create(value={'content': 'F9 \U0001f600 문자'}, expected=400,
                   message='일부 이모지 등 지원하지 않는 문자를 제외해 주세요.')
            create(value={'content': 'F9 \x00 NUL'}, expected=500, message='서버 에러!')

            for index, token in enumerate(('ROLE_USER', 'ROLE_ADMIN', 'ROLE_SHELTER', 'ROLE_USER', 'ROLE_USER')):
                content = ' F9 원문 앞뒤 공백\n보존 ' if index == 0 else 'F9 모든 게시판 ' + str(index)
                create(POST + index, {'content': content}, token=token)
            for content in ('\u2003', '\u3000', 'F9 긴 내용 ' + '가' * 12000):
                create(value={'content': content})
            duplicate = create(value={'content': 'F9 같은 입력도 새 댓글'})
            if create(value={'content': 'F9 같은 입력도 새 댓글'})['commentId'] == duplicate['commentId']:
                raise AssertionError('Separate POSTs must not introduce implicit idempotency')

            f3.sql('DELETE FROM pawbridge_user.users WHERE user_id=42')
            try:
                create(value={'content': 'F9 없는 회원 fallback'}, nickname='사용자42')
            finally:
                f3.sql('INSERT INTO pawbridge_user.users(user_id,nickname) VALUES(42,' + f4.literal(NICKNAME) + ')')
            f3.sql('REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader')
            try:
                create(value={'content': 'F9 닉네임 권한 fallback'}, nickname='사용자42')
            finally:
                f3.sql('GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader')
            create(value={'content': 'F9 닉네임 회복'})

            for privilege in ('INSERT(content)', 'SELECT(comment_id)'):
                f3.sql('REVOKE ' + privilege + ' ON pawbridge_community.comments FROM pawbridge_f3_writer')
                try:
                    create(value={'content': 'F9_PRIVATE_BODY_DO_NOT_LOG'}, expected=500, message='서버 에러!')
                    create(value={'content': 'F9 인증 선행'}, expected=401, token=None)
                finally:
                    f3.sql('GRANT ' + privilege + ' ON pawbridge_community.comments TO pawbridge_f3_writer')
                create(value={'content': 'F9 댓글 권한 회복'})
            result['post_get_validation_roles_boards_fallback_permissions_and_recovery'] = 'passed'
        finally:
            try:
                if app is not None:
                    result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F9 packaged application')
            finally:
                if stub is not None:
                    result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F9 S3 stub')
                (EVIDENCE / 'f9-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
        raise AssertionError('F9 bounded synthetic journey exceeded its one-MiB-per-child log budget')
    text = f4.fresh_log(app_path, app_offset)
    if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
        raise AssertionError('F9 normal HTTP/two-pool shutdown evidence missing')
    if any(value in text for value in (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'],
            os.environ['F3_TEST_JWT_SECRET'], 'F9_PRIVATE_BODY_DO_NOT_LOG', *tokens.values())):
        raise AssertionError('F9 application log exposed credentials, tokens or request content')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN "
            "('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('F9 application connections remain after normal shutdown')
    result.update(status='passed', shutdown_pools=2, remaining_application_connections=0)
    (EVIDENCE / 'f9-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F9 actual-JAR comment POST/GET, isolated writes, failure recovery and normal shutdown passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'smoke': smoke}[sys.argv[1]]()
