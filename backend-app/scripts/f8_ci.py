#!/usr/bin/env python3
"""Hosted-only public comment reads in the existing run-owned PG/S3 fixture."""
import json
import os
import pathlib
import sys

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f7_ci as f7

ROOT = f3.ROOT
EVIDENCE = f3.EVIDENCE
MARKER = 'pawbridge-f8-ci-only-v1'
FIELDS = {'commentId', 'postId', 'authorId', 'authorNickname', 'content', 'createdAt', 'updatedAt'}
COMMENT_COLUMNS = 'comment_id,post_id,author_id,content,created_at,updated_at,deleted_at'


def require_parent_fixture():
    # F7 retains F2 -> F3 -> F4 -> F5 ownership/marker/token/target checks.
    owner = f7.require_fixture()
    if f3.sql("SELECT count(*)=5 AND bool_and(has_column_privilege('pawbridge_f2_reader',"
            "'pawbridge_community.post_search_documents',column_name,'SELECT')) "
            "FROM information_schema.columns WHERE table_schema='pawbridge_community' "
            "AND table_name='post_search_documents' AND column_name IN "
            "('post_id','title_hash','content_hash','analyzer_version','tokens')") != 't':
        raise SystemExit('F8 requires the existing F7 search reader permissions')
    ports = f2.inspect_owned(os.environ['F2_CI_CONTAINER'])['NetworkSettings']['Ports'].get('5432/tcp')
    if (not ports or len(ports) != 1 or ports[0]['HostIp'] != '127.0.0.1'
            or os.environ['F2_TEST_JDBC_URL'] != 'jdbc:postgresql://127.0.0.1:'
            + ports[0]['HostPort'] + '/' + f2.DATABASE):
        raise SystemExit('F8 JDBC target must match the owned fixture container port')
    return owner


def require_fixture():
    owner = require_parent_fixture()
    if os.environ.get('F8_CI_OWNER') != owner:
        raise SystemExit('F8 fixture owner mismatch')
    if f3.sql("SELECT value || ':' || owner FROM pawbridge_f8_fixture.marker") != MARKER + ':' + owner:
        raise SystemExit('F8 fixture identity mismatch')
    if f3.sql("SELECT count(*)=7 AND bool_and(has_column_privilege('pawbridge_f2_reader',"
            "'pawbridge_community.comments',column_name,'SELECT')) "
            "FROM information_schema.columns WHERE table_schema='pawbridge_community' "
            "AND table_name='comments' AND column_name IN "
            "('comment_id','post_id','author_id','content','created_at','updated_at','deleted_at')") != 't':
        raise SystemExit('F8 fixture lacks the exact comment reader columns')
    return owner


def prepare():
    owner = require_parent_fixture()
    f2.export_environment({'F8_CI_OWNER': owner})
    f3.sql(input_text=(ROOT / 'scripts/f8_fixture.sql').read_text())
    require_fixture()
    print('Created only the run-owned comment table/marker and seven reader SELECT columns')


def prepare_business_rows():
    require_fixture()
    # Dedicated IDs preserve all prior compatibility/smoke rows and F4 FK fixtures.
    f3.sql("INSERT INTO pawbridge_user.users(user_id,nickname) VALUES(9800042,'댓글 합성작성자') "
           "ON CONFLICT(user_id) DO UPDATE SET nickname=EXCLUDED.nickname; "
           "INSERT INTO pawbridge_community.posts(post_id,author_id,title,content,board_type,"
           "image_urls,created_at,updated_at,deleted_at) VALUES"
           "(9800001,9800042,'F8 합성 부모','F8 합성 본문','COMMUNICATION','[]',"
           "'2025-01-01 01:02:03.123456','2025-01-02 01:02:03.123456',NULL),"
           "(9800002,9800042,'F8 삭제 부모','F8 합성 본문','COMMUNICATION','[]',"
           "'2025-01-01 01:02:03.123456','2025-01-02 01:02:03.123456','2025-01-03 01:02:03'); "
           "INSERT INTO pawbridge_community.comments(comment_id,post_id,author_id,content,"
           "created_at,updated_at,deleted_at) VALUES"
           "(9800011,9800001,9800042,'F8 오래된 댓글','2025-01-01 01:02:03.123456',"
           "'2025-01-02 01:02:03.123456',NULL),"
           "(9800012,9800001,9800099,'F8 최신 댓글 A','2025-01-03 01:02:03.654321',"
           "'2025-01-04 01:02:03.654321',NULL),"
           "(9800013,9800001,9800042,'F8 최신 댓글 B','2025-01-03 01:02:03.654321',"
           "'2025-01-04 01:02:03.654321',NULL),"
           "(9800014,9800001,9800042,'F8 삭제 댓글','2025-01-05 01:02:03',"
           "'2025-01-05 01:02:03','2025-01-06 01:02:03'),"
           "(9800021,9800002,9800042,'F8 삭제 부모의 댓글','2025-01-01 01:02:03',"
           "'2025-01-02 01:02:03',NULL),"
           "(9800031,9800003,9800042,'F8 없는 부모의 댓글','2025-01-01 01:02:03',"
           "'2025-01-02 01:02:03',NULL)")


def smoke():
    owner = require_fixture()
    jar = ROOT / 'boot/build/libs/pawbridge-backend-app.jar'
    if not jar.is_file():
        raise SystemExit('Packaged JAR missing')
    tokens = json.loads(pathlib.Path(os.environ['F3_TEST_TOKENS_PATH']).read_text())
    for value in tokens.values():
        print('::add-mask::' + value)
    bucket = 'f3-' + owner
    prefix = bucket + '/'
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
              'comment_get_checks': 0, 'comment_get_database_changes': 0, 'comment_get_s3_requests': 0,
              'denied_route_checks': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f8-packaged-startup.log', EVIDENCE / 'f8-s3-stub.log'
    base = endpoint = ''

    def snapshot():
        database = f3.sql("SELECT json_build_object("
            "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]'::json) FROM pawbridge_community.posts p),"
            "'comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]'::json) FROM pawbridge_community.comments c),"
            "'f4comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]'::json) FROM pawbridge_f4_fixture.comments c),"
            "'notes',(SELECT COALESCE(json_agg(n ORDER BY note_id),'[]'::json) FROM pawbridge_f4_fixture.private_notes n),"
            "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]'::json) FROM pawbridge_community.outbox_events e),"
            "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]'::json) FROM pawbridge_community.post_search_documents s))")
        storage = f3.expect(endpoint, '/__f3__/state', 200, headers=controls)
        return database, storage['counts'], len(storage['requests'])

    def returned():
        if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN "
                "('pawbridge_f2_reader','pawbridge_f3_writer') AND "
                "(state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('Comment response left a DB transaction active')

    def comments(post_id, expected=200, headers=None, extra=''):
        before = snapshot()
        response = f3.expect(base, '/api/v1/comments/posts/read/' + str(post_id) + extra,
                             expected, headers=headers)
        if snapshot() != before:
            raise AssertionError('Comment GET changed posts/comments/notes/outbox/search or contacted S3')
        result['comment_get_checks'] += 1
        returned()
        if expected == 200:
            if (response['code'] != 200 or response['message'] != '요청이 성공적으로 처리되었습니다.'
                    or not isinstance(response['data'], list)):
                raise AssertionError('Comment envelope or whole-array contract changed')
            rows = response['data']
            for item in rows:
                if set(item) != FIELDS or item['postId'] != post_id:
                    raise AssertionError('Comment item must retain exactly seven public fields for its post')
            times = [row['createdAt'] for row in rows]
            if times != sorted(times, reverse=True):
                raise AssertionError('Comments must use createdAt descending without an ID tie guarantee')
            return rows
        if response != {'code': 500, 'message': '서버 에러!', 'data': None}:
            raise AssertionError('Comment SQL failure did not return the safe whole-response 500')
        return response

    def by_id(rows):
        return {row['commentId']: row for row in rows}

    with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
        app = stub = None
        app_offset = 0
        try:
            app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
            result['startup_attempts'] = attempts
            prepare_business_rows()
            rows = comments(9800001)
            if (set(by_id(rows)) != {9800011, 9800012, 9800013}
                    or {row['commentId'] for row in rows[:2]} != {9800012, 9800013}
                    or rows[-1]['commentId'] != 9800011):
                raise AssertionError('Comment createdAt ordering or deleted-comment exclusion changed')
            known = by_id(rows)[9800011]
            if (known['authorId'] != 9800042 or known['authorNickname'] != '댓글 합성작성자'
                    or known['content'] != 'F8 오래된 댓글'
                    or known['createdAt'] == known['updatedAt']):
                raise AssertionError('Comment content/author/timestamp response changed')
            if by_id(rows)[9800012]['authorNickname'] != '사용자9800099':
                raise AssertionError('Missing comment author must fall back per item')
            forged = comments(9800001, headers={'Authorization': 'Bearer invalid',
                'X-User-Id': '999', 'X-User-Role': 'ROLE_ADMIN'}, extra='?page=999&size=1&authorId=999')
            if by_id(forged) != by_id(rows):
                raise AssertionError('Caller identity or query parameters changed public comments')
            if comments(9800099) != []:
                raise AssertionError('Missing post with no comments must return []')
            if [row['commentId'] for row in comments(9800002)] != [9800021]:
                raise AssertionError('Parent soft deletion must not filter existing comments')
            if [row['commentId'] for row in comments(9800003)] != [9800031]:
                raise AssertionError('Comment lookup must not validate parent existence')
            f3.sql('REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader')
            try:
                if any(row['authorNickname'] != '사용자' + str(row['authorId']) for row in comments(9800001)):
                    raise AssertionError('Defined User failure must retain item-local comment fallback')
            finally:
                f3.sql('GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader')
            if by_id(comments(9800001))[9800011] != known:
                raise AssertionError('Comment nickname lookup did not recover on the same pool')
            f3.sql('REVOKE SELECT(content) ON pawbridge_community.comments FROM pawbridge_f2_reader')
            try:
                comments(9800001, 500)
            finally:
                f3.sql('GRANT SELECT(content) ON pawbridge_community.comments TO pawbridge_f2_reader')
            if by_id(comments(9800001)) != by_id(rows):
                raise AssertionError('Comment SQL did not recover after restoring SELECT')
            # F9's newly supported POST requires authentication; other writes/near paths stay denied.
            for method, path in [('POST', '/api/v1/comments/posts/read/9800001'),
                    ('PUT', '/api/v1/comments/posts/read/9800001'),
                    ('DELETE', '/api/v1/comments/posts/read/9800001'),
                    ('HEAD', '/api/v1/comments/posts/read/9800001'),
                    ('GET', '/api/v1/comments/posts/read'),
                    ('GET', '/api/v1/comments/posts/read/9800001/extra'),
                    ('GET', '/api/v1/comments/posts/9800001'),
                    ('POST', '/api/v1/comments/posts/9800001'),
                    ('PUT', '/api/v1/comments/9800011'),
                    ('DELETE', '/api/v1/comments/9800011')]:
                before = snapshot()
                expected = 401 if (method, path) == ('POST', '/api/v1/comments/posts/9800001') else 403
                f3.expect(base, path, expected, method)
                if snapshot() != before:
                    raise AssertionError('Denied comment route changed DB or contacted S3')
                result['denied_route_checks'] += 1
                returned()
            result['comment_fields_order_deletion_parent_fallback_permissions_and_recovery'] = 'passed'
        finally:
            try:
                if app is not None:
                    result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F8 packaged application')
            finally:
                if stub is not None:
                    result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F8 S3 stub')
                (EVIDENCE / 'f8-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    text = f4.fresh_log(app_path, app_offset)
    if text.count('Shutdown completed.') < 2 or 'Graceful shutdown complete' not in text:
        raise AssertionError('F8 normal HTTP/two-pool shutdown evidence missing')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN "
            "('pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('F8 application connections remain after normal shutdown')
    result.update(status='passed', shutdown_pools=2, remaining_application_connections=0)
    (EVIDENCE / 'f8-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F8 actual-JAR comment journey, zero GET writes/S3, recovery and normal shutdown passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'smoke': smoke}[sys.argv[1]]()
