#!/usr/bin/env python3
"""Pure standard-library guards for the runtime probe, with no network/JVM/PG.

The memory stream and recording CI doubles verify probe controls, not production
SSE delivery, SQL semantics, transactions, socket EOF or issuer compatibility.
"""
import base64
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('f26_runtime_probe_under_test', Path(__file__).with_name('f26_stream_probe.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)

KEY = 'F26-unit-only-key-' * 4
NOW = 1800000000


def signed(identity=probe.CALLER, **extra):
    header = {'alg': 'HS512'}
    claims = {'userId': identity, 'role': 'ROLE_USER', 'exp': NOW + 600, **extra}
    parts = [probe._encode(json.dumps(x, separators=(',', ':')).encode()) for x in (header, claims)]
    parts.append(probe._encode(hmac.new(KEY.encode(), '.'.join(parts).encode(), hashlib.sha512).digest()))
    return '.'.join(parts)


def memory_stream(raw):
    stream = probe.Stream.__new__(probe.Stream)
    stream.condition = threading.Condition()
    stream.events = dict.fromkeys(('ready', 'resync', 'heartbeat'), 0)
    stream.bytes = 0
    stream.eof = stream.failed = stream.closed = False
    stream.response = io.BytesIO(raw)
    stream.connection = Mock()
    stream.socket = None
    stream.thread = None
    return stream


class F26StreamProbeTest(unittest.TestCase):
    def test_actual_issuer_signature_identity_role_and_freshness_are_checked(self):
        with patch.object(probe.time, 'time', return_value=NOW):
            self.assertEqual(probe._verified_token(signed(), probe.CALLER, KEY, NOW)[1]['userId'], probe.CALLER)
            cases = (signed(identity=probe.OTHER), signed(role='ROLE_ADMIN'), signed(userId=True),
                     signed(exp=NOW + 59), signed(exp=float('nan')), signed(exp='later'))
            for token in cases:
                with self.subTest(token_kind='invalid claim'), self.assertRaises(SystemExit):
                    probe._verified_token(token, probe.CALLER, KEY, NOW)
            with self.assertRaises(SystemExit):
                probe._verified_token(signed(), probe.CALLER, 'another key' * 8, NOW)

    def test_owned_regular_0600_token_file_keeps_old_keys_and_loads_only_f26(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'f3-tokens-42-1.json'
            values = {'F26_CALLER': signed(), 'F26_OTHER': signed(probe.OTHER), 'old_fixture': 'preserved'}
            path.write_text(json.dumps(values)); path.chmod(0o600)
            ci = types.SimpleNamespace(f2=types.SimpleNamespace(require_hosted=lambda: '42-1'))
            env = {'RUNNER_TEMP': directory, 'F3_TEST_TOKENS_PATH': str(path), 'F3_TEST_JWT_SECRET': KEY}
            with patch.dict(os.environ, env), patch.object(probe.time, 'time', return_value=NOW):
                self.assertEqual(set(probe.load_tokens(ci)), {'F26_CALLER', 'F26_OTHER'})
            self.assertEqual(json.loads(path.read_text()), values)

    def test_token_input_rejects_wrong_owner_permissions_and_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'f3-tokens-42-1.json'
            path.write_text(json.dumps({'F26_CALLER': signed(), 'F26_OTHER': signed(probe.OTHER)}))
            ci = types.SimpleNamespace(f2=types.SimpleNamespace(require_hosted=lambda: '42-1'))
            env = {'RUNNER_TEMP': directory, 'F3_TEST_TOKENS_PATH': str(path), 'F3_TEST_JWT_SECRET': KEY}
            with patch.dict(os.environ, env), patch.object(probe.time, 'time', return_value=NOW):
                path.chmod(0o644)
                with self.assertRaises(SystemExit): probe.load_tokens(ci)
                path.chmod(0o600)
                link = Path(directory) / 'alias.json'; link.symlink_to(path)
                with patch.dict(os.environ, {'F3_TEST_TOKENS_PATH': str(link)}), self.assertRaises(SystemExit): probe.load_tokens(ci)
                with patch.dict(os.environ, {'RUNNER_TEMP': str(Path(directory) / 'different')}), self.assertRaises(SystemExit): probe.load_tokens(ci)

    def test_crafted_expiry_variants_remain_memory_inputs_from_verified_normal_claims(self):
        normal = signed()
        with patch.object(probe, 'load_tokens', return_value={'F26_CALLER': normal}) as load, patch.dict(os.environ, {'F3_TEST_JWT_SECRET': KEY}), patch.object(probe.time, 'time', return_value=NOW):
            for kind, expected in (('short', NOW + 5), ('expired', NOW - 5), ('no_exp', None)):
                token = probe.crafted_input(object(), kind)
                claims = json.loads(probe._decode(token.split('.')[1]))
                self.assertEqual(claims.get('exp'), expected)
                self.assertEqual(claims['userId'], probe.CALLER)
                self.assertEqual(claims['role'], 'ROLE_USER')
            self.assertEqual(load.call_count, 3)
            self.assertEqual(json.loads(probe._decode(normal.split('.')[1]))['exp'], NOW + 600)
            with self.assertRaises(ValueError): probe.crafted_input(object(), 'arbitrary')

    def test_timestamp_requires_z_on_wire_and_at_most_microseconds(self):
        self.assertEqual(probe.timestamp('2026-10-20T00:00:00.123456Z', True), probe.timestamp('2026-10-20T09:00:00.123456+09:00'))
        for value in ('2026-10-20T09:00:00+09:00', '2026-10-20T00:00:00.123456789Z', None):
            with self.subTest(value=value), self.assertRaises(AssertionError): probe.timestamp(value, True)

    def test_exact_first_read_delta_leaves_all_other_rows_and_input_snapshot_unchanged(self):
        before = {'private_note_mailboxes': [dict(member_id=probe.CALLER, note_id=probe.INBOX, direction='INBOX', read_at=None)], 'unrelated': [dict(value=3)]}
        after = copy.deepcopy(before); after['private_note_mailboxes'][0]['read_at'] = '2026-10-20T00:00:00.123456+00:00'
        now = datetime(2026, 10, 20, tzinfo=timezone.utc)
        self.assertEqual(probe.expected_delta(before, after, probe.INBOX, now, now + timedelta(seconds=1)), after['private_note_mailboxes'][0]['read_at'])
        self.assertIsNone(before['private_note_mailboxes'][0]['read_at'])
        after['unrelated'][0]['value'] = 4
        with self.assertRaises(AssertionError): probe.expected_delta(before, after, probe.INBOX, now, now + timedelta(seconds=1))

    def test_read_delta_refuses_sent_repeat_and_outside_request_clock(self):
        now = datetime(2026, 10, 20, tzinfo=timezone.utc)
        base = dict(member_id=probe.CALLER, note_id=probe.INBOX, direction='INBOX', read_at=None)
        for change in ({'direction': 'SENT'}, {'read_at': '2026-10-20T00:00:00Z'}, {}):
            before = {'private_note_mailboxes': [{**base, **change}]}
            after = copy.deepcopy(before); after['private_note_mailboxes'][0]['read_at'] = '2026-10-19T00:00:00Z'
            with self.subTest(kind=tuple(change)), self.assertRaises(AssertionError): probe.expected_delta(before, after, probe.INBOX, now, now)

    def test_seed_is_reserved_absent_atomic_unique_users_and_three_owned_mailboxes(self):
        ci = Mock(); ci.NOTES = 'pawbridge_community.private_notes'; ci.MAILBOXES = 'pawbridge_community.private_note_mailboxes'
        ci.owned_sql.return_value = '0'
        owned = probe.seed(ci)
        self.assertEqual(owned['users'], probe.USERS)
        sql = ci.owned_sql.call_args.args[0]
        self.assertTrue(sql.startswith('BEGIN;')); self.assertTrue(sql.endswith('COMMIT;'))
        for identity in probe.USERS:
            self.assertIn('f26-' + str(identity) + '@fixture.invalid', sql)
            self.assertIn(probe.NAMES[identity], sql)
        for note in probe.NOTE_IDS: self.assertEqual(sql.count(note), 2)
        self.assertNotIn('CREATE ', sql); self.assertNotIn('TRUNCATE ', sql)
        self.assertIn('private_note_blocks', ci.owned_sql.call_args_list[0].args[0])

    def test_seed_collision_never_writes_or_returns_ownership(self):
        ci = Mock(); ci.NOTES = 'notes'; ci.MAILBOXES = 'mailboxes'; ci.owned_sql.return_value = '1'
        with self.assertRaises(SystemExit): probe.seed(ci)
        self.assertEqual(ci.owned_sql.call_count, 1)
        self.assertTrue(ci.owned_sql.call_args.args[0].startswith('SELECT '))

    def test_cleanup_reobserves_after_request_failure_and_deletes_only_exact_fk_order(self):
        ci = self.cleanup_ci()
        probe.cleanup(ci, {'users': probe.USERS, 'notes': probe.NOTE_IDS})
        ci.database_snapshot.assert_called_once()
        sql = ci.owned_sql.call_args.args[0]
        self.assertLess(sql.index('DELETE FROM mailboxes'), sql.index('DELETE FROM notes'))
        self.assertLess(sql.index('DELETE FROM notes'), sql.index('DELETE FROM pawbridge_community.private_note_members'))
        self.assertLess(sql.index('DELETE FROM pawbridge_community.private_note_members'), sql.index('DELETE FROM pawbridge_user.users'))
        self.assertTrue(sql.endswith('COMMIT;'))
        for note in probe.NOTE_IDS: self.assertIn(note, sql)

    def cleanup_ci(self):
        ci = Mock(); ci.NOTES = 'notes'; ci.MAILBOXES = 'mailboxes'
        ci.database_snapshot.return_value = {
            'private_notes': [dict(note_id=n, sender_id=probe.CALLER if n == probe.SENT else probe.OTHER,
                                   recipient_id=probe.OTHER if n == probe.SENT else probe.CALLER, body=probe.BODY) for n in probe.NOTE_IDS],
            'private_note_mailboxes': [dict(member_id=probe.CALLER, note_id=n, read_at='after-partial-success') for n in probe.NOTE_IDS]}
        ci.owned_sql.side_effect = ['0', '2', '']
        return ci

    def test_cleanup_refuses_unacknowledged_or_unexpected_foreign_reference(self):
        ci = self.cleanup_ci()
        with self.assertRaises(ValueError): probe.cleanup(ci, {'users': (), 'notes': probe.NOTE_IDS})
        ci.owned_sql.assert_not_called()
        ci.owned_sql.side_effect = ['1']
        with self.assertRaises(SystemExit): probe.cleanup(ci, {'users': probe.USERS, 'notes': probe.NOTE_IDS})
        self.assertTrue(all(call.args[0].startswith('SELECT ') for call in ci.owned_sql.call_args_list))
        ci = self.cleanup_ci(); ci.database_snapshot.return_value['private_note_mailboxes'].append(dict(member_id=probe.OTHER, note_id=probe.INBOX))
        with self.assertRaises(SystemExit): probe.cleanup(ci, {'users': probe.USERS, 'notes': probe.NOTE_IDS})
        ci.owned_sql.assert_not_called()

    def test_memory_sse_parser_accepts_only_named_empty_hints_and_clean_eof(self):
        stream = memory_stream(b'event:ready\ndata:{}\n\nevent: resync\r\ndata: {}\r\n\nevent:heartbeat\ndata:{}\n\n')
        stream._read()
        self.assertTrue(stream.eof); self.assertFalse(stream.failed)
        self.assertEqual(stream.events, dict(ready=1, resync=1, heartbeat=1))
        stream.wait('resync'); stream.wait_eof(); stream.assert_resync(1)

    def test_memory_sse_parser_refuses_payload_wrong_event_partial_and_budget_overflow(self):
        cases = (b'event:ready\ndata:{"private":1}\n\n', b'event:notification\ndata:{}\n\n',
                 b'event:ready\ndata:{}', b'x' * (probe.MAX_LINE + 1) + b'\n',
                 b'event:ready\ndata:{}\n\n' * (probe.MAX_EVENTS + 1))
        for raw in cases:
            stream = memory_stream(raw); stream._read()
            with self.subTest(length=len(raw)): self.assertTrue(stream.failed)
            with self.assertRaises(AssertionError): stream.wait_eof(timeout=0)

    def test_sse_wait_distinguishes_exact_resync_minimum_heartbeat_and_missing_event(self):
        stream = memory_stream(b''); stream.events.update(resync=2, heartbeat=3)
        with self.assertRaises(AssertionError): stream.wait('resync', 1, timeout=0)
        stream.wait('heartbeat', 1, timeout=0, at_least=True)
        with self.assertRaises(AssertionError): stream.wait('ready', timeout=0)
        with self.assertRaises(AssertionError): stream.assert_resync(0)

    def test_sse_connection_refuses_nonloopback_before_opening_a_socket(self):
        with patch.object(probe.http.client, 'HTTPConnection') as connect:
            for base in ('https://127.0.0.1:1234', 'http://example.invalid:1234', 'http://127.0.0.1:1234/path'):
                with self.assertRaises(ValueError): probe.Stream(base, {})
            connect.assert_not_called()

    def test_sse_close_shutdowns_socket_before_bounded_join_and_rejects_stuck_reader(self):
        stream = memory_stream(b''); stream.socket = Mock(); stream.thread = Mock(); stream.thread.is_alive.return_value = False
        events = []; stream.socket.shutdown.side_effect = lambda *_: events.append('shutdown')
        stream.thread.join.side_effect = lambda *_: events.append('join')
        stream.close(); self.assertEqual(events, ['shutdown', 'join'])
        stream.thread.join.assert_called_once_with(probe.JOIN_BUDGET)
        stream.thread.is_alive.return_value = True
        with self.assertRaises(AssertionError): stream.close()

    def test_all_stream_cleanup_attempts_continue_after_one_reader_failure(self):
        streams = [Mock(), Mock()]; streams[0].close.side_effect = AssertionError('unit-only')
        with self.assertRaises(AssertionError): probe.close_streams(streams)
        for stream in streams: stream.close.assert_called_once()

    def test_projection_requires_exact_fields_read_flags_and_db_instant(self):
        created = '2026-10-20T00:00:00.123456Z'
        saved = '2026-10-20T09:01:00.123456+09:00'
        reads = dict.fromkeys(probe.NOTE_IDS); reads[probe.INBOX] = saved
        item = probe._view(probe.INBOX, created, '2026-10-20T00:01:00.123456Z')
        body = dict(code=200, message=probe.SUCCESS, data=item)
        probe.verify_view(body, 'detail', reads, created)
        original = copy.deepcopy(body)
        body['data']['favorite'] = 0
        with self.assertRaises(AssertionError): probe.verify_view(body, 'detail', reads, created)
        body = original; body['data']['extra'] = 'forbidden'
        with self.assertRaises(AssertionError): probe.verify_view(body, 'detail', reads, created)

    def test_notification_unread_and_sent_projection_preserve_null_and_boolean(self):
        created = '2026-10-20T00:00:00Z'; reads = dict.fromkeys(probe.NOTE_IDS)
        data = dict(content=[dict(noteId=n, kind='PRIVATE_NOTE', actorId=probe.OTHER, actorNickname=probe.NAMES[probe.OTHER], createdAt=created, read=False, href='/notes/' + n) for n in (probe.FAILURE, probe.INBOX)], nextCursor=None, unreadCount=2)
        probe.verify_view(dict(code=200, message=probe.SUCCESS, data=data), 'notifications', reads, created)
        data['unreadCount'] = True
        with self.assertRaises(AssertionError): probe.verify_view(dict(code=200, message=probe.SUCCESS, data=data), 'notifications', reads, created)
        page = dict(content=[probe._view(probe.SENT, created, None)], totalElements=1, totalPages=1, number=0, size=10)
        probe.verify_view(dict(code=200, message=probe.SUCCESS, data=page), 'sent_list', reads, created)

    def test_prefix_mismatch_fails_closed_with_incomplete_receipt_before_start(self):
        with tempfile.TemporaryDirectory() as directory:
            ci = types.SimpleNamespace(require_fixture=lambda: '42-1', SOURCE='f' * 40, EVIDENCE=Path(directory),
                require_measurements=Mock(), COUNTERS=(), EXPECTED_JAR_COUNTS=None)
            with patch.object(probe, 'load_tokens') as load, self.assertRaises(SystemExit): probe.run(ci)
            load.assert_not_called()
            self.assertEqual(json.loads((Path(directory) / 'f26-packaged-result.txt').read_text())['status'], 'incomplete')

    def test_seed_failure_still_stops_both_children_and_never_marks_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); jar = root / 'boot/build/libs/pawbridge-backend-app.jar'
            jar.parent.mkdir(parents=True); jar.write_bytes(b'not-a-real-jar')
            app, stub = Mock(), Mock(); stopped = []
            ci = types.SimpleNamespace(require_fixture=lambda: '42-1', SOURCE='f' * 40, EVIDENCE=root,
                require_measurements=Mock(), COUNTERS=probe.COUNTERS, EXPECTED_JAR_COUNTS=probe.EXPECTED_JAR_COUNTS, ROOT=root,
                database_snapshot=lambda: {}, f4=types.SimpleNamespace(start_ready_children=lambda *_: (app, stub, 'base', 'endpoint', 1, 0)),
                f3=types.SimpleNamespace(WRITER='writer', expect=lambda *_a, **_k: dict(counts={}, requests=[], objects={}),
                    stop_owned_child=lambda child, _label: stopped.append(child) or 143))
            env = dict(GITHUB_SHA='a' * 40, F2_TEST_JDBC_URL='synthetic', F2_TEST_DB_USER='reader', F2_TEST_DB_PASSWORD='p1',
                       F3_TEST_WRITER_PASSWORD='p2', F3_TEST_JWT_SECRET=KEY)
            with patch.dict(os.environ, env), patch.object(probe, 'load_tokens', return_value={'F26_CALLER': 'unit'}), patch.object(probe, 'seed', side_effect=AssertionError('unit seed failure')):
                with self.assertRaises(AssertionError): probe.run(ci)
            self.assertEqual(stopped, [app, stub])
            result = json.loads((root / 'f26-packaged-result.txt').read_text())
            self.assertEqual(result['status'], 'incomplete')
            self.assertNotIn('stream_resources_stopped', result)

    def test_counter_contract_has_no_unmeasured_or_duplicate_keys_and_balances_methods(self):
        values = probe.EXPECTED_JAR_COUNTS
        self.assertEqual(len(probe.COUNTERS), len(set(probe.COUNTERS)))
        self.assertEqual(set(values), set(probe.COUNTERS))
        self.assertEqual(values['request_checks'], values['put_checks'] + values['get_checks'])
        self.assertEqual(values['put_checks'], values['successful_put_checks'] + values['rejected_put_checks'])
        self.assertEqual(values['request_checks'], values['nonstreaming_checks'] + values['stream_open_checks'])
        self.assertEqual(values['stream_open_checks'], values['stream_ready_checks'])
        self.assertEqual(values['approved_database_write_checks'], 2)
        self.assertEqual(values['other_resync_checks'], 0)


if __name__ == '__main__':
    unittest.main()
