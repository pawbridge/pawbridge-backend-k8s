#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "Usage: $0 <elasticsearch-url> <alias> <expected-current-index|-> <target-index>" >&2
  exit 2
fi

ELASTICSEARCH_URL=${1%/}
ALIAS_NAME=$2
EXPECTED_CURRENT_INDEX=$3
TARGET_INDEX=$4

for command_name in curl jq; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Required command is missing: $command_name" >&2
    exit 1
  }
done

case "$ALIAS_NAME" in
  store-products-read|store-products-write) ;;
  *)
    echo "Refusing unexpected alias name: $ALIAS_NAME" >&2
    exit 2
    ;;
esac

if [[ ! "$TARGET_INDEX" =~ ^store-products-v[0-9]+$ ]]; then
  echo "Refusing unexpected target index: $TARGET_INDEX" >&2
  exit 2
fi

if [ "$EXPECTED_CURRENT_INDEX" != "-" ] && [[ ! "$EXPECTED_CURRENT_INDEX" =~ ^store-products-v[0-9]+$ ]]; then
  echo "Refusing unexpected expected-current index: $EXPECTED_CURRENT_INDEX" >&2
  exit 2
fi

curl --silent --show-error --fail --head \
  "$ELASTICSEARCH_URL/$TARGET_INDEX" >/dev/null

alias_response_with_status=$(curl --silent --show-error \
  --write-out $'\n%{http_code}' \
  "$ELASTICSEARCH_URL/_alias/$ALIAS_NAME")
alias_response=${alias_response_with_status%$'\n'*}
alias_status=${alias_response_with_status##*$'\n'}
case "$alias_status" in
  200) current_indices=$(jq -r 'keys[]?' <<<"$alias_response" | sort) ;;
  404) current_indices="" ;;
  *)
    echo "Failed to inspect alias $ALIAS_NAME: HTTP $alias_status $alias_response" >&2
    exit 1
    ;;
esac

if [ "$EXPECTED_CURRENT_INDEX" = "-" ]; then
  if [ -n "$current_indices" ]; then
    echo "Alias already exists; expected none, found: $current_indices" >&2
    exit 2
  fi
  remove_action=""
else
  if [ "$current_indices" != "$EXPECTED_CURRENT_INDEX" ]; then
    echo "Alias source mismatch; expected $EXPECTED_CURRENT_INDEX, found: ${current_indices:-none}" >&2
    exit 2
  fi
  remove_action="{\"remove\":{\"index\":\"$EXPECTED_CURRENT_INDEX\",\"alias\":\"$ALIAS_NAME\"}},"
fi

write_flag=""
if [ "$ALIAS_NAME" = "store-products-write" ]; then
  write_flag=',"is_write_index":true'
fi

payload="{\"actions\":[${remove_action}{\"add\":{\"index\":\"$TARGET_INDEX\",\"alias\":\"$ALIAS_NAME\"${write_flag}}}]}"
response=$(curl --silent --show-error --fail \
  --request POST "$ELASTICSEARCH_URL/_aliases" \
  --header "Content-Type: application/json" \
  --data-binary "$payload")

if [ "$(jq -r '.acknowledged' <<<"$response")" != "true" ]; then
  echo "Elasticsearch did not acknowledge alias switch: $response" >&2
  exit 1
fi

verified_alias=$(curl --silent --show-error --fail "$ELASTICSEARCH_URL/_alias/$ALIAS_NAME")
if [ "$(jq -r --arg index "$TARGET_INDEX" 'keys == [$index]' <<<"$verified_alias")" != "true" ]; then
  echo "Alias verification failed after switch: $ALIAS_NAME" >&2
  exit 1
fi
if [ "$ALIAS_NAME" = "store-products-write" ] && \
   [ "$(jq -r --arg index "$TARGET_INDEX" --arg alias "$ALIAS_NAME" \
      '.[$index].aliases[$alias].is_write_index == true' <<<"$verified_alias")" != "true" ]; then
  echo "Write alias is missing is_write_index=true after switch" >&2
  exit 1
fi

echo "Alias switched atomically: $ALIAS_NAME -> $TARGET_INDEX"
