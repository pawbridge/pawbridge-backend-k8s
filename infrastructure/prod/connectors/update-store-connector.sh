#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <connect-url> <sink|source> <new-backup-file>" >&2
  exit 2
fi

CONNECT_URL=${1%/}
CONNECTOR_KIND=$2
BACKUP_FILE=$3

case "$CONNECTOR_KIND" in
  sink) CONNECTOR_NAME=store-es-sink-connector ;;
  source) CONNECTOR_NAME=store-outbox-connector ;;
  *)
    echo "Connector kind must be sink or source" >&2
    exit 2
    ;;
esac

for command_name in curl jq mktemp; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Required command is missing: $command_name" >&2
    exit 1
  }
done

if [ -e "$BACKUP_FILE" ]; then
  echo "Refusing to overwrite connector backup: $BACKUP_FILE" >&2
  exit 2
fi

temporary_config=$(mktemp)
temporary_response=$(mktemp)
trap 'rm -f "$temporary_config" "$temporary_response"' EXIT
chmod 600 "$temporary_config" "$temporary_response"

curl --silent --show-error --fail \
  "$CONNECT_URL/connectors/$CONNECTOR_NAME/config" >"$temporary_config"

umask 077
cp "$temporary_config" "$BACKUP_FILE"

if [ "$CONNECTOR_KIND" = "sink" ]; then
  jq '
    . + {
      "topics": "store.product-sku.events",
      "external.resource.usage": "ALIAS_INDEX",
      "topic.to.external.resource.mapping": "store.product-sku.events:store-products-write",
      "key.ignore": "false",
      "write.method": "INSERT",
      "behavior.on.null.values": "FAIL",
      "behavior.on.malformed.documents": "fail",
      "drop.invalid.message": "false",
      "errors.tolerance": "none",
      "errors.deadletterqueue.topic.name": "store.product-sku.es.dlq",
      "errors.deadletterqueue.topic.replication.factor": "1",
      "errors.deadletterqueue.context.headers.enable": "true",
      "errors.log.enable": "true",
      "errors.log.include.messages": "false"
    }
  ' "$temporary_config" >"$temporary_response"
else
  jq '
    . + {
      "transforms.outbox.table.field.event.key": "aggregate_id",
      "transforms.outbox.route.by.field": "aggregate_type",
      "transforms.outbox.route.topic.replacement": "store.${routedByValue}.events",
      "transforms.outbox.table.expand.json.payload": "true"
    }
  ' "$temporary_config" >"$temporary_response"
fi
mv "$temporary_response" "$temporary_config"
temporary_response=$(mktemp)
chmod 600 "$temporary_response"

http_status=$(curl --silent --show-error \
  --output "$temporary_response" \
  --write-out '%{http_code}' \
  --request PUT "$CONNECT_URL/connectors/$CONNECTOR_NAME/config" \
  --header 'Content-Type: application/json' \
  --data-binary "@$temporary_config")
if [ "$http_status" != "200" ] && [ "$http_status" != "201" ]; then
  echo "Connector update failed: name=$CONNECTOR_NAME http=$http_status" >&2
  exit 1
fi

for attempt in $(seq 1 30); do
  status=$(curl --silent --show-error --fail "$CONNECT_URL/connectors/$CONNECTOR_NAME/status")
  if jq -e '.connector.state == "RUNNING" and (.tasks | length > 0) and all(.tasks[]; .state == "RUNNING")' \
    >/dev/null <<<"$status"; then
    echo "Connector updated and running: $CONNECTOR_NAME"
    echo "Secret-bearing rollback backup: $BACKUP_FILE"
    exit 0
  fi
  sleep 2
done

echo "Connector did not reach RUNNING within 60 seconds: $CONNECTOR_NAME" >&2
exit 1
