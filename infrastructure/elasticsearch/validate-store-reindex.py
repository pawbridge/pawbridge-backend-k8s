#!/usr/bin/env python3

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict


ALLOWED_STATUSES = {"ACTIVE", "HIDDEN", "SOLD_OUT", "DELETED"}
REQUIRED_FIELDS = {
    "skuId", "productId", "categoryId", "productName", "skuCode", "optionName",
    "price", "stockQuantity", "totalStockQuantity", "isPrimarySku", "status",
    "imageUrl", "createdAt", "updatedAt",
}
MYSQL_FIELDS = REQUIRED_FIELDS - {"updatedAt"}
HTTP_TIMEOUT_SECONDS = 10


def request_json(base_url, path):
    try:
        with urllib.request.urlopen(
            base_url.rstrip("/") + path,
            timeout=HTTP_TIMEOUT_SECONDS,
        ) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Elasticsearch {error.code} for {path}: {body}") from error


def alias_metadata(base_url, alias):
    encoded_alias = urllib.parse.quote(alias, safe="")
    try:
        response = request_json(base_url, f"/_alias/{encoded_alias}")
    except RuntimeError as error:
        if "Elasticsearch 404" in str(error):
            return {}
        raise
    return {
        index: index_data.get("aliases", {}).get(alias, {})
        for index, index_data in response.items()
        if alias in index_data.get("aliases", {})
    }


def load_manifest(path):
    documents = {}
    with open(path, encoding="utf-8") as manifest_file:
        for line_number, line in enumerate(manifest_file, start=1):
            if not line.strip():
                continue
            try:
                document = json.loads(line)
            except json.JSONDecodeError as error:
                raise RuntimeError(f"invalid manifest JSON at line {line_number}: {error}") from error
            missing = sorted(MYSQL_FIELDS - set(document))
            unexpected = sorted(set(document) - MYSQL_FIELDS)
            if missing or unexpected:
                raise RuntimeError(
                    f"invalid manifest fields at line {line_number}: "
                    f"missing={missing}, unexpected={unexpected}"
                )
            sku_id = document["skuId"]
            if sku_id in documents:
                raise RuntimeError(f"duplicate manifest skuId: {sku_id}")
            documents[sku_id] = document
    return documents


def fail(errors, message):
    errors.append(message)


def main():
    parser = argparse.ArgumentParser(description="Validate Store ES against a MySQL canonical manifest")
    parser.add_argument("--url", required=True)
    parser.add_argument("--index", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--read-alias", default="store-products-read")
    parser.add_argument("--write-alias", default="store-products-write")
    parser.add_argument("--expect-read-alias", action="store_true")
    args = parser.parse_args()

    manifest = load_manifest(args.manifest)
    encoded_index = urllib.parse.quote(args.index, safe="")
    count = request_json(args.url, f"/{encoded_index}/_count")["count"]
    if count > 10000:
        raise RuntimeError("Validator refuses indices over 10,000 documents; add paginated validation first")

    search = request_json(
        args.url,
        f"/{encoded_index}/_search?size={max(count, 1)}&sort=skuId:asc",
    )
    hits = search["hits"]["hits"]
    errors = []

    if count != len(manifest):
        fail(errors, f"document count {count} != MySQL manifest count {len(manifest)}")
    if len(hits) != count:
        fail(errors, f"retrieved hit count {len(hits)} != Elasticsearch count {count}")

    sku_ids = set()
    products = defaultdict(list)
    for hit in hits:
        source = hit.get("_source", {})
        missing = sorted(REQUIRED_FIELDS - set(source))
        unexpected = sorted(set(source) - REQUIRED_FIELDS)
        if missing:
            fail(errors, f"document {hit.get('_id')} missing fields: {missing}")
        if unexpected:
            fail(errors, f"document {hit.get('_id')} has unexpected fields: {unexpected}")

        sku_id = source.get("skuId")
        product_id = source.get("productId")
        if str(sku_id) != hit.get("_id"):
            fail(errors, f"document _id {hit.get('_id')} != skuId {sku_id}")
        if sku_id in sku_ids:
            fail(errors, f"duplicate skuId: {sku_id}")
        sku_ids.add(sku_id)
        products[product_id].append(source)

        if source.get("status") not in ALLOWED_STATUSES:
            fail(errors, f"skuId {sku_id} has invalid status: {source.get('status')}")

        expected = manifest.get(sku_id)
        if expected is None:
            fail(errors, f"skuId {sku_id} is absent from MySQL manifest")
        else:
            for field in sorted(MYSQL_FIELDS):
                if source.get(field) != expected.get(field):
                    fail(
                        errors,
                        f"skuId {sku_id} field {field}: ES={source.get(field)!r} "
                        f"!= MySQL={expected.get(field)!r}",
                    )

    missing_sku_ids = sorted(set(manifest) - sku_ids)
    if missing_sku_ids:
        fail(errors, f"MySQL skuIds missing from Elasticsearch: {missing_sku_ids}")

    for product_id, documents in products.items():
        primary_documents = [document for document in documents if document.get("isPrimarySku") is True]
        if len(primary_documents) != 1:
            fail(errors, f"productId {product_id} has {len(primary_documents)} primary SKUs")
            continue

        expected_primary = min(documents, key=lambda document: (document["price"], document["skuId"]))
        if primary_documents[0].get("skuId") != expected_primary.get("skuId"):
            fail(errors, f"productId {product_id} primary SKU violates price/id ordering")

        expected_total_stock = sum(document["stockQuantity"] for document in documents)
        projected_totals = {document.get("totalStockQuantity") for document in documents}
        if projected_totals != {expected_total_stock}:
            fail(
                errors,
                f"productId {product_id} total stock projection {sorted(map(str, projected_totals))} "
                f"!= {expected_total_stock}",
            )

    write_alias = alias_metadata(args.url, args.write_alias)
    if set(write_alias) != {args.index}:
        fail(errors, f"write alias targets {sorted(write_alias)} != [{args.index}]")
    elif write_alias[args.index].get("is_write_index") is not True:
        fail(errors, f"write alias target {args.index} is missing is_write_index=true")

    if args.expect_read_alias:
        read_alias = alias_metadata(args.url, args.read_alias)
        if set(read_alias) != {args.index}:
            fail(errors, f"read alias targets {sorted(read_alias)} != [{args.index}]")

    if errors:
        print("Store reindex validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(
        f"Store reindex validation passed: documents={count}, "
        f"products={len(products)}, uniqueSkuIds={len(sku_ids)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
