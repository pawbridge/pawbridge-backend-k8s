#!/usr/bin/env python3
"""Loopback-only, in-memory S3 fixture for F3 hosted CI; never proxies to storage.

The access/secret strings are public synthetic fixture values, not real credentials.
CLI owner, bucket and prefix must describe the same unique run. Control calls need
X-F3-Owner. GET /__f3__/state reports metadata only; POST /__f3__/fail-next-put
accepts {"after": 1, "count": 1, "status": 400} to fail after one successful PUT.
Objects are bounded synthetic bytes and disappear when this process stops.
"""

import argparse
import hashlib
import ipaddress
import json
import re
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import unquote, urlsplit

from f2_ci import require_hosted

SYNTHETIC_ACCESS_KEY = "f3-synthetic-access"
MAX_OBJECT_BYTES = 16 * 1024
MAX_WIRE_BYTES = MAX_OBJECT_BYTES * 4


def arguments():
    owner = require_hosted()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", choices=("127.0.0.1",), default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--owner", required=True)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--prefix", required=True)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,39}", args.owner):
        parser.error("owner must be a unique lowercase CI run identifier (3-40 characters)")
    if args.owner != owner:
        parser.error("owner must match this hosted run identity")
    if args.bucket != "f3-" + args.owner or args.prefix != "f3-" + args.owner + "/":
        parser.error("bucket and prefix must exactly match f3-<owner> and f3-<owner>/")
    return args


class FixtureServer(HTTPServer):
    def __init__(self, args):
        super().__init__((args.host, args.port), FixtureHandler)
        self.owner = args.owner
        self.bucket = args.bucket
        self.prefix = args.prefix
        self.objects = {}
        self.requests = []
        self.fail_after = 0
        self.fail_count = 0

    def state(self):
        return {
            "owner": self.owner,
            "bucket": self.bucket,
            "prefix": self.prefix,
            "requests": self.requests,
            "objects": [self.metadata(key, value) for key, value in sorted(self.objects.items())],
            "counts": {method: sum(item["method"] == method for item in self.requests)
                       for method in ("PUT", "DELETE")},
        }

    @staticmethod
    def metadata(key, value):
        return {"key": key, "content_type": value["content_type"], "size": len(value["body"]),
                "sha256": hashlib.sha256(value["body"]).hexdigest()}


class FixtureHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    timeout = 3

    def handle_expect_100(self):
        accepted = super().handle_expect_100()
        self.wfile.flush()
        print(json.dumps({"continue_response_sent": accepted}), flush=True)
        return accepted

    def log_message(self, *_):
        # Do not log Authorization headers, query strings or uploaded body bytes.
        pass

    def _respond(self, status, body=b"", content_type="application/xml", extra=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.send_header("x-amz-request-id", "f3-synthetic")
        for name, value in (extra or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.close_connection = True
        if self.command != "HEAD":
            self.wfile.write(body)

    def _error(self, status, code="InvalidRequest"):
        body = ("<Error><Code>" + code + "</Code><Message>F3 synthetic fixture rejected request"
                "</Message><RequestId>f3-synthetic</RequestId></Error>").encode()
        self._respond(status, body)

    def _json(self, status, value):
        self._respond(status, json.dumps(value, sort_keys=True).encode(), "application/json")

    def _local(self):
        if not ipaddress.ip_address(self.client_address[0]).is_loopback:
            self._error(403, "AccessDenied")
            return False
        return True

    def _control(self):
        if not self._local():
            return False
        if self.headers.get("X-F3-Owner") != self.server.owner:
            self._error(403, "AccessDenied")
            return False
        return True

    def _key(self):
        try:
            parsed = urlsplit(self.path)
            if parsed.scheme or parsed.netloc:
                return None
            path = unquote(parsed.path, errors="strict")
        except (ValueError, UnicodeError):
            return None
        bucket_prefix = "/" + self.server.bucket + "/"
        if not path.startswith(bucket_prefix):
            return None
        key = path[len(bucket_prefix):]
        media_prefix = self.server.prefix + "posts/"
        if not key.startswith(media_prefix):
            return None
        suffix = key[len(media_prefix):]
        if not re.fullmatch(r"(?:images|videos)/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\.[^/\\]*)?", suffix):
            return None
        if any(ord(character) < 32 or character == "\x7f" for character in key):
            return None
        return key

    def _synthetic_credentials(self):
        # Presence of our public synthetic access key is a guard, not SigV4 verification.
        return re.search(r"(?:^|[ ,])Credential=" + SYNTHETIC_ACCESS_KEY + r"/",
                         self.headers.get("Authorization", "")) is not None

    def _record(self, status, key=None, body=None):
        self.server.requests.append({
            "method": self.command,
            "path": urlsplit(self.path).path,
            "key": key,
            "status": status,
            "content_type": self.headers.get("Content-Type"),
            "size": len(body) if body is not None else None,
            "sha256": hashlib.sha256(body).hexdigest() if body is not None else None,
        })

    def _wire_body(self):
        length_header = self.headers.get("Content-Length")
        transfer = self.headers.get("Transfer-Encoding", "").lower()
        if transfer:
            if transfer != "chunked" or length_header is not None:
                raise ValueError("ambiguous or unsupported HTTP body framing")
            chunks = bytearray()
            while True:
                line = self.rfile.readline(256)
                if not line.endswith(b"\r\n"):
                    raise ValueError("invalid chunk line")
                length = int(line.split(b";", 1)[0], 16)
                if length < 0 or len(chunks) + length > MAX_WIRE_BYTES:
                    raise ValueError("synthetic wire body limit exceeded")
                if length == 0:
                    for _ in range(32):
                        trailer = self.rfile.readline(1024)
                        if trailer == b"\r\n":
                            return bytes(chunks)
                        if not trailer.endswith(b"\r\n"):
                            raise ValueError("invalid chunk trailer")
                    raise ValueError("too many chunk trailers")
                chunk = self.rfile.read(length)
                if len(chunk) != length or self.rfile.read(2) != b"\r\n":
                    raise ValueError("incomplete HTTP chunk")
                chunks.extend(chunk)
        length = int(length_header or "0")
        if length < 0 or length > MAX_WIRE_BYTES:
            raise ValueError("synthetic wire body limit exceeded")
        body = self.rfile.read(length)
        if len(body) != length:
            raise ValueError("incomplete HTTP body")
        return body

    def _body(self):
        # Synthetic framing metadata only: never Authorization, path query or body.
        print(json.dumps({"framing": {
            "method": self.command, "http_version": self.request_version,
            **{name: self.headers.get(name) for name in (
                "Content-Length", "Transfer-Encoding", "Content-Encoding", "Expect",
                "x-amz-decoded-content-length")}}}), flush=True)
        wire = self._wire_body()
        encoding = self.headers.get("Content-Encoding", "").lower()
        if encoding == "aws-chunked":
            body = bytearray()
            offset = 0
            while True:
                end = wire.find(b"\r\n", offset)
                if end < 0:
                    raise ValueError("invalid AWS chunk line")
                length = int(wire[offset:end].split(b";", 1)[0], 16)
                offset = end + 2
                if length < 0 or len(body) + length > MAX_OBJECT_BYTES:
                    raise ValueError("synthetic object limit exceeded")
                if length == 0:
                    wire = bytes(body)
                    break
                if len(wire) < offset + length + 2 or wire[offset + length:offset + length + 2] != b"\r\n":
                    raise ValueError("incomplete AWS chunk")
                body.extend(wire[offset:offset + length])
                offset += length + 2
        elif encoding:
            raise ValueError("unsupported content encoding")
        if len(wire) > MAX_OBJECT_BYTES:
            raise ValueError("synthetic object limit exceeded")
        decoded_length = self.headers.get("x-amz-decoded-content-length")
        if decoded_length is not None and int(decoded_length) != len(wire):
            raise ValueError("decoded body length mismatch")
        return wire

    def do_PUT(self):
        key = self._key()
        if not self._local():
            self._record(403, key)
            return
        if key is None or not self._synthetic_credentials():
            self._record(403, key)
            self._error(403, "AccessDenied")
            return
        try:
            body = self._body()
        except (ValueError, UnicodeError, TimeoutError, ConnectionError) as failure:
            print(json.dumps({"framing_failure": type(failure).__name__}), flush=True)
            self._record(400, key)
            self._error(400)
            return
        if self.server.fail_count and self.server.fail_after == 0:
            self.server.fail_count -= 1
            self._record(400, key, body)
            self._error(400)
            return
        if self.server.fail_count:
            self.server.fail_after -= 1
        self.server.objects[key] = {"body": body, "content_type": self.headers.get("Content-Type")}
        self._record(200, key, body)
        self._respond(200, extra={"ETag": '"' + hashlib.md5(body).hexdigest() + '"'})

    def do_DELETE(self):
        # This flow never supports compensation. Keep attempted DELETE observable.
        self._record(403, self._key())
        self._error(403, "AccessDenied")

    def do_GET(self):
        if urlsplit(self.path).path == "/__f3__/state":
            if self._control():
                self._json(200, self.server.state())
            return
        self._object()

    def do_HEAD(self):
        self._object()

    def _object(self):
        if not self._local():
            return
        key = self._key()
        if key is None:
            self._error(403, "AccessDenied")
        elif key not in self.server.objects:
            self._error(404, "NoSuchKey")
        else:
            value = self.server.objects[key]
            self._respond(200, value["body"], value["content_type"] or "application/octet-stream")

    def do_POST(self):
        if urlsplit(self.path).path != "/__f3__/fail-next-put" or not self._control():
            if urlsplit(self.path).path != "/__f3__/fail-next-put":
                self._error(404, "NoSuchKey")
            return
        try:
            settings = json.loads(self._body())
            if not isinstance(settings, dict) or set(settings) - {"after", "count", "status"}:
                raise ValueError("unknown failure settings")
            after = settings.get("after", 0)
            count = settings.get("count", 1)
            status = settings.get("status", 400)
            if type(after) is not int or type(count) is not int or type(status) is not int:
                raise ValueError("integer settings required")
            if not 0 <= after <= 10 or not 1 <= count <= 10 or status != 400:
                raise ValueError("failure injection only permits bounded HTTP400 failures")
        except (ValueError, UnicodeError):
            self._error(400)
            return
        self.server.fail_after = after
        self.server.fail_count = count
        self._json(200, {"after": after, "count": count, "status": status})


def main():
    args = arguments()
    with FixtureServer(args) as server:
        print(json.dumps({"endpoint": "http://127.0.0.1:" + str(args.port),
                          "owner": args.owner, "bucket": args.bucket, "prefix": args.prefix}), flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
