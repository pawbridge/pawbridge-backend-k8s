#!/usr/bin/env python3
"""Late member-runtime control/delta guards only; no PG/JAR/JVM/socket execution.

Baseline imports are inert. Full mock journey is control-flow evidence, not an independent
business check: its simulated commits use the declared phase projection.
"""
import base64
import copy
from contextlib import contextmanager
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

HERE = Path(__file__).resolve().parent
BASE = Path('/Users/shyu/Documents/msa-projects/worktrees/pawbridge-backend/note-snapshot-batching/backend-app/scripts')
# Root's minimal checkout puts all inherited scripts beside these files; local private input is sparse.
sys.path[:0] = [str(HERE)] + ([str(BASE)] if BASE.is_dir() else [])
import f29_ci as ci
import f29_stream_probe as probe


class Stream:
    instances = []
    def __init__(self, base, headers):
        self.condition = threading.Condition(); self.eof = self.failed = self.closed = False
        self.events = {'ready': 1, 'resync': 0, 'heartbeat': 0}; self.instances.append(self)
    def wait(self, name, count=1):
        if self.events[name] != count: raise AssertionError('Mock event mismatch')
    def assert_resync(self, count):
        if self.failed or self.events['resync'] != count: raise AssertionError('Mock resync mismatch')
    def wait_eof(self):
        if not self.eof: raise AssertionError('Mock server EOF missing')
    def close(self): self.closed = True


class FakeCi:
    SNAPSHOT_TABLES, MEMBER_TABLES = ci.SNAPSHOT_TABLES, ci.MEMBER_TABLES
    SOURCE, NOTES, MAILBOXES, REQUESTS = ci.SOURCE, ci.NOTES, ci.MAILBOXES, ci.REQUESTS
    def __init__(self, folder):
        self.ROOT = Path(folder).resolve() / 'backend-app'; self.EVIDENCE = self.ROOT / 'build/ci'; self.EVIDENCE.mkdir(parents=True)
        jar = self.ROOT / 'boot/build/libs/pawbridge-backend-app.jar'; jar.parent.mkdir(parents=True); jar.write_bytes(b'mock-only')
        self.baseline = {name: [] for name, _, _ in self.SNAPSHOT_TABLES + self.MEMBER_TABLES}
        self.baseline['users'] = [{'user_id': 42, 'nickname': 'protected'}]
        self.state = copy.deepcopy(self.baseline); self.sql = []; self.requests = []; self.stopped = []; self.events = []
        self.pending = None; self.business = 0; self.bad_cache = False; self.stale_after_seed = False; self.stale = False
        self.fail_seed_ack = False; self.fail_after_community = False
        self.log_private_input = False; self.stop_fails = False; self.late_writer = None; self.raw_paths = ()
        self.f2 = Mock(); self.f2.require_hosted.return_value = '1-1'
        self.f3 = Mock(); self.f3.WRITER = 'pawbridge_f3_writer'; self.f3.request.side_effect = self.request
        self.f3.expect.return_value = {'counts': {'GET': 0}, 'objects': [], 'requests': []}
        self.f3.stop_owned_child.side_effect = self.stop
        self.f4 = Mock(); self.f4.start_ready_children.side_effect = self.start
        self.f4.fresh_log.side_effect = lambda path, offset: path.read_text()
    def require_fixture(self): raise AssertionError('Old eighteen-table strict must not be called')
    database_snapshot = require_fixture
    def member_require_fixture(self): self.events.append('strict24'); return '1-1'
    def member_require_identity(self): self.events.append('identity24'); return '1-1'
    def member_database_snapshot(self):
        self.events.append('snapshot24')
        if self.stale_after_seed and len(Stream.instances) == 2: self.stale = True
        return copy.deepcopy(self.state)
    def phase(self, phase):
        owned = dict(baseline=self.baseline, seed_rows=probe.member_seed_rows(), pending=self.pending)
        return probe.member_expected(self, owned, phase)
    def owned_sql(self, query='', *, input_text=None):
        self.sql.append(input_text if input_text is not None else query)
        if input_text and 'INSERT INTO' in input_text:
            self.state = self.phase('seed')
            if self.fail_seed_ack: raise SystemExit('Safe lost seed acknowledgement')
        elif input_text and 'DELETE FROM' in input_text:
            self.state = copy.deepcopy(self.baseline)
        return '0'
    @contextmanager
    def member_denied_privilege(self, kind):
        self.events.append('deny-' + kind)
        try: yield
        finally: self.events.extend(['identity24', 'restore-' + kind, 'strict24'])
    def start(self, jar, app_env, base_env, owner, bucket, prefix, stub_log, app_log, stub_path, app_path):
        assert app_env['MEMBER_CHAT_ENABLED'] == app_env['PAWBRIDGE_CHAT_ENABLED'] == 'false'
        self.raw_paths = (app_path, stub_path)
        if self.stop_fails: self.late_writer = os.dup(app_log.fileno())
        if self.log_private_input: app_log.write(probe.MEMBER_BODY + '\n')
        app_log.write('Start completed.\nShutdown completed.\nGraceful shutdown complete\nPrivate note stream resources stopped\n'); app_log.flush()
        return 'app', 'stub', 'mock-base', 'mock-storage', 1, 0
    def stop(self, child, label):
        self.stopped.append(child)
        if child == 'app':
            if self.stop_fails: raise RuntimeError('Controlled child stop failure')
            for stream in Stream.instances: stream.eof = True
        return 143
    def request(self, base, path, *, method, headers):
        self.requests.append((method, path, dict(headers)))
        bearer = headers.get('Authorization')
        cache = probe.prior.SECURITY_CACHE
        if method == 'DELETE':
            if bearer is None: status, msg = 401, '인증 토큰이 필요합니다.'
            elif bearer == 'Bearer other': status, msg = 403, '관리자 권한이 필요합니다.'
            else:
                self.business += 1
                if self.business == 1:
                    self.pending = {'user_id': probe.CALLER, 'started_at': datetime_now()}
                    self.state = self.phase('pending'); status, msg = 503, '쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요.'
                elif self.business == 2:
                    self.state = self.phase('community'); Stream.instances[0].eof = True
                    Stream.instances[1].events['resync'] = 1; status, msg = 500, '서버 에러!'
                    if self.fail_after_community: raise SystemExit('Safe response transport failure')
                else:
                    self.state = self.phase('final'); Stream.instances[1].events['resync'] = 2
                    status, msg = 200, '회원이 삭제되었습니다.'
            value = {'code': status, 'message': msg, 'data': None}
        elif path.endswith('/stream'):
            assert self.business == 2 and any(r['user_id'] == probe.CALLER for r in self.state['users'])
            status, value, cache = 403, {'message': '이용할 수 없는 회원입니다.'}, 'no-store'
        else:
            status, cache = 200, 'no-store'
            value = {'code': 200, 'message': probe.SUCCESS, 'data': dict(noteId=probe.MEMBER_NOTE_A,
                body=probe.MEMBER_BODY, direction='INBOX', createdAt=probe.MEMBER_FIRST, readAt=None, favorite=False,
                counterpartId=None, counterpartNickname='탈퇴한 회원', canReply=False, contextHref=None)}
        if self.bad_cache and self.business == 2: cache = 'wrong'
        return status, json.dumps(value).encode(), {'Cache-Control': cache}


def datetime_now():
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat()


class MemberRuntimeTest(unittest.TestCase):
    def setUp(self):
        Stream.instances = []
        self.folder = tempfile.TemporaryDirectory(); self.addCleanup(self.folder.cleanup)
        self.fake = FakeCi(Path(self.folder.name) / 'workspace')
        runner = Path(self.folder.name).resolve() / 'runner-temp'; runner.mkdir(mode=0o700)
        self.env = patch.dict(os.environ, {'GITHUB_SHA': '1' * 40, 'F2_TEST_DB_PASSWORD': 'reader-secret',
            'F3_TEST_WRITER_PASSWORD': 'writer-secret', 'F3_TEST_JWT_SECRET': 'issuer-secret-value' * 3,
            'RUNNER_TEMP': str(runner)})
        self.env.start(); self.addCleanup(self.env.stop)
    def run_journey(self):
        def tokens(_):
            self.fake.events.append('tokens')
            if self.fake.stale: raise SystemExit('Safe stale normal issuer')
            return {'F19_ADMIN': 'admin', 'F29_CALLER': 'victim', 'F29_OTHER': 'other'}
        with patch.object(probe, 'member_load_tokens', side_effect=tokens), patch.object(probe.transport, 'Stream', Stream), \
             patch.object(probe.transport, 'close_streams', side_effect=lambda streams: [s.close() for s in streams]), \
             patch.object(probe.transport, '_app_environment', return_value=({}, {})), patch('builtins.print'):
            probe.run_member_cleanup(self.fake)
    def receipt(self): return json.loads((self.fake.EVIDENCE / 'member-cleanup-packaged-result.txt').read_text())
    def owned(self, phase='seed'):
        return dict(baseline=copy.deepcopy(self.fake.baseline), seed_rows=probe.member_seed_rows(), pending=self.fake.pending,
                    reserved=True, ack=True, started_ns=time.time_ns() - 10_000_000_000)

    def test_given_pending_commit_and_two_transaction_failures__when_running_late_journey__then_keep_exact_nine_requests_and_restore_twenty_four_tables(self):
        self.run_journey(); result = self.receipt()
        self.assertEqual({k: result[k] for k in probe.MEMBER_COUNTERS}, probe.MEMBER_EXPECTED_COUNTS)
        self.assertEqual(result['status'], 'passed'); self.assertEqual(len(self.fake.requests), 7)  # SSE2 are separate Stream constructors.
        self.assertEqual(self.fake.state, self.fake.baseline); self.assertEqual(self.fake.stopped, ['app', 'stub'])
        self.assertTrue(all(s.closed for s in Stream.instances)); self.assertEqual(result['survivor_resync_checks'], 2)
        reconnect = self.fake.requests[4]; self.assertEqual(reconnect[:2], ('GET', '/api/v1/notes/stream'))
        self.assertEqual(result['child_log_evidence'], 'safe-copied')
        for path in self.fake.raw_paths:
            self.assertNotIn(self.fake.ROOT, path.parents); self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(path.parent.stat().st_mode & 0o777, 0o700)

    def test_given_slow_snapshot_makes_used_tokens_stale__when_request_would_start__then_authenticated_request_zero_and_stop_both_children_and_cleanup(self):
        self.fake.stale_after_seed = True
        with self.assertRaisesRegex(SystemExit, 'stale'): self.run_journey()
        self.assertEqual(len(self.fake.requests), 1)  # Missing-JWT401 must not acquire a TTL prerequisite.
        self.assertNotIn('Authorization', self.fake.requests[0][2]); self.assertEqual(self.fake.stopped, ['app', 'stub'])
        self.assertEqual(self.fake.state, self.fake.baseline); self.assertEqual(self.receipt()['status'], 'incomplete')
        self.assertTrue(all(s.closed for s in Stream.instances))
        index = max(i for i, event in enumerate(self.fake.events) if event == 'tokens')
        self.assertEqual(self.fake.events[index - 1], 'snapshot24')

    def test_given_private_input_and_optional_cache_failure_after_commit__when_aborting__then_publish_no_raw_private_log_and_never_mark_passed(self):
        for cache_failure in (True, False):
            with self.subTest(cache_failure=cache_failure):
                self.fake = FakeCi(Path(self.folder.name) / ('leak-' + str(cache_failure)))
                runner = Path(self.folder.name).resolve() / ('leak-runner-' + str(cache_failure)); runner.mkdir(mode=0o700)
                self.fake.bad_cache = cache_failure; self.fake.log_private_input = True
                with patch.dict(os.environ, {'RUNNER_TEMP': str(runner)}):
                    with self.assertRaisesRegex(AssertionError, 'status/cache' if cache_failure else 'private or unconfirmed'):
                        self.run_journey()
                self.assertEqual(self.fake.state, self.fake.baseline); self.assertEqual(self.receipt()['status'], 'incomplete')
                self.assertEqual(self.receipt()['child_log_evidence'], 'private-input-refused')
                self.assertEqual(self.fake.stopped, ['app', 'stub']); self.assertTrue(all(s.closed for s in Stream.instances))
                self.assertIn(probe.MEMBER_BODY, self.fake.raw_paths[0].read_text())
                self.assertFalse((self.fake.EVIDENCE / 'member-cleanup-packaged-startup.log').exists())
                for path in self.fake.EVIDENCE.glob('*'):
                    if path.is_file(): self.assertNotIn(probe.MEMBER_BODY, path.read_text())
                cleanup = next(q for q in self.fake.sql if 'DELETE FROM' in q)
                self.assertIn(probe.MEMBER_ROOM, cleanup); self.assertIn(probe.MEMBER_NOTE_A, cleanup)
                self.assertNotIn('sender_id IS NULL', cleanup)
                Stream.instances = []

    def test_given_unconfirmed_child_stop__when_original_fd_writes_later__then_raw_bytes_stay_outside_artifacts_and_result_is_incomplete(self):
        self.fake.stop_fails = True
        try:
            with self.assertRaisesRegex(RuntimeError, 'Controlled child stop failure'): self.run_journey()
            self.assertEqual(self.receipt()['status'], 'incomplete')
            self.assertEqual(self.receipt()['child_log_evidence'], 'unconfirmed-stop')
            self.assertEqual(self.fake.stopped, ['app', 'app', 'stub'])
            self.assertEqual(self.fake.state, self.fake.baseline); self.assertTrue(all(s.closed for s in Stream.instances))
            os.write(self.fake.late_writer, probe.MEMBER_BODY.encode())  # Simulate a still-writing owned child FD, not a process.
            self.assertIn(probe.MEMBER_BODY, self.fake.raw_paths[0].read_text())
            self.assertEqual(list(self.fake.EVIDENCE.glob('*.log')), [])
            for path in self.fake.EVIDENCE.glob('*'):
                if path.is_file(): self.assertNotIn(probe.MEMBER_BODY, path.read_text())
        finally:
            if self.fake.late_writer is not None: os.close(self.fake.late_writer)

    def test_given_transport_failure_after_community_commit__when_no_response_arrives__then_reobserve_committed_rows_and_restore_owned_namespace(self):
        self.fake.fail_after_community = True
        with self.assertRaisesRegex(SystemExit, 'transport'): self.run_journey()
        self.assertEqual(self.fake.state, self.fake.baseline); self.assertEqual(self.receipt()['status'], 'incomplete')
        self.assertEqual(self.fake.stopped, ['app', 'stub'])

    def test_given_seed_commit_acknowledgement_lost__when_aborting_before_streams__then_recognize_only_exact_atomic_seed_and_cleanup(self):
        self.fake.fail_seed_ack = True
        with self.assertRaisesRegex(SystemExit, 'seed acknowledgement'): self.run_journey()
        self.assertEqual(self.fake.state, self.fake.baseline); self.assertEqual(Stream.instances, [])
        self.assertEqual(self.fake.stopped, ['app', 'stub']); self.assertEqual(self.receipt()['status'], 'incomplete')

    def test_given_unknown_reply_or_chat_reference_or_foreign_column_change__when_cleanup_runs__then_reject_before_any_delete(self):
        for table, row in (('private_notes', {'note_id': 'external', 'reply_to': probe.MEMBER_NOTE_A}),
                           ('chat_requests', {'sender_id': 42, 'request_id': 'foreign', 'room_id': probe.MEMBER_ROOM}),
                           ('users', {'user_id': 43, 'nickname': 'external'})):
            with self.subTest(table=table):
                self.fake.state = self.fake.phase('seed'); self.fake.state[table].append(row); self.fake.sql = []
                with self.assertRaisesRegex(SystemExit, 'externally referenced'): probe.member_cleanup_owned(self.fake, self.owned())
                self.assertFalse(any('DELETE FROM' in q for q in self.fake.sql))

    def test_given_same_identifiers_but_changed_private_cells__when_phase_is_checked__then_reject_unapproved_delta(self):
        self.fake.pending = {'user_id': probe.CALLER, 'started_at': datetime_now()}
        for table, field, value in (('private_notes', 'body', 'changed'), ('chat_rooms', 'latest_sequence', 2),
                                    ('private_note_mailboxes', 'favorite', True), ('refresh_tokens', 'token', 'changed')):
            with self.subTest(table=table):
                state = self.fake.phase('community'); state[table][0][field] = value
                with self.assertRaisesRegex(AssertionError, 'protected row'): probe.member_assert_state(self.fake, self.owned(), state, 'community')

    def test_given_reserved_identity_or_uuid_in_existing_foreign_rows__when_seed_is_considered__then_refuse_instead_of_claiming_ownership(self):
        for table, row in (('users', {'user_id': probe.CALLER}),
                           ('private_notes', {'note_id': 'external', 'reply_to': probe.MEMBER_NOTE_A}),
                           ('chat_messages', {'room_id': probe.MEMBER_ROOM, 'sender_id': None}),
                           ('users', {'user_id': probe.MEMBER_ADMIN})):
            with self.subTest(table=table):
                state = copy.deepcopy(self.fake.baseline); state[table].append(row)
                with self.assertRaises(SystemExit): probe.member_reserved_free(state)

    def test_given_actual_member_pending_marker__when_retry_is_observed__then_preserve_original_time_and_reject_wrong_identity_or_time(self):
        owned = self.owned(); row = {'user_id': probe.CALLER, 'started_at': datetime_now()}
        state = copy.deepcopy(self.fake.baseline); state['contact_deletions'] = [row]
        started = time.time_ns(); probe.member_observe_pending(owned, state, started, time.time_ns())
        probe.member_observe_pending(owned, state, started, time.time_ns())
        state['contact_deletions'][0]['started_at'] = '1999-01-01T00:00:00Z'
        with self.assertRaisesRegex(AssertionError, 'replaced'): probe.member_observe_pending(owned, state, started, time.time_ns())

    def test_given_denied_cleanup_privilege_and_body_failure__when_restoring__then_identity_before_grant_and_strict_afterward(self):
        for failure in ('body', 'revoke-ack'):
            with self.subTest(failure=failure):
                events = []
                def sql(query):
                    events.append(query)
                    if failure == 'revoke-ack' and query.startswith('REVOKE '): raise RuntimeError('controlled')
                with patch.object(ci, 'member_require_fixture', side_effect=lambda: events.append('strict')), \
                     patch.object(ci, 'member_require_identity', side_effect=lambda: events.append('identity')), \
                     patch.object(ci, 'owned_sql', side_effect=sql):
                    with self.assertRaisesRegex(RuntimeError, 'controlled'):
                        with ci.member_denied_privilege('chat-update'): raise RuntimeError('controlled')
                self.assertEqual(events, ['strict', 'REVOKE UPDATE(sender_id) ON pawbridge_community.member_chat_messages FROM pawbridge_f3_writer',
                    'identity', 'GRANT UPDATE(sender_id) ON pawbridge_community.member_chat_messages TO pawbridge_f3_writer', 'strict'])

    def test_given_wrong_owner_during_denial__when_restoration_is_attempted__then_never_grant_or_weaken_identity(self):
        sql = Mock()
        with patch.object(ci, 'member_require_fixture'), patch.object(ci, 'member_require_identity', side_effect=SystemExit('wrong owner')), \
             patch.object(ci, 'owned_sql', sql):
            with self.assertRaisesRegex(SystemExit, 'wrong owner'):
                with ci.member_denied_privilege('user-delete'): pass
        self.assertEqual(sql.call_count, 1); self.assertTrue(sql.call_args.args[0].startswith('REVOKE '))

    def test_given_three_used_issuer_keys_and_unused_expired_key__when_loading__then_validate_only_used_role_identity_and_ttl_from_regular_owned_file(self):
        secret = 's' * 64; owner = '1-1'; token_path = self.fake.ROOT / ('f3-tokens-' + owner + '.json')
        def token(identity, role, expiry):
            encode = lambda value: base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).decode().rstrip('=')
            data = encode({'alg': 'HS256', 'typ': 'JWT'}) + '.' + encode({'userId': identity, 'role': role, 'exp': expiry})
            return data + '.' + base64.urlsafe_b64encode(hmac.new(secret.encode(), data.encode(), hashlib.sha256).digest()).decode().rstrip('=')
        values = {'unused-old': 'expired-ignored', 'F19_ADMIN': token(probe.MEMBER_ADMIN, 'ROLE_ADMIN', time.time() + 600),
            'F29_CALLER': token(probe.CALLER, 'ROLE_USER', time.time() + 600), 'F29_OTHER': token(probe.OTHER, 'ROLE_USER', time.time() + 600)}
        token_path.write_text(json.dumps(values)); token_path.chmod(0o600)
        with patch.dict(os.environ, {'RUNNER_TEMP': str(self.fake.ROOT), 'F3_TEST_TOKENS_PATH': str(token_path), 'F3_TEST_JWT_SECRET': secret}):
            self.assertEqual(set(probe.member_load_tokens(self.fake)), {'F19_ADMIN', 'F29_CALLER', 'F29_OTHER'})
            self.assertEqual(json.loads(token_path.read_text()), values)
            for kind in ('wrong-role', 'stale', 'mode'):
                modified = dict(values)
                if kind == 'wrong-role': modified['F19_ADMIN'] = token(probe.MEMBER_ADMIN, 'ROLE_USER', time.time() + 600)
                if kind == 'stale': modified['F29_CALLER'] = token(probe.CALLER, 'ROLE_USER', time.time() + 59)
                token_path.write_text(json.dumps(modified)); token_path.chmod(0o644 if kind == 'mode' else 0o600)
                with self.assertRaises(SystemExit): probe.member_load_tokens(self.fake)


if __name__ == '__main__':
    unittest.main()
