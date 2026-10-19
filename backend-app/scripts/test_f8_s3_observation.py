"""Exercise synthetic S3 observation with fake handlers; never bind a socket."""
import hashlib
import io
from types import SimpleNamespace
import unittest
from unittest.mock import Mock

import f3_s3_stub as stub


KEY = 'f3-123-1/posts/images/12345678-1234-1234-1234-123456789abc.jpg'
MISSING = 'f3-123-1/posts/images/12345678-1234-1234-1234-123456789abd.jpg'
OBJECT_PATH = '/f3-123-1/' + KEY
AUTH = {'Authorization': 'AWS4-HMAC-SHA256 Credential=f3-synthetic-access/synthetic'}


def fixture():
    server = SimpleNamespace(owner='123-1', bucket='f3-123-1', prefix='f3-123-1/',
        objects={KEY: {'body': b'F8-synthetic-image', 'content_type': 'image/jpeg'}}, requests=[],
        allow_delete=True, owned_keys={KEY}, fail_after=0, fail_count=0,
        delete_fail_after=0, delete_fail_count=0, metadata=stub.FixtureServer.metadata)
    server.state = lambda: stub.FixtureServer.state(server)
    return server


def handler(server, method, path, headers=None, client='127.0.0.1'):
    # Bypass BaseHTTPRequestHandler construction: no listener, socket or request parsing.
    value = object.__new__(stub.FixtureHandler)
    value.server, value.command, value.path = server, method, path
    value.headers, value.client_address = headers or {}, (client, 12345)
    value.wfile = io.BytesIO()
    value.send_response, value.send_header, value.end_headers = Mock(), Mock(), Mock()
    return value


class F8S3ObservationTest(unittest.TestCase):
    def test_given_existing_object__when_get_or_head__then_record_attempt_without_query_or_credentials(self):
        for method in ('GET', 'HEAD'):
            with self.subTest(method=method):
                server = fixture()
                request = handler(server, method, OBJECT_PATH + '?sensitive-synthetic-query=excluded', AUTH)
                getattr(request, 'do_' + method)()
                request.send_response.assert_called_once_with(200)
                self.assertEqual(1, len(server.requests))
                recorded = server.requests[0]
                self.assertEqual((method, 200, KEY, OBJECT_PATH),
                                 (recorded['method'], recorded['status'], recorded['key'], recorded['path']))
                self.assertNotIn('sensitive-synthetic-query', str(recorded))
                self.assertNotIn('Credential', str(recorded))
                self.assertEqual(b'F8-synthetic-image' if method == 'GET' else b'', request.wfile.getvalue())
                self.assertEqual({'PUT': 0, 'DELETE': 0}, server.state()['counts'])
                self.assertEqual({KEY}, set(server.objects))

    def test_given_missing_or_forbidden_object__when_get_or_head__then_record_status_and_preserve_response(self):
        for method in ('GET', 'HEAD'):
            for path, client, status, key in [('/f3-123-1/' + MISSING, '127.0.0.1', 404, MISSING),
                    ('/wrong-bucket/unknown', '127.0.0.1', 403, None),
                    (OBJECT_PATH, '192.0.2.1', 403, KEY)]:
                with self.subTest(method=method, path=path, client=client):
                    server = fixture()
                    request = handler(server, method, path, client=client)
                    getattr(request, 'do_' + method)()
                    request.send_response.assert_called_once_with(status)
                    self.assertEqual([(method, status, key)],
                        [(item['method'], item['status'], item['key']) for item in server.requests])
                    self.assertEqual({KEY}, set(server.objects))
                    if method == 'HEAD':
                        self.assertEqual(b'', request.wfile.getvalue())

    def test_given_state_control__when_reading_or_refusing_owner__then_do_not_change_request_snapshot(self):
        for owner, status in [('123-1', 200), ('foreign', 403)]:
            with self.subTest(owner=owner):
                server = fixture()
                before = server.state()
                request = handler(server, 'GET', '/__f3__/state', {'X-F3-Owner': owner})
                request.do_GET()
                request.send_response.assert_called_once_with(status)
                self.assertEqual([], server.requests)
                self.assertEqual(before, server.state())

    def test_given_malformed_head_target__when_recording_forbidden_response__then_keep_original_response(self):
        server = fixture()
        request = handler(server, 'HEAD', 'http://[invalid')
        request.do_HEAD()
        request.send_response.assert_called_once_with(403)
        self.assertEqual([('HEAD', 403, None, None)],
            [(item['method'], item['status'], item['key'], item['path']) for item in server.requests])
        self.assertEqual(b'', request.wfile.getvalue())

    def test_given_non_control_post__when_rejected__then_record_the_attempt(self):
        server = fixture()
        request = handler(server, 'POST', OBJECT_PATH)
        request.do_POST()
        request.send_response.assert_called_once_with(404)
        self.assertEqual([('POST', 404, KEY)],
                         [(item['method'], item['status'], item['key']) for item in server.requests])
        self.assertEqual({KEY}, set(server.objects))

    def test_given_existing_put_and_delete__when_recording__then_keep_single_records_and_original_metadata(self):
        server = fixture()
        upload = handler(server, 'PUT', OBJECT_PATH, dict(AUTH, **{'Content-Type': 'image/jpeg'}))
        upload._body = Mock(return_value=b'F8-upload')
        upload.do_PUT()
        upload.send_response.assert_called_once_with(200)
        delete = handler(server, 'DELETE', OBJECT_PATH, AUTH)
        delete.do_DELETE()
        delete.send_response.assert_called_once_with(204)
        self.assertEqual([('PUT', 200, KEY), ('DELETE', 204, KEY)],
                         [(item['method'], item['status'], item['key']) for item in server.requests])
        self.assertEqual(9, server.requests[0]['size'])
        self.assertEqual(hashlib.sha256(b'F8-upload').hexdigest(), server.requests[0]['sha256'])
        self.assertEqual('image/jpeg', server.requests[0]['content_type'])
        self.assertEqual({'PUT': 1, 'DELETE': 1}, server.state()['counts'])
        self.assertEqual({}, server.objects)


if __name__ == '__main__':
    unittest.main()
