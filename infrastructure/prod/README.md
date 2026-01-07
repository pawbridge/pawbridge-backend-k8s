# Infrastructure 프로덕션 설정

이 폴더에는 **프로덕션 배포용** 설정 파일 템플릿이 있습니다.

## 구조

```
prod/
├── prometheus/              # Prometheus 설정
│   └── prometheus.yml
├── grafana/                 # Grafana 프로비저닝 설정
│   └── provisioning/
│       ├── datasources/     # 데이터소스 설정
│       └── dashboards/      # 대시보드 JSON 파일
└── connectors/              # Kafka Connect 커넥터 템플릿 (수동 등록용)
```

## Prometheus

Node 1에 자동 배포됩니다. Docker 네트워크 내 컨테이너 이름을 사용합니다.

## Kafka Connect 커넥터 (수동 등록)

커넥터 파일들은 **템플릿**입니다. 실제 등록 시 플레이스홀더를 변경하세요:

| 플레이스홀더 | 설명 |
|-------------|------|
| `<MYSQL_HOST>` | MySQL 서버 IP |
| `<MYSQL_USER>` | MySQL 사용자 |
| `<MYSQL_PASSWORD>` | MySQL 비밀번호 |
| `<KAFKA_BOOTSTRAP_SERVERS>` | Kafka 브로커 (예: 172.31.x.x:9092) |
| `<ELASTICSEARCH_HOST>` | Elasticsearch IP |

### 등록 방법

```bash
# 1. 템플릿 복사 후 값 변경
cp connectors/animal-outbox-connector.json /tmp/connector.json
# 에디터로 플레이스홀더 변경

# 2. Kafka Connect에 등록
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @/tmp/connector.json

# 3. 등록 확인
curl http://localhost:8083/connectors
```

## 로컬 개발용

로컬 개발용 설정은 각 인프라 폴더에 있습니다:
- `infrastructure/kafka/` - 로컬 Kafka 설정
- `infrastructure/monitoring/` - 로컬 Prometheus/Grafana 설정
