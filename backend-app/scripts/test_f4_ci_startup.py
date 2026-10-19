"""Mock-only F4 listener orchestration tests: no sockets, JVM, Docker or real children."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import f4_ci as f4

ORIGINAL_STOP = f4.f3.stop_owned_child


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
