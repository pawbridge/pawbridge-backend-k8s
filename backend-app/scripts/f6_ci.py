#!/usr/bin/env python3
"""Hosted-only public list journey on the existing F2-F5 owned PG and S3 stub."""
import json
import os
import pathlib
import sys

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f5_ci as f5

ROOT = f3.ROOT
EVIDENCE = f3.EVIDENCE
FIELDS = {'postId', 'authorId', 'authorNickname', 'title', 'content', 'boardType',
          'imageUrls', 'createdAt', 'updatedAt'}
POST_COLUMNS = 'post_id,author_id,title,content,board_type,image_urls,deleted_at,created_at,updated_at'


def require_fixture():
    # This entire chain verifies hosted execution, container owner, DB/admin/reader,
    # token-file ownership and the F2/F3/F4/F5 markers. No extra F6 DDL or grants.
    return f5.require_fixture()


def prepare_business_rows():
    require_fixture()
    # Existing F4 comments/notes keep their FKs. Retire earlier smoke rows rather
    # than truncate through their dependent tables or delete another test's rows.
    f3.sql('UPDATE pawbridge_community.posts SET deleted_at=COALESCE(deleted_at,CURRENT_TIMESTAMP); '
           "INSERT INTO pawbridge_user.users(user_id,nickname) VALUES(42,'목록 합성작성자') "
           "ON CONFLICT(user_id) DO UPDATE SET nickname=EXCLUDED.nickname")


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
    app_env = dict(base_env, PAWBRIDGE_DB_URL=os.environ['F2_TEST_JDBC_URL'],
        PAWBRIDGE_DB_USER=f3.APP, PAWBRIDGE_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'],
        PAWBRIDGE_DB_POOL_MAX_SIZE='4',
        PAWBRIDGE_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'], PAWBRIDGE_STORAGE_BUCKET=bucket,
        PAWBRIDGE_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', PAWBRIDGE_STORAGE_OBJECT_PREFIX=prefix,
        PAWBRIDGE_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret',
        PAWBRIDGE_STORAGE_REGION='us-east-1')
    result = {'status': 'incomplete', 'timezone': 'Asia/Seoul',
              'pool_maximum': 4,
              'list_get_checks': 0, 'list_get_database_changes': 0, 'list_get_s3_requests': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f6-packaged-startup.log', EVIDENCE / 'f6-s3-stub.log'
    base = endpoint = ''

    def state():
        return f3.expect(endpoint, '/__f3__/state', 200, headers=controls)

    def snapshot():
        database = f3.sql("SELECT json_build_object("
            "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]'::json) FROM pawbridge_community.posts p),"
            "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]'::json) FROM pawbridge_community.outbox_events e),"
            "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]'::json) FROM pawbridge_community.post_search_documents s))")
        storage = state()
        return database, storage['counts'], len(storage['requests'])

    def returned():
        if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN "
                "('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer') AND "
                "(state='idle in transaction' OR xact_start IS NOT NULL)") != '0':
            raise AssertionError('List response left a DB transaction active')

    def listing(path='/api/v1/posts/read', expected=200, headers=None):
        before = snapshot()
        response = f3.expect(base, path, expected, headers=headers)
        after = snapshot()
        if after != before:
            raise AssertionError('List GET changed posts/outbox/search or contacted S3')
        result['list_get_checks'] += 1
        returned()
        if expected == 200:
            if (response['code'] != 200 or response['message'] != '요청이 성공적으로 처리되었습니다.'
                    or not isinstance(response['data'], list)):
                raise AssertionError('List envelope or whole-array contract changed')
            for item in response['data']:
                if set(item) != FIELDS:
                    raise AssertionError('List item no longer has exactly the nine F2 fields')
            times = [row['createdAt'] for row in response['data']]
            if times != sorted(times, reverse=True):
                raise AssertionError('List is not ordered by createdAt descending')
            return response['data']
        if expected == 500 and response != {'code': 500, 'message': '서버 에러!', 'data': None}:
            raise AssertionError('List SQL/mapping failure did not return safe whole-response 500')
        return response

    def by_id(rows):
        return {row['postId']: row for row in rows}

    def create(title, files=()):
        body, mime = f3.multipart({'title': title, 'content': 'F6 목록 본문', 'boardType': 'COMMUNICATION'}, files)
        return f3.expect(base, '/api/v1/posts', 200, 'POST',
            {'Authorization': 'Bearer ' + tokens['ROLE_USER'], 'Content-Type': mime}, body)['data']

    def read(post_id):
        return f3.expect(base, '/api/v1/posts/read/' + str(post_id), 200)['data']

    with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
        app = stub = None
        app_offset = 0
        try:
            app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
            result['startup_attempts'] = attempts
            prepare_business_rows()
            if listing() != []:
                raise AssertionError('All soft-deleted fixture rows must produce []')
            first = create('F6 첫 글', [('list.jpg', 'image/jpeg', b'F6-list-media')])
            second = create('F6 두번째 글')
            rows = listing()
            if set(by_id(rows)) != {first['postId'], second['postId']}:
                raise AssertionError('POST was not exposed in the full list')
            for item in rows:
                if item != read(item['postId']):
                    raise AssertionError('List and existing single GET differ')
            query = listing('/api/v1/posts/read?page=999&size=1&limit=0&boardType=REPORT&boardId=999&authorId=999',
                headers={'X-User-Id': '999', 'X-User-Role': 'ROLE_ADMIN'})
            if by_id(query) != by_id(rows):
                raise AssertionError('Irrelevant query or forged principal filtered the public list')
            body, mime = f3.multipart({'title': 'F6 수정 노출', 'content': '수정 본문'})
            f3.expect(base, '/api/v1/posts/' + str(first['postId']), 200, 'PUT',
                {'Authorization': 'Bearer ' + tokens['ROLE_USER'], 'Content-Type': mime}, body)
            current = read(first['postId'])
            if by_id(listing())[first['postId']] != current or current['title'] != 'F6 수정 노출':
                raise AssertionError('PUT was not reflected in list')

            # A missing author affects one item; neither author is taken from the viewer.
            f3.sql('UPDATE pawbridge_community.posts SET author_id=9600042 WHERE post_id=' + str(second['postId']))
            rows = by_id(listing())
            if rows[second['postId']]['authorNickname'] != '사용자9600042' or rows[first['postId']]['authorNickname'] != '목록 합성작성자':
                raise AssertionError('Missing author fallback was not item-local')
            f3.sql('REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader')
            try:
                for item in listing():
                    if item['authorNickname'] != '사용자' + str(item['authorId']):
                        raise AssertionError('Defined User SQL failure did not fall back per row')
            finally:
                f3.sql('GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader')
            if by_id(listing())[first['postId']]['authorNickname'] != '목록 합성작성자':
                raise AssertionError('List nickname SQL did not recover on the same pool')
            f3.sql('REVOKE SELECT(' + POST_COLUMNS + ') ON pawbridge_community.posts FROM pawbridge_f2_reader')
            try:
                listing(expected=500)
            finally:
                f3.sql('GRANT SELECT(' + POST_COLUMNS + ') ON pawbridge_community.posts TO pawbridge_f2_reader')
            if len(listing()) != 2:
                raise AssertionError('List posts SQL did not recover')
            # JSON is valid to PostgreSQL but invalid for the declared list-of-strings mapping.
            saved_images = f3.sql('SELECT image_urls::text FROM pawbridge_community.posts WHERE post_id=' + str(first['postId']))
            f3.sql("UPDATE pawbridge_community.posts SET image_urls='{\"invalid\":true}'::json WHERE post_id=" + str(first['postId']))
            try:
                listing(expected=500)
            finally:
                f3.sql('UPDATE pawbridge_community.posts SET image_urls=' + f4.literal(saved_images)
                    + '::json WHERE post_id=' + str(first['postId']))
            if by_id(listing())[first['postId']] != current:
                raise AssertionError('List mapping did not recover after restoring the row')
            f3.expect(base, '/api/v1/posts/' + str(first['postId']), 200, 'DELETE',
                {'Authorization': 'Bearer ' + tokens['ROLE_USER']})
            if first['postId'] in by_id(listing()):
                raise AssertionError('DELETE remained visible in list')
            f3.expect(base, '/api/v1/posts/read/' + str(first['postId']), 404)
            # The second fixture's author was intentionally changed; restore before owner deletion.
            f3.sql('UPDATE pawbridge_community.posts SET author_id=42 WHERE post_id=' + str(second['postId']))
            f3.expect(base, '/api/v1/posts/' + str(second['postId']), 200, 'DELETE',
                {'Authorization': 'Bearer ' + tokens['ROLE_USER']})
            if listing() != []:
                raise AssertionError('Deleting the remaining active post must return []')
            result['post_list_single_put_delete_and_recovery'] = 'passed'
        finally:
            try:
                if app is not None:
                    result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F6 packaged application')
            finally:
                if stub is not None:
                    result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F6 S3 stub')
                (EVIDENCE / 'f6-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    text = f4.fresh_log(app_path, app_offset)
    if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
        raise AssertionError('F6 normal HTTP/single-pool shutdown evidence missing')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN "
            "('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('F6 application connections remain after normal shutdown')
    result.update(status='passed', shutdown_pools=1, remaining_application_connections=0)
    (EVIDENCE / 'f6-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F6 whole-list journey, per-GET zero writes/S3, recovery and normal shutdown passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'smoke': smoke}[sys.argv[1]]()
