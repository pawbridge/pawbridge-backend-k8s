#!/usr/bin/env python3
"""Hosted-only comment PUT after the original F9 and exact F10 ACL regressions."""
import datetime
import json
import os
import pathlib
import sys
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f6_ci as f6
import f8_ci as f8
import f9_ci as f9
import f10_ci as f10

ROOT = f3.ROOT
EVIDENCE = f3.EVIDENCE
MARKER = 'pawbridge-f11-ci-only-v1'
POST = 9970001
FOREIGN_COMMENT = 9970010
DELETED_COMMENT = 9970011
NICKNAME = 'F11 합성 댓글작성자'
PRIVATE_BODY = 'F11_PRIVATE_BODY_DO_NOT_LOG'
COMPATIBILITY_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlCommentUpdateCompatibilityTest'


def require_seoul_get_timestamp(observed, stored):
    # JdbcCommentReader reads TIMESTAMP with a UTC Calendar, then Timestamp.toLocalDateTime()
    # projects that instant into the JVM zone. f4 starts this packaged JVM in Asia/Seoul.
    expected = (datetime.datetime.fromisoformat(stored).replace(tzinfo=datetime.timezone.utc)
                .astimezone(ZoneInfo('Asia/Seoul')).replace(tzinfo=None))
    if datetime.datetime.fromisoformat(observed) != expected:
        raise AssertionError('GET did not observe the committed PUT stored timestamp in the Seoul JVM')


def verify_results():
    f2.require_hosted()
    counts = {}
    for task, zone in (('commentUpdateCompatibilityTest', 'UTC'),
                       ('commentUpdateCompatibilitySeoulTest', 'Asia/Seoul')):
        path = ROOT / 'boot/build/test-results' / task / ('TEST-' + COMPATIBILITY_CLASS + '.xml')
        try:
            suite = ET.parse(path).getroot()
            values = {key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
        except (OSError, ET.ParseError, KeyError, ValueError):
            raise SystemExit('F11 compatibility report missing or malformed: ' + task)
        cases = suite.findall('testcase')
        if (suite.tag != 'testsuite' or suite.attrib.get('name') != COMPATIBILITY_CLASS
                or values['tests'] <= 0 or any(values[key] != 0 for key in ('failures', 'errors', 'skipped'))
                or len(cases) != values['tests']
                or any(case.attrib.get('classname') != COMPATIBILITY_CLASS for case in cases)):
            raise SystemExit('F11 compatibility report must contain executed, successful, unskipped tests: ' + task)
        counts[zone] = values['tests']
    print('Verified separate F11 compatibility execution counts: ' + json.dumps(counts, sort_keys=True))
    return counts


def require_fixture():
    # F9/F10 exact ACL checks stay strict and are never reused after this extension.
    owner = f8.require_fixture()
    for stage, marker in ((9, f9.MARKER), (10, f10.MARKER), (11, MARKER)):
        if os.environ.get('F' + str(stage) + '_CI_OWNER') != owner:
            raise SystemExit('F' + str(stage) + ' fixture owner mismatch')
        if f3.sql("SELECT value || ':' || owner FROM pawbridge_f" + str(stage) + '_fixture.marker') != marker + ':' + owner:
            raise SystemExit('F' + str(stage) + ' fixture identity mismatch')
    if f3.sql("SELECT count(*)=7 AND bool_and("
            "has_column_privilege('pawbridge_f3_writer','pawbridge_community.comments',column_name,'INSERT') "
            "= (column_name IN ('post_id','author_id','content','created_at','updated_at')) "
            "AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.comments',column_name,'SELECT') "
            "= (column_name IN ('comment_id','author_id','deleted_at')) "
            "AND has_column_privilege('pawbridge_f3_writer','pawbridge_community.comments',column_name,'UPDATE') "
            "= (column_name IN ('content','deleted_at','updated_at'))) "
            "AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_community.comments','DELETE') "
            "AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_user.users','INSERT,UPDATE,DELETE') "
            "AND NOT has_schema_privilege('pawbridge_f3_writer','pawbridge_community','CREATE') "
            "AND NOT has_sequence_privilege('pawbridge_f3_writer','pawbridge_community.comments_comment_id_seq','USAGE,SELECT,UPDATE') "
            "AND (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_community.comments'::regclass "
            "AND conname='ck_rollback_charset_comments' AND contype='c' AND convalidated) "
            "FROM information_schema.columns WHERE table_schema='pawbridge_community' AND table_name='comments'") != 't':
        raise SystemExit('F11 fixture lacks the exact extended comment ACL or validated charset constraint')
    return owner


def prepare():
    owner = f10.require_fixture()
    f2.export_environment({'F11_CI_OWNER': owner})
    f3.sql(input_text=(ROOT / 'scripts/f11_fixture.sql').read_text())
    require_fixture()
    print('Extended only the owned comment writer UPDATE(content) after strict F10 validation')


def prepare_business_rows():
    require_fixture()
    f3.sql("INSERT INTO pawbridge_user.users(user_id,nickname) VALUES(42," + f4.literal(NICKNAME)
           + ') ON CONFLICT(user_id) DO UPDATE SET nickname=EXCLUDED.nickname; '
           'INSERT INTO pawbridge_community.posts(post_id,author_id,title,content,board_type,'
           'image_urls,created_at,updated_at,deleted_at) VALUES'
           + ','.join('(' + str(POST + index) + ",9970043,'F11 합성 부모','F11 부모 본문','COMMUNICATION',"
                      "'[]','2025-01-01 01:02:03.123456','2025-01-02 01:02:03.123456',NULL)"
                      for index in range(3))
           + '; INSERT INTO pawbridge_community.comments(comment_id,post_id,author_id,content,'
           'created_at,updated_at,deleted_at) VALUES('
           + str(FOREIGN_COMMENT) + ',' + str(POST)
           + ",9970043,'F11 다른 작성자 댓글','2025-01-01 01:02:03.123456','2025-01-02 01:02:03.123456',NULL),("
           + str(DELETED_COMMENT) + ',' + str(POST)
           + ",42,'F11 이미 삭제 댓글','2025-01-01 01:02:03.123456','2025-01-02 01:02:03.123456','2025-01-03 01:02:03')")


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
              'comment_post_checks': 0, 'comment_get_checks': 0, 'comment_delete_checks': 0,
              'comment_put_checks': 0, 'changed_put_checks': 0, 'same_put_checks': 0,
              'rejected_put_checks': 0, 'unrelated_database_changes': 0, 's3_requests': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f11-packaged-startup.log', EVIDENCE / 'f11-s3-stub.log'
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
            raise AssertionError('F11 request left a DB transaction active')

    def unchanged_neighbors(before, after):
        if ({key: rows for key, rows in before[0].items() if key != 'comments'}
                != {key: rows for key, rows in after[0].items() if key != 'comments'} or before[1:] != after[1:]):
            raise AssertionError('Comment request changed User/posts/F4 rows/outbox/search or contacted S3')

    def rows_by_id(value):
        return {row['comment_id']: row for row in value[0]['comments']}

    def read(post_id):
        before = snapshot()
        response = f3.expect(base, '/api/v1/comments/posts/read/' + str(post_id), 200)
        if snapshot() != before:
            raise AssertionError('F8 GET changed DB or contacted S3')
        if (response['code'] != 200 or response['message'] != '요청이 성공적으로 처리되었습니다.'
                or not isinstance(response['data'], list)
                or any(set(row) != f8.FIELDS for row in response['data'])):
            raise AssertionError('F8 GET lost its original whole-array/seven-field envelope')
        result['comment_get_checks'] += 1
        returned()
        return {row['commentId']: row for row in response['data']}

    def create(post_id=POST):
        # Exercise F9 through HTTP; its original-ACL helpers cannot run at this stage.
        before = snapshot()
        response = f3.expect(base, '/api/v1/comments/posts/' + str(post_id), 200, 'POST',
            {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tokens['ROLE_USER']},
            json.dumps({'content': PRIVATE_BODY}).encode())
        after = snapshot()
        item = response['data']
        if (response['code'] != 200 or response['message'] != '댓글이 생성되었습니다.' or set(item) != f8.FIELDS
                or item['postId'] != post_id or item['authorId'] != 42 or item['authorNickname'] != NICKNAME
                or item['content'] != PRIVATE_BODY):
            raise AssertionError('F9 HTTP creation contract changed inside the F11 journey')
        old, new = rows_by_id(before), rows_by_id(after)
        if set(new) - set(old) != {item['commentId']} or any(new[key] != row for key, row in old.items()):
            raise AssertionError('F9 setup must add exactly one comment and preserve all previous rows')
        unchanged_neighbors(before, after)
        returned()
        result['comment_post_checks'] += 1
        # The stored GET, not the immediate POST's higher precision, is PUT's time baseline.
        return read(post_id)[item['commentId']]

    def update(comment_id, content='F11 변경 본문', expected=200, message=None,
               token='ROLE_USER', raw=None, nickname=NICKNAME):
        before = snapshot()
        old = rows_by_id(before)
        saved = read(old[comment_id]['post_id'])[comment_id] if expected == 200 else None
        headers = {'Content-Type': 'application/json', 'X-User-Id': '9970999', 'X-User-Role': 'ROLE_ADMIN'}
        if token is not None:
            headers['Authorization'] = 'Bearer ' + tokens[token]
        body = json.dumps({'content': content}).encode() if raw is None else raw
        response = f3.expect(base, '/api/v1/comments/' + str(comment_id), expected, 'PUT', headers, body)
        after = snapshot()
        result['comment_put_checks'] += 1
        returned()
        if expected != 200:
            if after != before:
                raise AssertionError('Rejected comment PUT changed DB or contacted S3')
            if response['code'] != expected or response['data'] is not None:
                raise AssertionError('Comment PUT error did not retain a safe whole response')
            if message is not None and response['message'] != message:
                raise AssertionError('Comment PUT failure message changed')
            result['rejected_put_checks'] += 1
            return
        expected_item = dict(saved, content=content, authorNickname=nickname)
        if response != {'code': 200, 'message': '댓글이 수정되었습니다.', 'data': expected_item}:
            raise AssertionError('PUT must return seven fields, request content and the stored pre-update timestamps')
        new = rows_by_id(after)
        if set(old) != set(new) or any(new[key] != row for key, row in old.items() if key != comment_id):
            raise AssertionError('PUT changed another comment or the physical row set')
        previous, current = old[comment_id], new[comment_id]
        if content == previous['content']:
            if after != before:
                raise AssertionError('Same-content PUT must leave every DB row and S3 unchanged')
            result['same_put_checks'] += 1
        else:
            if (current['content'] != content or current['updated_at'] == previous['updated_at']
                    or {key: value for key, value in previous.items() if key not in ('content', 'updated_at')}
                    != {key: value for key, value in current.items() if key not in ('content', 'updated_at')}):
                raise AssertionError('Changed PUT must change only content/updated_at and preserve deleted_at')
            result['changed_put_checks'] += 1
        unchanged_neighbors(before, after)
        observed = read(previous['post_id'])[comment_id]
        if observed['content'] != content or observed['authorNickname'] != nickname:
            raise AssertionError('GET did not observe the committed PUT content and stored timestamp')
        require_seoul_get_timestamp(observed['updatedAt'], current['updated_at'])
        return observed

    def delete(comment_id):
        before = snapshot()
        response = f3.expect(base, '/api/v1/comments/' + str(comment_id), 200, 'DELETE',
                             {'Authorization': 'Bearer ' + tokens['ROLE_USER']})
        after = snapshot()
        returned()
        old, new = rows_by_id(before), rows_by_id(after)
        if response != {'code': 200, 'message': '댓글이 삭제되었습니다.', 'data': None}:
            raise AssertionError('Existing F10 DELETE contract changed inside the F11 journey')
        if (set(old) != set(new) or any(new[key] != row for key, row in old.items() if key != comment_id)
                or new[comment_id]['deleted_at'] is None
                or new[comment_id]['updated_at'] == old[comment_id]['updated_at']
                or {key: value for key, value in old[comment_id].items() if key not in ('deleted_at', 'updated_at')}
                != {key: value for key, value in new[comment_id].items() if key not in ('deleted_at', 'updated_at')}):
            raise AssertionError('DELETE must preserve edited content and every row, changing only two dates')
        unchanged_neighbors(before, after)
        if comment_id in read(old[comment_id]['post_id']):
            raise AssertionError('Deleted edited comment remained visible through F8 GET')
        result['comment_delete_checks'] += 1

    with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
        app = stub = None
        app_offset = 0
        try:
            app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
            result['startup_attempts'] = attempts
            prepare_business_rows()
            first = create()['commentId']
            for token in (None, 'refresh', 'expired', 'forged', 'missingId', 'zeroId', 'negativeId',
                          'stringId', 'missingRole', 'numericRole'):
                update(first, expected=401, token=token)
            update('not-a-long', expected=401, token=None, raw=b'{"content":')
            for token in ('ROLE_USER', 'ROLE_ADMIN', 'ROLE_SHELTER'):
                update(FOREIGN_COMMENT, 'F11 다른 작성자 댓글', 403, '댓글에 접근할 권한이 없습니다.', token)
            for absent in (9970999, 0, -1, DELETED_COMMENT):
                update(absent, expected=404, message='댓글을 찾을 수 없습니다.')
            for malformed in ('not-a-long', '9223372036854775808'):
                update(malformed, expected=500, message='서버 에러!')
            for value in ({}, {'content': None}, {'content': ''}, {'content': ' \t\r\n\f'}):
                update(first, expected=400, message='content: 내용은 필수입니다', raw=json.dumps(value).encode())
            for raw in (b'{"content":', b'', b'null'):
                update(first, expected=400, message='요청 본문 형식이 올바르지 않습니다.', raw=raw)
            update(9970999, expected=400, message='content: 내용은 필수입니다', raw=b'{}')
            update(first, PRIVATE_BODY + ' \U0001f600 문자', 400, '일부 이모지 등 지원하지 않는 문자를 제외해 주세요.')
            update(first, PRIVATE_BODY + ' \x00 NUL', 500, '서버 에러!')

            for index, token in enumerate(('ROLE_USER', 'ROLE_ADMIN', 'ROLE_SHELTER')):
                update(first, ' F11 원문 앞뒤 공백\n보존 ' + str(index), token=token)
            for content in ('\u2003', '\u3000', 'F11 긴 내용 ' + '가' * 12000):
                update(first, content)
            current = update(first, PRIVATE_BODY)['content']

            # The short User read still runs on the same-body branch and its failure recovers.
            f3.sql('DELETE FROM pawbridge_user.users WHERE user_id=42')
            try:
                update(first, current, nickname='사용자42')
                current = update(first, 'F11 없는 회원 fallback', nickname='사용자42')['content']
            finally:
                f3.sql('INSERT INTO pawbridge_user.users(user_id,nickname) VALUES(42,' + f4.literal(NICKNAME) + ')')
            f3.sql('REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader')
            try:
                update(first, current, nickname='사용자42')
                current = update(first, 'F11 닉네임 권한 fallback', nickname='사용자42')['content']
            finally:
                f3.sql('GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader')
            for nickname, expected_name in (('F11 회복 이름', 'F11 회복 이름'), ('', None), ('  ', '  '), (NICKNAME, NICKNAME)):
                f3.sql('UPDATE pawbridge_user.users SET nickname=' + f4.literal(nickname) + ' WHERE user_id=42')
                update(first, current, nickname=expected_name)

            deleted_parent = create(POST + 1)['commentId']
            absent_parent = create(POST + 2)['commentId']
            f3.sql('UPDATE pawbridge_community.posts SET deleted_at=CURRENT_TIMESTAMP WHERE post_id=' + str(POST + 1)
                   + '; DELETE FROM pawbridge_community.posts WHERE post_id=' + str(POST + 2)
                   + '; REVOKE SELECT(' + f6.POST_COLUMNS + ') ON pawbridge_community.posts FROM pawbridge_f2_reader')
            try:
                update(deleted_parent, 'F11 삭제 부모의 댓글')
                update(absent_parent, 'F11 없는 부모의 댓글')
            finally:
                f3.sql('GRANT SELECT(' + f6.POST_COLUMNS + ') ON pawbridge_community.posts TO pawbridge_f2_reader')

            # This proves no UPDATE on same content, rather than only an unchanged row value.
            f3.sql('REVOKE UPDATE(content,updated_at) ON pawbridge_community.comments FROM pawbridge_f3_writer')
            try:
                update(first, current)
                update(first, PRIVATE_BODY + ' 변경 권한 실패', 500, '서버 에러!')
                update(first, current, 401, token=None)
            finally:
                f3.sql('GRANT UPDATE(content,updated_at) ON pawbridge_community.comments TO pawbridge_f3_writer')
            current = update(first, 'F11 변경 권한 회복')['content']
            for role, privilege in (('pawbridge_f2_reader', 'SELECT(content)'),
                                    ('pawbridge_f3_writer', 'SELECT(comment_id)')):
                f3.sql('REVOKE ' + privilege + ' ON pawbridge_community.comments FROM ' + role)
                try:
                    update(first, PRIVATE_BODY + ' SQL 권한 실패', 500, '서버 에러!')
                finally:
                    f3.sql('GRANT ' + privilege + ' ON pawbridge_community.comments TO ' + role)
                current = update(first, current + ' 회복')['content']
            delete(first)
            update(first, 'F11 삭제 후 재수정', 404, '댓글을 찾을 수 없습니다.')
            result['post_get_changed_same_put_validation_nickname_permissions_delete_and_recovery'] = 'passed'
        finally:
            try:
                if app is not None:
                    result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F11 packaged application')
            finally:
                if stub is not None:
                    result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F11 S3 stub')
                (EVIDENCE / 'f11-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
        raise AssertionError('F11 bounded journey exceeded its one-MiB-per-child log budget')
    text = f4.fresh_log(app_path, app_offset)
    if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
        raise AssertionError('F11 normal HTTP/two-pool shutdown evidence missing')
    if any(value in text for value in (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'],
            os.environ['F3_TEST_JWT_SECRET'], PRIVATE_BODY, *tokens.values())):
        raise AssertionError('F11 application log exposed credentials, tokens or request content')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN "
            "('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('F11 application connections remain after normal shutdown')
    result.update(status='passed', shutdown_pools=2, remaining_application_connections=0)
    (EVIDENCE / 'f11-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F11 actual-JAR POST/GET/PUT/DELETE, dirty/no-op writes, recovery and shutdown passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'results': verify_results, 'smoke': smoke}[sys.argv[1]]()
