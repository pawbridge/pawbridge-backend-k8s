#!/usr/bin/env python3
"""Tests for the generic service Helm image-values updater."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("update_service_image_values.py")
SPEC = importlib.util.spec_from_file_location("update_service_image_values", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


REPOSITORY = "dorosiya/pawbridge-user-service"
TAG = "sha-0123456789abcdef0123456789abcdef01234567"
DIGEST = "sha256:" + "a" * 64
OLD_DIGEST = "sha256:" + "b" * 64


class UpdateServiceImageValuesTest(unittest.TestCase):
    def test_updates_existing_image_mapping_and_preserves_unmanaged_values(self) -> None:
        original = (
            "replicaCount: 1\n"
            "image:\n"
            "  repository: old/repository\n"
            "  tag: old-tag\n"
            f"  digest: {OLD_DIGEST}\n"
            "  pullPolicy: Always\n"
            "service:\n"
            "  port: 8080\n"
        )

        updated = MODULE.update_values(original, REPOSITORY, TAG, DIGEST)

        self.assertEqual(
            updated,
            "replicaCount: 1\n"
            "image:\n"
            f"  repository: {REPOSITORY}\n"
            f"  tag: {TAG}\n"
            f"  digest: {DIGEST}\n"
            "  pullPolicy: Always\n"
            "service:\n"
            "  port: 8080\n",
        )

    def test_adds_image_mapping_when_missing(self) -> None:
        original = "replicaCount: 1\nservice:\n  port: 8080\n"

        updated = MODULE.update_values(original, REPOSITORY, TAG, DIGEST)

        self.assertEqual(
            updated,
            "image:\n"
            f"  repository: {REPOSITORY}\n"
            f"  tag: {TAG}\n"
            f"  digest: {DIGEST}\n\n"
            "replicaCount: 1\nservice:\n  port: 8080\n",
        )

    def test_is_idempotent_and_preserves_crlf(self) -> None:
        original = (
            "image:\r\n"
            "  pullPolicy: IfNotPresent\r\n"
            "service:\r\n"
            "  port: 8081\r\n"
        )

        first = MODULE.update_values(original, REPOSITORY, TAG, DIGEST)
        second = MODULE.update_values(first, REPOSITORY, TAG, DIGEST)

        self.assertEqual(second, first)
        self.assertIn("\r\n", first)
        self.assertNotIn("\n", first.replace("\r\n", ""))

    def test_rejects_invalid_image_references(self) -> None:
        invalid_references = [
            ("invalid repository", TAG, DIGEST),
            (REPOSITORY, "latest", DIGEST),
            (REPOSITORY, TAG, "sha256:not-a-digest"),
        ]

        for repository, tag, digest in invalid_references:
            with self.subTest(repository=repository, tag=tag, digest=digest):
                with self.assertRaises(ValueError):
                    MODULE.update_values("service: {}\n", repository, tag, digest)

    def test_rejects_duplicate_managed_keys(self) -> None:
        original = "image:\n  tag: first\n  tag: second\nservice: {}\n"

        with self.assertRaisesRegex(ValueError, "duplicate image key: tag"):
            MODULE.update_values(original, REPOSITORY, TAG, DIGEST)

    def test_rejects_malformed_or_duplicate_top_level_image_mappings(self) -> None:
        invalid_documents = [
            "image: old/repository:old-tag\n",
            "image:\n  tag: first\nimage:\n  tag: second\n",
        ]

        for document in invalid_documents:
            with self.subTest(document=document):
                with self.assertRaises(ValueError):
                    MODULE.update_values(document, REPOSITORY, TAG, DIGEST)

    def test_file_update_preserves_crlf(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            values_file = Path(directory) / "values.yaml"
            values_file.write_bytes(b"image:\r\n  pullPolicy: IfNotPresent\r\nenv: {}\r\n")

            changed = MODULE.update_file(values_file, REPOSITORY, TAG, DIGEST)
            content = values_file.read_bytes()

            self.assertTrue(changed)
            self.assertIn(b"\r\n", content)
            self.assertNotIn(b"\n", content.replace(b"\r\n", b""))


if __name__ == "__main__":
    unittest.main()
