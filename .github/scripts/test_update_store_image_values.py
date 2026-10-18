import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("update_store_image_values.py")
SPEC = importlib.util.spec_from_file_location("update_store_image_values", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


REPOSITORY = "dorosiya/pawbridge-store-service"
TAG = "sha-" + "a" * 40
DIGEST = "sha256:" + "b" * 64


class UpdateStoreImageValuesTest(unittest.TestCase):
    def test_adds_image_mapping_when_missing(self) -> None:
        original = "env:\n  SPRING_PROFILES_ACTIVE: dev\n"

        updated = MODULE.update_values(original, REPOSITORY, TAG, DIGEST)

        self.assertEqual(
            updated,
            "image:\n"
            f"  repository: {REPOSITORY}\n"
            f"  tag: {TAG}\n"
            f"  digest: {DIGEST}\n"
            "\n"
            "env:\n"
            "  SPRING_PROFILES_ACTIVE: dev\n",
        )

    def test_updates_managed_keys_and_preserves_other_image_settings(self) -> None:
        original = (
            "image:\n"
            "  repository: old/repository\n"
            "  tag: old-tag\n"
            "  # Keep the pull policy override.\n"
            "  pullPolicy: IfNotPresent\n"
            "  digest: sha256:old\n"
            "env:\n"
            "  SPRING_PROFILES_ACTIVE: dev\n"
        )

        updated = MODULE.update_values(original, REPOSITORY, TAG, DIGEST)

        self.assertIn(f"  repository: {REPOSITORY}\n", updated)
        self.assertIn(f"  tag: {TAG}\n", updated)
        self.assertIn(f"  digest: {DIGEST}\n", updated)
        self.assertIn("  # Keep the pull policy override.\n", updated)
        self.assertIn("  pullPolicy: IfNotPresent\n", updated)

    def test_update_is_idempotent(self) -> None:
        original = "env:\n  SPRING_PROFILES_ACTIVE: dev\n"
        first = MODULE.update_values(original, REPOSITORY, TAG, DIGEST)

        second = MODULE.update_values(first, REPOSITORY, TAG, DIGEST)

        self.assertEqual(second, first)

    def test_rejects_invalid_immutable_reference(self) -> None:
        invalid_references = [
            ("repository with spaces", TAG, DIGEST),
            (REPOSITORY, "latest", DIGEST),
            (REPOSITORY, TAG, "sha256:short"),
        ]

        for repository, tag, digest in invalid_references:
            with self.subTest(repository=repository, tag=tag, digest=digest):
                with self.assertRaises(ValueError):
                    MODULE.update_values("env: {}\n", repository, tag, digest)

    def test_rejects_duplicate_managed_keys(self) -> None:
        original = "image:\n  tag: first\n  tag: second\nenv: {}\n"

        with self.assertRaisesRegex(ValueError, "duplicate image key: tag"):
            MODULE.update_values(original, REPOSITORY, TAG, DIGEST)


if __name__ == "__main__":
    unittest.main()
