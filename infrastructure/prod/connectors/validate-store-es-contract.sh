#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
BACKEND_ROOT=$(cd -- "$SCRIPT_DIR/../../.." && pwd)
SOURCE_CONFIG="$SCRIPT_DIR/store-outbox-connector.json"
SINK_CONFIG="$SCRIPT_DIR/store-es-sink-connector.json"
LOCAL_SOURCE_CONFIG="$BACKEND_ROOT/infrastructure/kafka/connectors/store-outbox-connector.json"
LOCAL_SINK_CONFIG="$BACKEND_ROOT/infrastructure/kafka/connectors/store-es-sink-connector.json"
MAPPING_FILE="$BACKEND_ROOT/infrastructure/elasticsearch/mappings/store-index-mapping.json"
REINDEX_SQL="$BACKEND_ROOT/store-service/src/main/resources/sync-to-es.sql"
MANIFEST_SQL="$BACKEND_ROOT/store-service/src/main/resources/store-es-validation-manifest.sql"
ORDER_SERVICE="$BACKEND_ROOT/store-service/src/main/java/com/pawbridge/storeservice/domain/order/service/OrderServiceImpl.java"

command -v jq >/dev/null 2>&1 || {
  echo "Required command is missing: jq" >&2
  exit 1
}

for config_file in "$SOURCE_CONFIG" "$LOCAL_SOURCE_CONFIG"; do
  jq -e '
    .config["transforms.outbox.table.field.event.key"] == "aggregate_id" and
    .config["transforms.outbox.route.by.field"] == "aggregate_type" and
    .config["transforms.outbox.route.topic.replacement"] == "store.${routedByValue}.events" and
    .config["transforms.outbox.table.expand.json.payload"] == "true"
  ' "$config_file" >/dev/null
done

for config_file in "$SINK_CONFIG" "$LOCAL_SINK_CONFIG"; do
  jq -e '
    .config.topics == "store.product-sku.events" and
    .config["external.resource.usage"] == "ALIAS_INDEX" and
    .config["topic.to.external.resource.mapping"] == "store.product-sku.events:store-products-write" and
    .config["key.ignore"] == "false" and
    .config["write.method"] == "INSERT" and
    .config["behavior.on.null.values"] == "FAIL" and
    .config["behavior.on.malformed.documents"] == "fail" and
    .config["errors.tolerance"] == "none" and
    .config["errors.deadletterqueue.topic.name"] == "store.product-sku.es.dlq" and
    .config["errors.deadletterqueue.topic.replication.factor"] == "1" and
    .config["errors.log.include.messages"] == "false"
  ' "$config_file" >/dev/null
done

jq -e '
  .config["database.hostname"] == "db-server" and
  .config["schema.history.internal.kafka.bootstrap.servers"] == "kafka-broker:29092"
' "$LOCAL_SOURCE_CONFIG" >/dev/null
jq -e '.config["connection.url"] == "http://elasticsearch:9200"' "$LOCAL_SINK_CONFIG" >/dev/null

jq -e '
  .mappings.dynamic == "strict" and
  .mappings.properties.skuId.type == "long" and
  .mappings.properties.totalStockQuantity.type == "integer" and
  .mappings.properties.isPrimarySku.type == "boolean" and
  .mappings.properties.status.type == "keyword"
' "$MAPPING_FILE" >/dev/null

for required_sql_fragment in \
  "'product-sku'" \
  "ORDER BY primary_sku.price ASC, primary_sku.id ASC" \
  "JSON_EXTRACT('true', '$')" \
  "'status',         p.status" \
  "JOIN option_groups"; do
  if ! grep -Fq "$required_sql_fragment" "$REINDEX_SQL"; then
    echo "Missing reindex SQL contract fragment: $required_sql_fragment" >&2
    exit 1
  fi
done

for required_manifest_fragment in \
  "SET SESSION group_concat_max_len = 65535" \
  "COLLATE utf8mb4_bin" \
  "ORDER BY primary_sku.price ASC, primary_sku.id ASC"; do
  if ! grep -Fq "$required_manifest_fragment" "$MANIFEST_SQL"; then
    echo "Missing manifest SQL contract fragment: $required_manifest_fragment" >&2
    exit 1
  fi
done

if ! grep -Fq '.aggregateType("order")' "$ORDER_SERVICE"; then
  echo "Order aggregate type must remain lowercase order" >&2
  exit 1
fi

echo "Store ES static contract validation passed"
