#!/bin/bash

# =================================================================
# [PawBridge] CDC & Elasticsearch Production Setup Script (Node 5)
# =================================================================

# 1. 환경 변수 확인
if [ -z "$NODE2_DB_IP" ] || [ -z "$NODE3_KAFKA_IP" ]; then
    echo "❌ 에러: NODE2_DB_IP 또는 NODE3_KAFKA_IP 환경변수가 설정되지 않았습니다."
    echo "   사용법: export NODE2_DB_IP=... export NODE3_KAFKA_IP=... && ./setup_prod_cdc.sh"
    exit 1
fi

CONNECT_URL="http://localhost:8083/connectors"
ES_URL="http://localhost:9200"

echo "🚀 CDC 프로덕션 설정 시작..."
echo "   - DB(Node 2): $NODE2_DB_IP"
echo "   - Kafka(Node 3): $NODE3_KAFKA_IP"
echo "   - Connect: $CONNECT_URL"
echo ""

# 2. Elasticsearch 인덱스 설정 (Nori 적용)
echo "🔍 1. Elasticsearch 인덱스 설정 (setup-index.sh 실행)..."
chmod +x ./elasticsearch/setup-index.sh
cd elasticsearch
./setup-index.sh
cd ..
echo ""

# 3. Connector 등록 함수
register_connector() {
    local NAME=$1
    local FILE=$2
    
    echo "🔨 Connector 등록 중: $NAME"
    
    # JSON 파일 읽어서 변수 치환 (envsubst 대신 sed 사용 - 호환성)
    # db-server -> NODE2_DB_IP
    # kafka-broker -> NODE3_KAFKA_IP
    # localhost -> NODE3_KAFKA_IP (for bootstrap)
    
    CONFIG_CONTENT=$(cat $FILE)
    CONFIG_CONTENT=${CONFIG_CONTENT//db-server/$NODE2_DB_IP}
    CONFIG_CONTENT=${CONFIG_CONTENT//kafka-broker/$NODE3_KAFKA_IP}
    CONFIG_CONTENT=${CONFIG_CONTENT//29092/9092} # Port 변경
    
    # 등록 요청
    RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" -d "$CONFIG_CONTENT" "$CONNECT_URL")
    
    echo "   결과: $RESPONSE"
}

# 4. Connector 등록 실행
echo "🔍 2. Kafka Connectors 등록..."

# 4-1. MySQL Source Connector
register_connector "animal-animals-connector" "./kafka/connectors/animal-animals-connector.json"

# 4-2. Elasticsearch Sink Connector
register_connector "elasticsearch-sink-v3" "./kafka/connectors/elasticsearch-sink-connector-v3.json"

echo ""
echo "🎉 모든 설정 요청 완료! (http://(Node5_Public_IP):8083/connectors 에서 확인 가능)"
