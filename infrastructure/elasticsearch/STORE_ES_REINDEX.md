# Store Elasticsearch 재색인 계약

이 계약은 MySQL의 상품/SKU를 검색 projection의 원본으로 삼고, Kafka 재처리에도 SKU당 Elasticsearch 문서가 하나만 남도록 한다. 기존 `store.outbox.events` 인덱스는 롤백 원본이므로 자동 삭제하지 않는다.

## 고정 계약

| 항목 | 값 |
|---|---|
| 상품 Outbox `aggregate_type` | `product-sku` |
| 주문 Outbox `aggregate_type` | `order` |
| Debezium topic route | `store.${routedByValue}.events` |
| 상품 topic | `store.product-sku.events` |
| Kafka key / Elasticsearch `_id` | SKU ID 문자열 |
| sink write resource | `store-products-write` alias |
| 애플리케이션 read resource | `store-products-read` alias |
| 물리 index | `store-products-vNNN` |
| document write | 완전한 snapshot + `INSERT` |
| 대표 SKU | 최저가, 동가이면 낮은 SKU ID |
| 상태 | `ACTIVE`, `HIDDEN`, `SOLD_OUT`, `DELETED` |
| 실패 | malformed document/task fail, 오류 payload 로그 미출력 |

상품 topic만 Elasticsearch sink가 구독한다. `key.ignore=false`이므로 Debezium EventRouter가 `aggregate_id`에서 만든 Kafka key가 Elasticsearch `_id`가 된다. 같은 SKU가 재전달돼도 같은 문서를 완전히 교체한다.

물리 SKU 삭제는 현재 지원하지 않는다. 상품 삭제는 `status=DELETED`인 완전한 snapshot으로 처리한다. 추후 물리 삭제 API를 만들 때만 null payload/tombstone 계약을 별도로 추가한다.

대표 SKU는 재고와 무관하다. `inStockOnly`는 대표 SKU 재고가 아니라 projection의 `totalStockQuantity`를 기준으로 해야 하므로, 모든 SKU 문서가 같은 상품 총재고를 가진다.

## 최초 전환 순서

MySQL이 전체 재생성의 유일한 원본이다. 상품 topic은 `compact,delete`와 30일 보존이므로 Kafka replay만으로 전체 상품을 복구한다고 가정하지 않는다.

### 0. 입력값과 사전 백업

수정 Store 이미지는 `latest`나 재사용 가능한 tag가 아니라 registry에 push된 immutable digest로 지정한다. 아래 경로와 digest는 실제 값으로 바꾸며, `CUTOVER_DIR`에는 connector credential이 포함되므로 공유·커밋하지 않는다.

```bash
BACKEND_ROOT=/path/to/pawbridge-backend-k8s
INFRA_ROOT=/path/to/pawbridge-infra-k8s
ENVIRONMENT=dev
CUTOVER_DIR=/secure/path/store-es-v001
STORE_IMAGE_REPOSITORY=dorosiya/pawbridge-store-service
STORE_IMAGE_DIGEST=sha256:REPLACE_WITH_64_HEX
MYSQL_USER=REPLACE_WITH_MYSQL_USER

install -d -m 700 "$CUTOVER_DIR"
printf '%s\n' "$STORE_IMAGE_DIGEST" | grep -Eq '^sha256:[0-9a-f]{64}$'

kubectl get deployment/store-service -n pawbridge \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="store-service")].image}{"\n"}' \
  > "$CUTOVER_DIR/store-service-image.before-v001.txt"
helm history store-service -n pawbridge -o json \
  | jq -r 'map(select(.status == "deployed")) | last | .revision' \
  > "$CUTOVER_DIR/store-service-revision.before-v001.txt"
chmod 600 \
  "$CUTOVER_DIR/store-service-image.before-v001.txt" \
  "$CUTOVER_DIR/store-service-revision.before-v001.txt"
```

세 HPA는 API 응답 원본을 그대로 저장하지 않는다. 재생성을 방해하는 `resourceVersion`, `uid`, `status` 등의 서버 필드를 제거한 manifest만 저장한다.

```bash
for workload in api-gateway payment-service store-service; do
  kubectl get hpa "$workload" -n pawbridge -o json \
    | jq 'del(
        .metadata.annotations."kubectl.kubernetes.io/last-applied-configuration",
        .metadata.creationTimestamp,
        .metadata.generation,
        .metadata.managedFields,
        .metadata.resourceVersion,
        .metadata.uid,
        .status
      )' \
    > "$CUTOVER_DIR/$workload-hpa.json"
  chmod 600 "$CUTOVER_DIR/$workload-hpa.json"
  jq -e 'has("status") | not' "$CUTOVER_DIR/$workload-hpa.json" >/dev/null
done

helm template store-service "$INFRA_ROOT/charts/store-service" \
  -n pawbridge \
  -f "$INFRA_ROOT/environments/$ENVIRONMENT/values/store-service.yaml" \
  --set-string image.repository="$STORE_IMAGE_REPOSITORY" \
  --set-string image.digest="$STORE_IMAGE_DIGEST" \
  > "$CUTOVER_DIR/store-service-v001.rendered.yaml"
grep -F "image: \"$STORE_IMAGE_REPOSITORY@$STORE_IMAGE_DIGEST\"" \
  "$CUTOVER_DIR/store-service-v001.rendered.yaml"
```

쓰기 동결 전에 별도 터미널에서 Connect port-forward를 시작하고 전환이 끝날 때까지 유지한다.

```bash
kubectl port-forward -n kafka svc/debezium-connect 18083:8083
```

### 1. 쓰기 동결

이 전환은 잠깐의 전체 Store 쓰기 중단을 전제로 한다. 외부 진입점인 API Gateway를 먼저 내리고, 결제 이벤트 생산자인 Payment를 내린다. Store는 아직 유지하여 이미 발행된 `payment.events`/`payment`를 모두 처리한 뒤 마지막으로 내린다.

```bash
kubectl delete hpa api-gateway payment-service -n pawbridge
kubectl scale deployment/api-gateway deployment/payment-service \
  -n pawbridge --replicas=0
kubectl wait --for=delete pod -l app=api-gateway -n pawbridge --timeout=180s
kubectl wait --for=delete pod -l app=payment-service -n pawbridge --timeout=180s

"$BACKEND_ROOT/infrastructure/prod/connectors/set-payment-outbox-state.sh" \
  http://127.0.0.1:18083 pause

PAYMENT_LAG=$(
  kubectl exec -n kafka pawbridge-kafka-0 -- \
    bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --group payment-group --describe \
  | awk '$2 ~ /^payment(\.events)?$/ {
      rows += 1
      if ($3 !~ /^[0-9]+$/ || $6 !~ /^[0-9]+$/) invalid = 1
      else lag += $6
    }
    END { if (rows == 0 || invalid) exit 2; print lag + 0 }'
)
test "$PAYMENT_LAG" -eq 0

kubectl delete hpa store-service -n pawbridge
kubectl scale deployment/store-service -n pawbridge --replicas=0
kubectl wait --for=delete pod -l app=store-service -n pawbridge --timeout=180s

kubectl get deployment api-gateway payment-service store-service -n pawbridge \
  -o custom-columns=NAME:.metadata.name,DESIRED:.spec.replicas,READY:.status.readyReplicas
```

세 Deployment의 `DESIRED`가 모두 0인지 확인한다. `payment-outbox-connector`가 `PAUSED`인지, `payment-group` lag가 같은 엄격한 검사에서 다시 0인지 확인한다. connector가 멈춘 뒤의 Payment DB outbox는 아직 Kafka로 나오지 않아도 되며, 이 fence 때문에 재색인 중 상품 delta를 만들 수 없다. 기존 sink consumer group `connect-store-es-sink-connector` lag가 0이고 Store source/sink connector와 task가 모두 `RUNNING`인지도 확인한다. 이 시점부터 Payment connector를 재개할 때까지 상품/SKU MySQL 데이터가 바뀌면 절차를 중단하고 처음부터 다시 시작한다.

### 2. topic과 version index 준비

기존 클러스터에도 topic CR을 적용하고 Ready를 확인한다.

```bash
kubectl apply -f "$INFRA_ROOT/infra/kafka/store-topics.yaml"
kubectl wait kafkatopic/store.product-sku.events kafkatopic/store.order.events \
  kafkatopic/store.product-sku.es.dlq -n kafka --for=condition=Ready --timeout=180s
```

별도 터미널에서 Elasticsearch와 MySQL port-forward를 유지한다. Connect port-forward는 쓰기 동결 전에 시작한 것을 계속 사용한다. 이후 명령은 클러스터 DNS가 아니라 이 로컬 포트를 사용한다.

```bash
kubectl port-forward -n databases svc/elasticsearch-master 19200:9200
kubectl port-forward -n databases svc/mysql 13306:3306
```

새 물리 index를 만든다. 기존 index를 삭제하지 않는다.

```bash
"$BACKEND_ROOT/infrastructure/elasticsearch/prepare-store-index.sh" \
  http://127.0.0.1:19200 \
  store-products-v001
```

### 3. 쓰기 경로만 신규 index로 전환

검증 전에는 read alias를 만들지 않는다. write alias만 연결하고 sink를 먼저 갱신한다. 갱신 스크립트는 기존 host/credential을 그대로 보존하고, 원본 config를 권한 600 backup으로 남긴다.

```bash
"$BACKEND_ROOT/infrastructure/elasticsearch/switch-store-alias.sh" \
  http://127.0.0.1:19200 \
  store-products-write - store-products-v001

"$BACKEND_ROOT/infrastructure/prod/connectors/update-store-connector.sh" \
  http://127.0.0.1:18083 sink \
  "$CUTOVER_DIR/store-es-sink.before-v001.json"
```

sink/task가 `RUNNING`이고 아직 비어 있는 `store.product-sku.events`의 lag가 0임을 확인한 다음 source route를 갱신한다.

```bash
"$BACKEND_ROOT/infrastructure/prod/connectors/update-store-connector.sh" \
  http://127.0.0.1:18083 source \
  "$CUTOVER_DIR/store-outbox.before-v001.json"
```

### 4. MySQL snapshot 발행과 원본 대조

수정 Store 앱은 아직 기동하지 않는다. `sync-to-es.sql`을 정확히 한 번 실행하고, 동일한 쓰기 동결 안에서 MySQL canonical manifest를 만든다.

```bash
mysql -h 127.0.0.1 -P 13306 -u "$MYSQL_USER" -p pawbridge_store \
  < "$BACKEND_ROOT/store-service/src/main/resources/sync-to-es.sql"

umask 077
mysql --batch --raw --skip-column-names -h 127.0.0.1 -P 13306 -u "$MYSQL_USER" -p pawbridge_store \
  < "$BACKEND_ROOT/store-service/src/main/resources/store-es-validation-manifest.sql" \
  > "$CUTOVER_DIR/store-products-v001.jsonl"
chmod 600 "$CUTOVER_DIR/store-products-v001.jsonl"
```

source/sink task가 모두 `RUNNING`, sink lag 0, DLQ end offset 0인지 확인한 뒤 MySQL manifest와 ES를 필드 단위로 대조한다. 최초 검증에는 `--expect-read-alias`를 넣지 않는다.

```bash
for connector in store-outbox-connector store-es-sink-connector; do
  curl --silent --show-error --fail "http://127.0.0.1:18083/connectors/$connector/status" \
    | jq -e '.connector.state == "RUNNING" and (.tasks | length > 0) and all(.tasks[]; .state == "RUNNING")'
done

kubectl exec -n kafka pawbridge-kafka-0 -- \
  bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group connect-store-es-sink-connector --describe
kubectl exec -n kafka pawbridge-kafka-0 -- \
  bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
  --topic store.product-sku.es.dlq

python3 "$BACKEND_ROOT/infrastructure/elasticsearch/validate-store-reindex.py" \
  --url http://127.0.0.1:19200 \
  --index store-products-v001 \
  --manifest "$CUTOVER_DIR/store-products-v001.jsonl"
```

validator는 SKU별 ID, 상품/카테고리/옵션명, 가격, 재고, 총재고, 대표 여부, 상태, 이미지, 생성 시각과 write alias의 `is_write_index=true`를 확인한다. `updatedAt`은 snapshot 발행 시각이므로 존재와 타입은 mapping으로 검증하되 MySQL 값과 동일성을 비교하지 않는다.

### 5. 읽기 전환과 쓰기 재개

위 검증이 모두 성공한 뒤에만 read alias를 만들고 alias를 직접 다시 검증한다. API Gateway와 Payment는 계속 0개이므로 이 시점에도 외부 쓰기와 결제 이벤트는 재개되지 않는다.

```bash
"$BACKEND_ROOT/infrastructure/elasticsearch/switch-store-alias.sh" \
  http://127.0.0.1:19200 \
  store-products-read - store-products-v001

python3 "$BACKEND_ROOT/infrastructure/elasticsearch/validate-store-reindex.py" \
  --url http://127.0.0.1:19200 \
  --index store-products-v001 \
  --manifest "$CUTOVER_DIR/store-products-v001.jsonl" \
  --expect-read-alias
```

수정 Store만 digest로 배포한다. `deploy-all.sh`는 다른 서비스를 함께 재배포하고 기본 `k8s-v1` tag를 사용할 수 있으므로 이 전환에는 사용하지 않는다. HPA는 아직 만들지 않고 1개 pod만 올린다.

```bash
helm upgrade --install store-service "$INFRA_ROOT/charts/store-service" \
  -n pawbridge \
  -f "$INFRA_ROOT/environments/$ENVIRONMENT/values/store-service.yaml" \
  --set-string image.repository="$STORE_IMAGE_REPOSITORY" \
  --set-string image.digest="$STORE_IMAGE_DIGEST" \
  --set autoscaling.enabled=false \
  --set replicaCount=1 \
  --atomic --wait --timeout 5m

kubectl rollout status deployment/store-service -n pawbridge --timeout=300s
test "$(kubectl get deployment/store-service -n pawbridge \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="store-service")].image}')" \
  = "$STORE_IMAGE_REPOSITORY@$STORE_IMAGE_DIGEST"
kubectl get pod -n pawbridge -l app=store-service \
  -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.containerStatuses[?(@.name=="store-service")].imageID}{"\n"}{end}' \
  | grep -F "$STORE_IMAGE_DIGEST"
```

별도 터미널에서 Store를 직접 port-forward하고, 검색 결과가 단순 HTTP 200이 아니라 MySQL manifest의 `ACTIVE` 대표 SKU 수와 같은지도 확인한다. 현재 검색 구현은 Elasticsearch 오류를 빈 200 응답으로 바꾸므로 이 수량 검증을 생략하면 안 된다.

```bash
kubectl port-forward -n pawbridge svc/store-service 18084:8083
```

```bash
EXPECTED_SEARCH_TOTAL=$(jq -s \
  '[.[] | select(.isPrimarySku == true and .status == "ACTIVE")] | length' \
  "$CUTOVER_DIR/store-products-v001.jsonl")
curl --silent --show-error --fail \
  'http://127.0.0.1:18084/api/v1/products?page=0&size=1' \
  > "$CUTOVER_DIR/store-search-v001.json"
jq -e --argjson expected "$EXPECTED_SEARCH_TOTAL" \
  '.totalCount == $expected and (.items | length) <= 1' \
  "$CUTOVER_DIR/store-search-v001.json"
```

검색 표본까지 성공하면 Store HPA를 Helm으로 재생성하고 확인한다. 이때까지 Payment와 API Gateway는 계속 중단 상태이므로 아직 안전 롤백 구간이다.

```bash
helm upgrade store-service "$INFRA_ROOT/charts/store-service" \
  -n pawbridge \
  -f "$INFRA_ROOT/environments/$ENVIRONMENT/values/store-service.yaml" \
  --set-string image.repository="$STORE_IMAGE_REPOSITORY" \
  --set-string image.digest="$STORE_IMAGE_DIGEST" \
  --set autoscaling.enabled=true \
  --set replicaCount=1 \
  --atomic --wait --timeout 5m
kubectl get deployment,pod,hpa -n pawbridge -l app=store-service
```

여기서 운영자가 전환 성공을 확인한 뒤에만 Payment outbox fence를 잠깐 연다. **`payment-outbox-connector`를 처음 `resume`하는 명령이 간편 롤백의 종료 지점**이다. Payment 앱과 API Gateway는 아직 0개지만, DB에 남아 있던 결제 outbox가 Store의 재고와 상품 snapshot을 바꿀 수 있기 때문이다.

```bash
"$BACKEND_ROOT/infrastructure/prod/connectors/set-payment-outbox-state.sh" \
  http://127.0.0.1:18083 resume

PAYMENT_LAG=$(
  kubectl exec -n kafka pawbridge-kafka-0 -- \
    bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --group payment-group --describe \
  | awk '$2 ~ /^payment(\.events)?$/ {
      rows += 1
      if ($3 !~ /^[0-9]+$/ || $6 !~ /^[0-9]+$/) invalid = 1
      else lag += $6
    }
    END { if (rows == 0 || invalid) exit 2; print lag + 0 }'
)
test "$PAYMENT_LAG" -eq 0

"$BACKEND_ROOT/infrastructure/prod/connectors/set-payment-outbox-state.sh" \
  http://127.0.0.1:18083 pause

PAYMENT_LAG=$(
  kubectl exec -n kafka pawbridge-kafka-0 -- \
    bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --group payment-group --describe \
  | awk '$2 ~ /^payment(\.events)?$/ {
      rows += 1
      if ($3 !~ /^[0-9]+$/ || $6 !~ /^[0-9]+$/) invalid = 1
      else lag += $6
    }
    END { if (rows == 0 || invalid) exit 2; print lag + 0 }'
)
test "$PAYMENT_LAG" -eq 0
```

pause 완료 뒤에도 lag 0이어야 한다. Store가 결제 이벤트를 DB에 반영한 뒤 Store outbox→상품 topic→ES sink까지 끝났는지도 다시 확인한다.

```bash
for connector in store-outbox-connector store-es-sink-connector; do
  curl --silent --show-error --fail "http://127.0.0.1:18083/connectors/$connector/status" \
    | jq -e '.connector.state == "RUNNING" and (.tasks | length > 0) and all(.tasks[]; .state == "RUNNING")'
done
kubectl exec -n kafka pawbridge-kafka-0 -- \
  bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group connect-store-es-sink-connector --describe \
| awk '$2 == "store.product-sku.events" {
    rows += 1
    if ($3 !~ /^[0-9]+$/ || $6 !~ /^[0-9]+$/) invalid = 1
    else lag += $6
  }
  END {
    if (rows == 0 || invalid || lag != 0) exit 2
    print "store ES sink lag=0"
  }'
```

sink lag가 0이면 최신 MySQL manifest를 다시 만들고 ES 전체 필드 및 검색 결과를 재검증한다. 최초 manifest와 값이 달라도 이 단계의 최신 manifest와 ES가 정확히 같아야 한다.

```bash
mysql --batch --raw --skip-column-names -h 127.0.0.1 -P 13306 \
  -u "$MYSQL_USER" -p pawbridge_store \
  < "$BACKEND_ROOT/store-service/src/main/resources/store-es-validation-manifest.sql" \
  > "$CUTOVER_DIR/store-products-v001.post-payment.jsonl"
chmod 600 "$CUTOVER_DIR/store-products-v001.post-payment.jsonl"

python3 "$BACKEND_ROOT/infrastructure/elasticsearch/validate-store-reindex.py" \
  --url http://127.0.0.1:19200 \
  --index store-products-v001 \
  --manifest "$CUTOVER_DIR/store-products-v001.post-payment.jsonl" \
  --expect-read-alias

EXPECTED_SEARCH_TOTAL=$(jq -s \
  '[.[] | select(.isPrimarySku == true and .status == "ACTIVE")] | length' \
  "$CUTOVER_DIR/store-products-v001.post-payment.jsonl")
curl --silent --show-error --fail \
  'http://127.0.0.1:18084/api/v1/products?page=0&size=1' \
  > "$CUTOVER_DIR/store-search-v001.post-payment.json"
jq -e --argjson expected "$EXPECTED_SEARCH_TOTAL" \
  '.totalCount == $expected and (.items | length) <= 1' \
  "$CUTOVER_DIR/store-search-v001.post-payment.json"
```

이 재검증까지 성공한 뒤 Payment connector를 정상 재개하고 Payment를 준비시킨다. HPA를 복원한 다음, 외부 트래픽이 들어오는 API Gateway를 마지막에 올린다.

```bash
"$BACKEND_ROOT/infrastructure/prod/connectors/set-payment-outbox-state.sh" \
  http://127.0.0.1:18083 resume

kubectl scale deployment/payment-service -n pawbridge --replicas=1
kubectl rollout status deployment/payment-service -n pawbridge --timeout=300s
kubectl apply -f "$CUTOVER_DIR/payment-service-hpa.json"

kubectl scale deployment/api-gateway -n pawbridge --replicas=1
kubectl rollout status deployment/api-gateway -n pawbridge --timeout=300s
kubectl apply -f "$CUTOVER_DIR/api-gateway-hpa.json"

kubectl get deployment,pod,hpa -n pawbridge \
  -l 'app in (store-service,payment-service,api-gateway)'
```

### 최초 전환 실패 시 롤백

read alias 전 검증 실패라면 앱은 계속 중단된 상태이고 구 index도 그대로다. sink/source를 백업 config로 복원한다. 복원 helper는 credential이 포함된 응답 body를 권한 600 임시 파일로만 받고 터미널에 출력하지 않는다.

```bash
"$BACKEND_ROOT/infrastructure/prod/connectors/restore-store-connector.sh" \
  http://127.0.0.1:18083 sink \
  "$CUTOVER_DIR/store-es-sink.before-v001.json"
"$BACKEND_ROOT/infrastructure/prod/connectors/restore-store-connector.sh" \
  http://127.0.0.1:18083 source \
  "$CUTOVER_DIR/store-outbox.before-v001.json"
```

수정 Store를 배포하기 전 실패라면 기존 Deployment를 1개로 올리고 정제한 HPA를 복원한 뒤 Payment connector를 반드시 재개한다.

```bash
kubectl scale deployment/store-service -n pawbridge --replicas=1
kubectl rollout status deployment/store-service -n pawbridge --timeout=300s
kubectl apply -f "$CUTOVER_DIR/store-service-hpa.json"

"$BACKEND_ROOT/infrastructure/prod/connectors/set-payment-outbox-state.sh" \
  http://127.0.0.1:18083 resume
```

read alias 전환 또는 수정 Store 검증 뒤 실패했지만 아직 `payment-outbox-connector`를 재개하지 않았다면 Store를 다시 0으로 내린 뒤 connector를 위 helper로 복원하고, 기록한 Helm revision으로 돌아간다. API Gateway와 Payment가 계속 0개이고 Payment connector가 `PAUSED`, `payment-group` lag가 0이므로 이 구간에는 상품 delta가 없어야 한다.

```bash
kubectl delete hpa store-service -n pawbridge --ignore-not-found
kubectl scale deployment/store-service -n pawbridge --replicas=0
kubectl wait --for=delete pod -l app=store-service -n pawbridge --timeout=180s

PREVIOUS_STORE_REVISION=$(cat "$CUTOVER_DIR/store-service-revision.before-v001.txt")
helm rollback store-service "$PREVIOUS_STORE_REVISION" \
  -n pawbridge --wait --timeout 5m
kubectl rollout status deployment/store-service -n pawbridge --timeout=300s
test "$(kubectl get deployment/store-service -n pawbridge \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="store-service")].image}')" \
  = "$(cat "$CUTOVER_DIR/store-service-image.before-v001.txt")"

"$BACKEND_ROOT/infrastructure/prod/connectors/set-payment-outbox-state.sh" \
  http://127.0.0.1:18083 resume
```

두 간편 롤백 분기 모두 Payment connector가 `RUNNING`인 상태에서 strict `payment-group` lag 0을 확인하고, Payment와 API Gateway를 다음 순서로 복원한다.

```bash
PAYMENT_LAG=$(
  kubectl exec -n kafka pawbridge-kafka-0 -- \
    bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --group payment-group --describe \
  | awk '$2 ~ /^payment(\.events)?$/ {
      rows += 1
      if ($3 !~ /^[0-9]+$/ || $6 !~ /^[0-9]+$/) invalid = 1
      else lag += $6
    }
    END { if (rows == 0 || invalid) exit 2; print lag + 0 }'
)
test "$PAYMENT_LAG" -eq 0

kubectl scale deployment/payment-service -n pawbridge --replicas=1
kubectl rollout status deployment/payment-service -n pawbridge --timeout=300s
kubectl apply -f "$CUTOVER_DIR/payment-service-hpa.json"

kubectl scale deployment/api-gateway -n pawbridge --replicas=1
kubectl rollout status deployment/api-gateway -n pawbridge --timeout=300s
kubectl apply -f "$CUTOVER_DIR/api-gateway-hpa.json"
```

신규 v001과 alias는 원인 분석이 끝날 때까지 삭제하지 않는다.

Payment connector를 처음 재개한 뒤에는 새 이벤트가 MySQL/outbox에 반영될 수 있으므로 위 간편 롤백을 사용하면 안 된다. 사후 manifest 검증에 실패하면 Payment connector를 다시 `PAUSED`로 유지하고 새 index를 고친 뒤 MySQL 전체 snapshot을 재발행한다. 이 지점을 지난 구 pipeline 롤백은 다시 전체 쓰기를 동결하고, 목표 write index로 MySQL 전체 snapshot을 재발행하여 delta까지 복구한 뒤 read alias를 바꾸는 별도 재색인으로 수행한다.


## 다음 버전 재색인

1. `store-products-v002`를 새로 만든다.
2. 쓰기를 잠시 중단하고 lag를 0으로 만든다.
3. write alias만 v002로 원자 전환한다.
4. `sync-to-es.sql`로 전체 snapshot을 발행하고 MySQL canonical manifest를 생성한다.
5. manifest로 v002를 검증한다. 이 동안 read alias는 v001을 계속 가리킨다.
6. 검증 성공 후 read alias를 v002로 원자 전환한다.
7. 롤백 기간이 끝날 때까지 v001을 삭제하지 않는다.

검증 실패 시 read alias는 건드리지 않는다. write alias를 이전 index로 되돌린 뒤 전체 snapshot을 다시 발행해야 실패 구간의 변경분까지 복구된다.

## 필수 검증

- MySQL SKU 수 = ES document 수 = 고유 `skuId` 수
- 모든 `_id` = 해당 문서 `skuId`
- 상품별 `isPrimarySku=true` 정확히 하나
- 대표 SKU가 `(price ASC, skuId ASC)` 첫 번째 SKU
- 같은 상품의 모든 문서가 동일하고 정확한 `totalStockQuantity`
- 상태가 허용된 enum 네 값 중 하나
- ORDER 형태 문서와 예전 `{deleted:true}` 부분 문서 0개
- 같은 SKU snapshot 재발행 전후 문서 수 동일
- source/sink lag 0, DLQ 0, connector/task 모두 `RUNNING`
