#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <connect-url> <pause|resume>" >&2
  exit 2
fi

CONNECT_URL=${1%/}
ACTION=$2
CONNECTOR_NAME=payment-outbox-connector

case "$ACTION" in
  pause) EXPECTED_STATE=PAUSED ;;
  resume) EXPECTED_STATE=RUNNING ;;
  *)
    echo "Action must be pause or resume" >&2
    exit 2
    ;;
esac

for command_name in curl jq; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Required command is missing: $command_name" >&2
    exit 1
  }
done

http_status=$(curl --silent --show-error \
  --output /dev/null \
  --write-out '%{http_code}' \
  --request PUT "$CONNECT_URL/connectors/$CONNECTOR_NAME/$ACTION")
case "$http_status" in
  200|202|204) ;;
  *)
    echo "Connector state change failed: name=$CONNECTOR_NAME action=$ACTION http=$http_status" >&2
    exit 1
    ;;
esac

for attempt in $(seq 1 30); do
  status=$(curl --silent --show-error --fail "$CONNECT_URL/connectors/$CONNECTOR_NAME/status")
  if [ "$EXPECTED_STATE" = "PAUSED" ]; then
    state_matches=$(jq -e '
      .connector.state == "PAUSED" and
      (.tasks | length > 0) and
      all(.tasks[]; .state == "PAUSED")
    ' >/dev/null <<<"$status" && printf true || printf false)
  else
    state_matches=$(jq -e '
      .connector.state == "RUNNING" and
      (.tasks | length > 0) and
      all(.tasks[]; .state == "RUNNING")
    ' >/dev/null <<<"$status" && printf true || printf false)
  fi

  if [ "$state_matches" = "true" ]; then
    echo "Connector reached $EXPECTED_STATE: $CONNECTOR_NAME"
    exit 0
  fi
  sleep 2
done

echo "Connector did not reach $EXPECTED_STATE within 60 seconds: $CONNECTOR_NAME" >&2
exit 1
