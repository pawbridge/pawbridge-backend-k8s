#!/usr/bin/env python3
"""Hosted-only comment deletion after the original F3-F9 ACL regression phase."""
import datetime
import json
import os
import pathlib
import sys
import xml.etree.ElementTree as ET

import f2_ci as f2
import f3_ci as f3
import f4_ci as f4
import f6_ci as f6
import f8_ci as f8
import f9_ci as f9

ROOT = f3.ROOT
EVIDENCE = f3.EVIDENCE
MARKER = 'pawbridge-f10-ci-only-v1'
POST = 9950001
FOREIGN_COMMENT = 9950010
NICKNAME = 'F10 합성 댓글작성자'
COMPATIBILITY_CLASS = 'com.pawbridge.backend.compatibility.PostgresqlCommentDeleteCompatibilityTest'


def verify_results():
    f2.require_hosted()
    counts = {}
    for task, zone in (('commentDeleteCompatibilityTest', 'UTC'),
                       ('commentDeleteCompatibilitySeoulTest', 'Asia/Seoul')):
        path = ROOT / 'boot/build/test-results' / task / ('TEST-' + COMPATIBILITY_CLASS + '.xml')
        try:
            suite = ET.parse(path).getroot()
            values = {key: int(suite.attrib[key]) for key in ('tests', 'failures', 'errors', 'skipped')}
        except (OSError, ET.ParseError, KeyError, ValueError):
            raise SystemExit('F10 compatibility report missing or malformed: ' + task)
        cases = suite.findall('testcase')
        if (suite.tag != 'testsuite' or suite.attrib.get('name') != COMPATIBILITY_CLASS
                or values['tests'] <= 0 or any(values[key] != 0 for key in ('failures', 'errors', 'skipped'))
                or len(cases) != values['tests']
                or any(case.attrib.get('classname') != COMPATIBILITY_CLASS for case in cases)):
            raise SystemExit('F10 compatibility report must contain executed, successful, unskipped tests: ' + task)
        counts[zone] = values['tests']
    print('Verified separate F10 compatibility execution counts: ' + json.dumps(counts, sort_keys=True))
    return counts


def require_fixture():
    # F9's exact original ACL remains strict. After this stage, reuse its identity
    # and negative-privilege checks with the explicitly extended comments matrix.
    owner = f8.require_fixture()
    for stage, marker in ((9, f9.MARKER), (10, MARKER)):
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
            "= (column_name IN ('deleted_at','updated_at'))) "
            "AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_community.comments','DELETE') "
            "AND NOT has_table_privilege('pawbridge_f3_writer','pawbridge_user.users','INSERT,UPDATE,DELETE') "
            "AND NOT has_schema_privilege('pawbridge_f3_writer','pawbridge_community','CREATE') "
            "AND NOT has_sequence_privilege('pawbridge_f3_writer','pawbridge_community.comments_comment_id_seq','USAGE,SELECT,UPDATE') "
            "AND (SELECT count(*)=1 FROM pg_constraint WHERE conrelid='pawbridge_community.comments'::regclass "
            "AND conname='ck_rollback_charset_comments' AND contype='c' AND convalidated) "
            "FROM information_schema.columns WHERE table_schema='pawbridge_community' AND table_name='comments'") != 't':
        raise SystemExit('F10 fixture lacks the exact extended comment ACL or validated charset constraint')
    return owner


def prepare():
    owner = f9.require_fixture()
    f2.export_environment({'F10_CI_OWNER': owner})
    f3.sql(input_text=(ROOT / 'scripts/f10_fixture.sql').read_text())
    require_fixture()
    print('Extended only the owned comment writer SELECT/UPDATE columns after strict F9 validation')


def prepare_business_rows():
    require_fixture()
    f3.sql("INSERT INTO pawbridge_user.users(user_id,nickname) VALUES(42," + f4.literal(NICKNAME)
           + ') ON CONFLICT(user_id) DO UPDATE SET nickname=EXCLUDED.nickname; '
           'INSERT INTO pawbridge_community.posts(post_id,author_id,title,content,board_type,'
           'image_urls,created_at,updated_at,deleted_at) VALUES'
           + ','.join('(' + str(POST + index) + ",9950043,'F10 합성 부모','F10 부모 본문','COMMUNICATION',"
                      "'[]','2025-01-01 01:02:03.123456','2025-01-02 01:02:03.123456',NULL)"
                      for index in range(3))
           + '; INSERT INTO pawbridge_community.comments(comment_id,post_id,author_id,content,'
           'created_at,updated_at,deleted_at) VALUES(' + str(FOREIGN_COMMENT) + ',' + str(POST)
           + ",9950043,'F10 다른 작성자 댓글','2025-01-01 01:02:03.123456','2025-01-02 01:02:03.123456',NULL)")


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
    app_env = dict(base_env, PAWBRIDGE_DB_URL=os.environ['F2_TEST_JDBC_URL'],
        PAWBRIDGE_DB_USER=f3.APP, PAWBRIDGE_DB_PASSWORD=os.environ['F3_TEST_WRITER_PASSWORD'],
        PAWBRIDGE_DB_POOL_MAX_SIZE='4',
        PAWBRIDGE_JWT_SECRET=os.environ['F3_TEST_JWT_SECRET'], PAWBRIDGE_STORAGE_BUCKET=bucket,
        PAWBRIDGE_STORAGE_PUBLIC_BASE_URL='https://fixture.invalid', PAWBRIDGE_STORAGE_OBJECT_PREFIX=prefix,
        PAWBRIDGE_STORAGE_ACCESS_KEY_ID='f3-synthetic-access', PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY='f3-synthetic-secret',
        PAWBRIDGE_STORAGE_REGION='us-east-1')
    result = {'status': 'incomplete', 'timezone': 'Asia/Seoul',
              'pool_maximum': 4,
              'comment_post_checks': 0, 'comment_get_checks': 0, 'comment_delete_checks': 0,
              'comments_deleted': 0, 'rejected_delete_checks': 0,
              'unrelated_database_changes': 0, 's3_requests': 0}
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    app_path, stub_path = EVIDENCE / 'f10-packaged-startup.log', EVIDENCE / 'f10-s3-stub.log'
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
            raise AssertionError('F10 request left a DB transaction active')

    def unchanged_neighbors(before, after):
        if ({key: rows for key, rows in before[0].items() if key != 'comments'}
                != {key: rows for key, rows in after[0].items() if key != 'comments'} or before[1:] != after[1:]):
            raise AssertionError('Comment request changed User/posts/F4 rows/outbox/search or contacted S3')

    def rows_by_id(snapshot_value):
        return {row['comment_id']: row for row in snapshot_value[0]['comments']}

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
        # Use F9 through HTTP, never its strict original-ACL helper after F10 preparation.
        before = snapshot()
        body = json.dumps({'content': 'F10_PRIVATE_BODY_DO_NOT_LOG'}).encode()
        response = f3.expect(base, '/api/v1/comments/posts/' + str(post_id), 200, 'POST',
                             {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tokens['ROLE_USER']}, body)
        after = snapshot()
        item = response['data']
        if (response['code'] != 200 or response['message'] != '댓글이 생성되었습니다.' or set(item) != f8.FIELDS
                or item['postId'] != post_id or item['authorId'] != 42 or item['authorNickname'] != NICKNAME
                or item['content'] != 'F10_PRIVATE_BODY_DO_NOT_LOG'):
            raise AssertionError('F9 HTTP creation contract changed inside the F10 journey')
        old, new = rows_by_id(before), rows_by_id(after)
        if (set(new) - set(old) != {item['commentId']} or any(new[key] != row for key, row in old.items())):
            raise AssertionError('F9 HTTP setup must add exactly one comment and preserve all previous rows')
        unchanged_neighbors(before, after)
        returned()
        if item['commentId'] not in read(post_id):
            raise AssertionError('New F9 comment was not publicly visible before deletion')
        result['comment_post_checks'] += 1
        return item['commentId']

    def delete(comment_id, expected=200, message=None, token='ROLE_USER', body=None):
        headers = {'Content-Type': 'application/json', 'X-User-Id': '9950999', 'X-User-Role': 'ROLE_ADMIN'}
        if token is not None:
            headers['Authorization'] = 'Bearer ' + tokens[token]
        before = snapshot()
        response = f3.expect(base, '/api/v1/comments/' + str(comment_id), expected, 'DELETE', headers, body)
        after = snapshot()
        result['comment_delete_checks'] += 1
        returned()
        if expected != 200:
            if after != before:
                raise AssertionError('Rejected comment DELETE changed DB or contacted S3')
            if response['code'] != expected or response['data'] is not None:
                raise AssertionError('Comment DELETE error did not retain a safe whole response')
            if message is not None and response['message'] != message:
                raise AssertionError('Comment DELETE failure message changed')
            result['rejected_delete_checks'] += 1
            return
        if response != {'code': 200, 'message': '댓글이 삭제되었습니다.', 'data': None}:
            raise AssertionError('Comment DELETE must retain HTTP/code200, original message and data:null')
        old, new = rows_by_id(before), rows_by_id(after)
        if set(old) != set(new) or any(new[key] != row for key, row in old.items() if key != comment_id):
            raise AssertionError('Comment DELETE physically removed a row or changed another comment')
        previous, current = old[comment_id], new[comment_id]
        if (previous['deleted_at'] is not None or current['deleted_at'] is None
                or current['updated_at'] == previous['updated_at']
                or {key: value for key, value in previous.items() if key not in ('deleted_at', 'updated_at')}
                != {key: value for key, value in current.items() if key not in ('deleted_at', 'updated_at')}):
            raise AssertionError('Comment DELETE must change only deletion/auditing timestamps and preserve stored fields')
        # Both timestamps are observed separately; equality between them is not a new invariant.
        for field in ('deleted_at', 'updated_at'):
            datetime.datetime.fromisoformat(current[field])
        unchanged_neighbors(before, after)
        if comment_id in read(previous['post_id']):
            raise AssertionError('Soft-deleted comment remained visible through F8 GET')
        result['comments_deleted'] += 1

    with app_path.open('w') as app_log, stub_path.open('w') as stub_log:
        app = stub = None
        app_offset = 0
        try:
            app, stub, base, endpoint, attempts, app_offset = f4.start_ready_children(
                jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path)
            result['startup_attempts'] = attempts
            prepare_business_rows()
            first = create()
            for token in (None, 'refresh', 'expired', 'forged', 'missingId', 'zeroId', 'negativeId',
                          'stringId', 'missingRole', 'numericRole'):
                delete(first, 401, token=token)
            delete(FOREIGN_COMMENT, 403, '댓글에 접근할 권한이 없습니다.', token='ROLE_ADMIN')
            for absent in (9950999, 0, -1):
                delete(absent, 404, '댓글을 찾을 수 없습니다.')
            for malformed in ('not-a-long', '9223372036854775808'):
                delete(malformed, 500, '서버 에러!')
            delete(first, body=b'{"ignored-invalid-json":')
            delete(first, 404, '댓글을 찾을 수 없습니다.')
            for token in ('ROLE_ADMIN', 'ROLE_SHELTER'):
                delete(create(), token=token, body=json.dumps({'content': '\U0001f600\x00'}).encode())

            deleted_parent = create(POST + 1)
            f3.sql('UPDATE pawbridge_community.posts SET deleted_at=CURRENT_TIMESTAMP WHERE post_id=' + str(POST + 1))
            delete(deleted_parent)
            absent_parent = create(POST + 2)
            f3.sql('DELETE FROM pawbridge_community.posts WHERE post_id=' + str(POST + 2))
            delete(absent_parent)

            independent = create()
            # REVOKE/GRANT SELECT must be standalone statements so f3.sql also revokes/restores the
            # overlapping writer-role privilege inherited by the single shared app login.
            f3.sql('DELETE FROM pawbridge_user.users WHERE user_id=42')
            f3.sql('REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader; '
                   'REVOKE SELECT(' + f6.POST_COLUMNS + ') ON pawbridge_community.posts FROM pawbridge_f2_reader')
            try:
                delete(independent)
            finally:
                f3.sql('GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader; '
                       'GRANT SELECT(' + f6.POST_COLUMNS + ') ON pawbridge_community.posts TO pawbridge_f2_reader')
                f3.sql('INSERT INTO pawbridge_user.users(user_id,nickname) VALUES(42,' + f4.literal(NICKNAME) + ')')

            for privilege in ('SELECT(author_id)', 'SELECT(deleted_at)', 'UPDATE(deleted_at)', 'UPDATE(updated_at)'):
                comment_id = create()
                f3.sql('REVOKE ' + privilege + ' ON pawbridge_community.comments FROM pawbridge_f3_writer')
                try:
                    delete(comment_id, 500, '서버 에러!')
                    delete(comment_id, 401, token=None)
                finally:
                    f3.sql('GRANT ' + privilege + ' ON pawbridge_community.comments TO pawbridge_f3_writer')
                delete(comment_id)
            result['post_get_delete_author_parent_body_permissions_and_recovery'] = 'passed'
        finally:
            try:
                if app is not None:
                    result['app_sigterm_exit'] = f3.stop_owned_child(app, 'F10 packaged application')
            finally:
                if stub is not None:
                    result['stub_sigterm_exit'] = f3.stop_owned_child(stub, 'F10 S3 stub')
                (EVIDENCE / 'f10-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    if app_path.stat().st_size > 1024 * 1024 or stub_path.stat().st_size > 1024 * 1024:
        raise AssertionError('F10 bounded journey exceeded its one-MiB-per-child log budget')
    text = f4.fresh_log(app_path, app_offset)
    if text.count('Shutdown completed.') < 1 or 'Graceful shutdown complete' not in text:
        raise AssertionError('F10 normal HTTP/single-pool shutdown evidence missing')
    if any(value in text for value in (os.environ['F2_TEST_DB_PASSWORD'], os.environ['F3_TEST_WRITER_PASSWORD'],
            os.environ['F3_TEST_JWT_SECRET'], 'F10_PRIVATE_BODY_DO_NOT_LOG', *tokens.values())):
        raise AssertionError('F10 application log exposed credentials, tokens or request content')
    if f3.sql("SELECT count(*) FROM pg_stat_activity WHERE usename IN "
            "('pawbridge_app','pawbridge_f2_reader','pawbridge_f3_writer')") != '0':
        raise AssertionError('F10 application connections remain after normal shutdown')
    result.update(status='passed', shutdown_pools=1, remaining_application_connections=0)
    (EVIDENCE / 'f10-packaged-result.txt').write_text(json.dumps(result, indent=2) + '\n')
    print('Hosted F10 actual-JAR POST/GET/DELETE, two-column soft delete, rollback recovery and shutdown passed')


if __name__ == '__main__':
    f2.require_hosted()
    {'prepare': prepare, 'results': verify_results, 'smoke': smoke}[sys.argv[1]]()
