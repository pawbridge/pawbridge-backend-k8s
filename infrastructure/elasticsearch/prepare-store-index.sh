#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <elasticsearch-url> <new-version-index>" >&2
  echo "Example: $0 http://localhost:9200 store-products-v001" >&2
  exit 2
fi

ELASTICSEARCH_URL=${1%/}
INDEX_NAME=$2
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
MAPPING_FILE="$SCRIPT_DIR/mappings/store-index-mapping.json"

if [[ ! "$INDEX_NAME" =~ ^store-products-v[0-9]+$ ]]; then
  echo "Refusing unexpected index name: $INDEX_NAME" >&2
  exit 2
fi

for command_name in curl jq; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Required command is missing: $command_name" >&2
    exit 1
  }
done

curl --silent --show-error --fail "$ELASTICSEARCH_URL" >/dev/null

if curl --silent --output /dev/null --head --fail "$ELASTICSEARCH_URL/$INDEX_NAME"; then
  echo "Refusing to overwrite existing index: $INDEX_NAME" >&2
  exit 2
fi

response=$(curl --silent --show-error --fail \
  --request PUT "$ELASTICSEARCH_URL/$INDEX_NAME" \
  --header "Content-Type: application/json" \
  --data-binary "@$MAPPING_FILE")

if [ "$(jq -r '.acknowledged' <<<"$response")" != "true" ]; then
  echo "Elasticsearch did not acknowledge index creation: $response" >&2
  exit 1
fi

dynamic_mode=$(curl --silent --show-error --fail \
  "$ELASTICSEARCH_URL/$INDEX_NAME/_mapping" \
  | jq -r --arg index "$INDEX_NAME" '.[$index].mappings.dynamic')
if [ "$dynamic_mode" != "strict" ]; then
  echo "Unexpected mapping dynamic mode: $dynamic_mode" >&2
  exit 1
fi

echo "Created versioned Store index without deleting or switching aliases: $INDEX_NAME"
