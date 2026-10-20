"""Mock-only F4 startup/shape tests: no sockets, JVM, Docker or real children."""
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import f4_ci as f4

ORIGINAL_STOP = f4.f3.stop_owned_child


class F4ShapePreparationTest(unittest.TestCase):
    TABLES_SQL = (
        'CREATE TABLE pawbridge_f4_fixture.comments(comment_id BIGINT PRIMARY KEY,'
        'post_id BIGINT REFERENCES pawbridge_community.posts(post_id)); '
        'CREATE TABLE pawbridge_f4_fixture.private_notes(note_id BIGINT PRIMARY KEY,'
        'post_id BIGINT REFERENCES pawbridge_community.posts(post_id))')
    MARKER_SQL = "SELECT value || ':' || owner FROM pawbridge_f4_fixture.marker"

    def setUp(self):
        self.events = []
        self.owner = '123-1'
        self.enter(patch.dict(os.environ, {'F4_CI_OWNER': self.owner}, clear=True))
        self.hosted = self.enter(patch.object(f4.f2, 'require_hosted',
            side_effect=lambda: self.events.append('hosted') or self.owner))
        self.fixture = self.enter(patch.object(f4.f3, 'require_fixture',
            side_effect=lambda: self.events.append('f3-owned')))
        self.sql = self.enter(patch.object(f4.f3, 'sql', side_effect=self.execute))
        self.command = self.enter(patch.object(f4.f2, 'command',
            side_effect=AssertionError('Real commands forbidden')))
        self.enter(patch.object(f4.f3.socket, 'socket',
            side_effect=AssertionError('Real sockets forbidden')))

    def enter(self, context):
        value = context.__enter__()
        self.addCleanup(context.__exit__, None, None, None)
        return value

    def execute(self, query):
        self.events.append(query)
        return f4.MARKER + ':' + self.owner if query == self.MARKER_SQL else ''

    def tearDown(self):
        self.command.assert_not_called()

    def test_given_owned_fixture__when_preparing_shape__then_create_only_two_fk_tables_after_identity(self):
        f4.prepare_business_shape()
        self.assertEqual(['hosted', 'f3-owned', self.MARKER_SQL, self.TABLES_SQL], self.events)
        self.assertEqual(2, self.TABLES_SQL.count('CREATE TABLE '))
        for operation in ('TRUNCATE ', 'INSERT ', 'UPDATE ', 'DELETE ', 'setval(', 'GRANT '):
            self.assertNotIn(operation, self.sql.call_args.args[0])

    def test_given_unowned_fixture__when_preparing_shape__then_refuse_before_table_writes(self):
        for failure in ('hosted', 'f3-owner', 'f4-owner', 'marker'):
            with self.subTest(failure=failure):
                self.events.clear()
                self.sql.reset_mock()
                with patch.object(f4.f2, 'require_hosted',
                        side_effect=SystemExit('Hosted target required') if failure == 'hosted' else None,
                        return_value=self.owner), \
                     patch.object(f4.f3, 'require_fixture',
                        side_effect=SystemExit('F3 owner mismatch') if failure == 'f3-owner' else None), \
                     patch.dict(os.environ, {'F4_CI_OWNER': 'foreign' if failure == 'f4-owner' else self.owner}), \
                     patch.object(f4.f3, 'sql', return_value=f4.MARKER + ':foreign') as sql:
                    with self.assertRaises(SystemExit):
                        f4.prepare_business_shape()
                    self.assertTrue(all(call.args == (self.MARKER_SQL,) for call in sql.call_args_list))

    def test_given_ddl_failure__when_preparing_shape__then_propagate_without_retry_or_business_writes(self):
        failure = subprocess.CalledProcessError(1, 'synthetic-owned-sql')
        self.sql.side_effect = [f4.MARKER + ':' + self.owner, failure]
        with self.assertRaises(subprocess.CalledProcessError) as raised:
            f4.prepare_business_shape()
        self.assertIs(failure, raised.exception)
        self.assertEqual([(self.MARKER_SQL,), (self.TABLES_SQL,)],
            [call.args for call in self.sql.call_args_list])

    def test_given_full_business_preparation__when_preparing__then_preserve_original_single_sql_and_order(self):
        with patch.object(f4, 'prepare_business_shape') as shape:
            f4.prepare_business_rows()
        self.sql.assert_called_once_with(
            'TRUNCATE pawbridge_community.post_search_documents,pawbridge_community.outbox_events,'
            'pawbridge_community.posts,pawbridge_user.users; '
            "INSERT INTO pawbridge_user.users VALUES(42,'합성작성자'); "
            "SELECT setval('pawbridge_community.posts_post_id_seq',100000,true); "
            + self.TABLES_SQL)
        # Splitting this call would change the original psql -c transaction boundary.
        shape.assert_not_called()

    def test_given_shape_cli__when_invoked__then_use_only_owned_database_shape_path(self):
        with patch.object(f4.sys, 'argv', ['f4_ci.py', 'prepare-shape']):
            runpy.run_path(str(Path(f4.__file__)), run_name='__main__')
        self.assertEqual(['hosted', 'hosted', 'f3-owned', self.MARKER_SQL, self.TABLES_SQL], self.events)


class Log:
    def __init__(self):
        self.data = b''

    def read_bytes(self):
        return self.data

    def append(self, value):
        self.data += value.encode() + b'\n'


class F4StartupTest(unittest.TestCase):
    def setUp(self):
        self.owner = '123-1'
        self.stub_log, self.app_log = Log(), Log()
        self.modes = ['healthy']
        self.attempt = 0
        self.children = []
        self.force_cleanup = False
        self.environment = self.enter(patch.dict(os.environ, {
            'GITHUB_REPOSITORY': 'pawbridge/pawbridge-backend-k8s',
            'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}, clear=True))
        self.enter(patch.object(f4.f2, 'require_hosted', return_value=self.owner))
        self.enter(patch.object(f4.f3.socket, 'socket', side_effect=AssertionError('Real sockets forbidden')))
        self.sql = self.enter(patch.object(f4.f3, 'sql', side_effect=AssertionError('Startup must not mutate DB')))
        self.ports = self.enter(patch.object(f4.f3, 'port', side_effect=iter(range(41001, 41030))))
        self.popen = self.enter(patch.object(f4.subprocess, 'Popen', side_effect=self.launch))
        self.stop = self.enter(patch.object(f4.f3, 'stop_owned_child', side_effect=self.stop_normally))
        self.request = self.enter(patch.object(f4.f3, 'request', return_value=(404, b'', {})))
        self.enter(patch.object(f4.f3, 'expect', return_value={'owner': self.owner}))
        self.enter(patch.object(f4.f3, 'verify_continue_handshake'))
        self.enter(patch.object(f4.f3, 'wait_ready', side_effect=self.wait_ready))

    def enter(self, context):
        value = context.__enter__()
        self.addCleanup(context.__exit__, None, None, None)
        return value

    def launch(self, command, **options):
        child = Mock()
        if command[0] == 'java':
            kind = 'app'
            mode = self.modes[self.attempt - 1]
            port = options['env']['F2_PORT']
            if mode == 'app-bind':
                child.poll.return_value = 1
                self.app_log.append('Port ' + port + ' was already in use.')
            elif mode == 'database':
                child.poll.return_value = 1
                self.app_log.append('Synthetic database startup failure')
            elif mode == 'timeout':
                child.poll.return_value = None
                self.app_log.append('Application has not bound its listener')
            else:
                child.poll.return_value = None
                self.app_log.append('Tomcat started on port ' + port + ' (http)')
        else:
            self.assertTrue(command[1].endswith('/scripts/f3_s3_stub.py'))
            kind = 'stub'
            self.attempt += 1
            mode = self.modes[self.attempt - 1]
            port = command[command.index('--port') + 1]
            child.poll.return_value = 1 if mode == 'stub-bind' else None
            if mode == 'stub-bind':
                self.stub_log.append('OSError: [Errno 98] Address already in use')
            else:
                self.stub_log.append(json.dumps({'endpoint': 'http://127.0.0.1:' + port, 'owner': self.owner}))
        child.wait.return_value = child.poll.return_value
        if self.force_cleanup and kind == 'stub':
            child.wait.side_effect = [subprocess.TimeoutExpired('owned child', 20), -9]
        self.children.append((kind, child))
        return child

    def wait_ready(self, child, check, label):
        if child.poll() is not None:
            raise AssertionError(label + ' exited before readiness')
        if not check():
            raise AssertionError(label + ' readiness deadline exceeded')

    def stop_normally(self, child, _label):
        child.poll.return_value = 143
        child.wait.return_value = 143
        return 143

    def start(self):
        return f4.start_ready_children(Path('/synthetic/app.jar'), {}, {}, self.owner,
            'f3-' + self.owner, 'f3-' + self.owner + '/', Mock(), Mock(), self.stub_log, self.app_log)

    def tearDown(self):
        self.sql.assert_not_called()

    def test_given_repeated_port_candidates__when_selecting__then_choose_distinct_ports_or_fail_at_bound(self):
        self.ports.side_effect = [41001, 41001, 41002]
        self.assertEqual((41001, 41002), f4.distinct_ports())
        self.ports.reset_mock(side_effect=True)
        self.ports.side_effect = [41001] * 9
        with self.assertRaisesRegex(AssertionError, 'distinct'):
            f4.distinct_ports()
        self.assertEqual(9, self.ports.call_count)
        self.popen.assert_not_called()

    def test_given_confirmed_stub_bind_collision__when_starting__then_reap_and_retry_fresh_ports(self):
        self.modes = ['stub-bind', 'healthy']
        _, _, base, endpoint, attempts, _ = self.start()
        self.assertEqual(2, attempts)
        self.assertEqual('http://127.0.0.1:41004', base)
        self.assertEqual('http://127.0.0.1:41003', endpoint)
        self.assertEqual(3, self.popen.call_count)
        self.children[0][1].wait.assert_called_once_with(timeout=5)
        self.stop.assert_not_called()

    def test_given_confirmed_application_bind_collision__when_starting__then_stop_old_stub_before_retry(self):
        self.modes = ['app-bind', 'healthy']
        _, _, base, endpoint, attempts, offset = self.start()
        self.assertEqual(2, attempts)
        self.assertNotEqual(base, endpoint)
        self.assertGreater(offset, 0)
        self.assertEqual(4, self.popen.call_count)
        self.stop.assert_called_once_with(self.children[0][1], 'F4 startup S3 stub')
        self.children[1][1].wait.assert_called_once_with(timeout=5)

    def test_given_stale_or_foreign_start_markers__when_checking_readiness__then_reject_without_http_fallback(self):
        child = Mock()
        child.poll.return_value = None
        endpoint = 'http://127.0.0.1:41001'
        for label, metadata in (
                ('previous attempt', {'endpoint': endpoint, 'owner': self.owner}),
                ('wrong owner', {'endpoint': endpoint, 'owner': '999-1'}),
                ('wrong endpoint', {'endpoint': 'http://127.0.0.1:41003', 'owner': self.owner})):
            with self.subTest(marker=label):
                log = Log()
                log.append(json.dumps(metadata))
                offset = len(log.read_bytes()) if label == 'previous attempt' else 0
                self.assertFalse(f4.stub_ready(child, log, offset, endpoint, self.owner))
        app_log = Log()
        app_log.append('Tomcat started on port 41002 (http)')
        self.assertFalse(f4.application_ready(child, app_log, len(app_log.read_bytes()),
            41002, 'http://127.0.0.1:41002'))
        f4.f3.expect.assert_not_called()
        self.request.assert_not_called()
        self.popen.assert_not_called()

    def test_given_database_failure_or_unconfirmed_timeout__when_starting__then_fail_without_retry(self):
        for mode in ('database', 'timeout'):
            with self.subTest(mode=mode):
                self.modes = [mode]
                self.attempt = 0
                self.popen.reset_mock()
                self.request.reset_mock()
                with self.assertRaises(AssertionError):
                    self.start()
                self.assertEqual(2, self.popen.call_count)
                # Even an unrelated HTTP 404 cannot replace our child's listener-start marker.
                self.request.assert_not_called()

    def test_given_three_confirmed_collisions__when_starting__then_cleanup_every_attempt_and_fail(self):
        self.modes = ['app-bind'] * 3
        with self.assertRaisesRegex(AssertionError, 'exited before readiness'):
            self.start()
        self.assertEqual(6, self.popen.call_count)
        self.assertEqual(3, self.stop.call_count)
        for kind, child in self.children:
            if kind == 'app':
                child.wait.assert_called_once_with(timeout=5)

    def test_given_forced_cleanup_is_needed__when_releasing_failed_attempt__then_fail_instead_of_retry(self):
        self.modes = ['app-bind', 'healthy']
        self.force_cleanup = True
        self.stop.side_effect = ORIGINAL_STOP
        with self.assertRaisesRegex(AssertionError, 'forced termination'):
            self.start()
        self.assertEqual(2, self.popen.call_count)
        self.children[0][1].kill.assert_called_once_with()

    def test_given_listener_readiness_failure__when_smoking__then_do_not_prepare_business_rows(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'boot/build/libs/pawbridge-backend-app.jar'
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b'never executed')
            tokens = root / 'synthetic-tokens.json'
            tokens.write_text('{}')
            values = {'F3_TEST_TOKENS_PATH': str(tokens), 'F2_TEST_JDBC_URL': 'unused',
                'F2_TEST_DB_USER': 'unused', 'F2_TEST_DB_PASSWORD': 'synthetic',
                'F3_TEST_WRITER_PASSWORD': 'synthetic', 'F3_TEST_JWT_SECRET': 'synthetic'}
            with patch.dict(os.environ, values), patch.object(f4, 'ROOT', root), \
                    patch.object(f4, 'EVIDENCE', root / 'evidence'), \
                    patch.object(f4, 'require_fixture', return_value=self.owner), \
                    patch.object(f4, 'start_ready_children', side_effect=AssertionError('readiness failure')), \
                    patch.object(f4, 'prepare_business_rows') as rows:
                with self.assertRaisesRegex(AssertionError, 'readiness failure'):
                    f4.smoke()
                rows.assert_not_called()
            self.popen.assert_not_called()


if __name__ == '__main__':
    unittest.main()
