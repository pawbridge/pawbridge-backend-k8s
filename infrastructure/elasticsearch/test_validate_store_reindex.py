import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch


MODULE_PATH = pathlib.Path(__file__).with_name("validate-store-reindex.py")
SPEC = importlib.util.spec_from_file_location("validate_store_reindex", MODULE_PATH)
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)


class ValidateStoreReindexTest(unittest.TestCase):

    def test_valid_projection_passes(self):
        hits = [
            self.hit(101, 10, 10_000, 2, 5, True),
            self.hit(102, 10, 20_000, 3, 5, False),
        ]

        with self.validator_context(hits):
            self.assertEqual(0, VALIDATOR.main())

    def test_wrong_primary_fails(self):
        hits = [
            self.hit(101, 10, 10_000, 2, 5, False),
            self.hit(102, 10, 20_000, 3, 5, True),
        ]

        with self.validator_context(hits):
            self.assertEqual(1, VALIDATOR.main())

    def test_mysql_field_mismatch_fails(self):
        hits = [self.hit(101, 10, 10_000, 2, 2, True)]
        manifest_hits = [self.hit(101, 10, 11_000, 2, 2, True)]

        with self.validator_context(hits, manifest_hits=manifest_hits):
            self.assertEqual(1, VALIDATOR.main())

    def test_wrong_document_id_fails(self):
        hits = [self.hit(101, 10, 10_000, 2, 2, True)]
        hits[0]["_id"] = "999"

        with self.validator_context(hits):
            self.assertEqual(1, VALIDATOR.main())

    def test_write_alias_without_write_flag_fails(self):
        hits = [self.hit(101, 10, 10_000, 2, 2, True)]

        with self.validator_context(hits, write_alias=False):
            self.assertEqual(1, VALIDATOR.main())

    def validator_context(self, hits, manifest_hits=None, write_alias=True):
        def response(_base_url, path):
            if path.endswith("/_count"):
                return {"count": len(hits)}
            if "/_search?" in path:
                return {"hits": {"hits": hits}}
            raise AssertionError(f"Unexpected path: {path}")

        manifest_documents = manifest_hits if manifest_hits is not None else hits
        manifest_file = tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", delete=False)
        for hit in manifest_documents:
            document = dict(hit["_source"])
            document.pop("updatedAt")
            manifest_file.write(json.dumps(document, ensure_ascii=False) + "\n")
        manifest_file.close()

        arguments = [
            "validate-store-reindex.py",
            "--url",
            "http://elasticsearch:9200",
            "--index",
            "store-products-v001",
            "--manifest",
            manifest_file.name,
            "--expect-read-alias",
        ]
        return _CombinedPatch(
            patch.object(VALIDATOR, "request_json", side_effect=response),
            patch.object(
                VALIDATOR,
                "alias_metadata",
                return_value={"store-products-v001": {"is_write_index": write_alias}},
            ),
            patch.object(sys, "argv", arguments),
            cleanup=lambda: pathlib.Path(manifest_file.name).unlink(missing_ok=True),
        )

    @staticmethod
    def hit(sku_id, product_id, price, stock, total_stock, primary):
        return {
            "_id": str(sku_id),
            "_source": {
                "skuId": sku_id,
                "productId": product_id,
                "categoryId": None,
                "productName": "사료",
                "skuCode": f"SKU-{sku_id}",
                "optionName": "",
                "price": price,
                "stockQuantity": stock,
                "totalStockQuantity": total_stock,
                "isPrimarySku": primary,
                "status": "ACTIVE",
                "imageUrl": None,
                "createdAt": "2026-08-28T10:00:00",
                "updatedAt": "2026-08-28T10:00:00",
            },
        }


class _CombinedPatch:
    def __init__(self, *patchers, cleanup=lambda: None):
        self.patchers = patchers
        self.cleanup = cleanup

    def __enter__(self):
        for patcher in self.patchers:
            patcher.start()
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        for patcher in reversed(self.patchers):
            patcher.stop()
        self.cleanup()


if __name__ == "__main__":
    unittest.main()
