#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <connect-url> <sink|source> <backup-file>" >&2
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

if [ ! -f "$BACKUP_FILE" ]; then
  echo "Connector backup does not exist: $BACKUP_FILE" >&2
  exit 2
fi

jq -e 'type == "object" and has("connector.class")' "$BACKUP_FILE" >/dev/null || {
  echo "Connector backup is not a connector config object: $BACKUP_FILE" >&2
  exit 2
}

temporary_response=$(mktemp)
trap 'rm -f "$temporary_response"' EXIT
chmod 600 "$temporary_response"

http_status=$(curl --silent --show-error \
  --output "$temporary_response" \
  --write-out '%{http_code}' \
  --request PUT "$CONNECT_URL/connectors/$CONNECTOR_NAME/config" \
  --header 'Content-Type: application/json' \
  --data-binary "@$BACKUP_FILE")
case "$http_status" in
  200|201|202) ;;
  *)
    echo "Connector restore failed: name=$CONNECTOR_NAME http=$http_status" >&2
    exit 1
    ;;
esac

for attempt in $(seq 1 30); do
  status=$(curl --silent --show-error --fail "$CONNECT_URL/connectors/$CONNECTOR_NAME/status")
  if jq -e '.connector.state == "RUNNING" and (.tasks | length > 0) and all(.tasks[]; .state == "RUNNING")' \
    >/dev/null <<<"$status"; then
    echo "Connector restored and running: $CONNECTOR_NAME"
    exit 0
  fi
  sleep 2
done

echo "Connector did not reach RUNNING within 60 seconds: $CONNECTOR_NAME" >&2
exit 1
