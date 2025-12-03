#!/bin/bash

# Elasticsearch animals 인덱스 설정 스크립트
# - Nori Analyzer 설정 적용
# - 인덱스 매핑 설정

ELASTICSEARCH_URL="http://localhost:9200"
INDEX_NAME="animals"
MAPPING_FILE="./mappings/animals-index-mapping.json"

echo "📋 Elasticsearch 인덱스 설정 시작..."
echo "   - URL: $ELASTICSEARCH_URL"
echo "   - Index: $INDEX_NAME"
echo ""

# 1. Elasticsearch 연결 확인
echo "🔍 Elasticsearch 연결 확인..."
if ! curl -s "$ELASTICSEARCH_URL" > /dev/null 2>&1; then
    echo "❌ Elasticsearch 연결 실패: $ELASTICSEARCH_URL"
    echo "   Elasticsearch가 실행 중인지 확인하세요."
    exit 1
fi
echo "✅ Elasticsearch 연결 성공"
echo ""

# 2. 기존 인덱스 확인
echo "🔍 기존 인덱스 확인..."
if curl -s -f "$ELASTICSEARCH_URL/$INDEX_NAME" > /dev/null 2>&1; then
    echo "⚠️  기존 인덱스 발견: $INDEX_NAME"
    read -p "   기존 인덱스를 삭제하고 재생성하시겠습니까? (y/N): " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "🗑️  기존 인덱스 삭제 중..."
        DELETE_RESULT=$(curl -s -X DELETE "$ELASTICSEARCH_URL/$INDEX_NAME")
        echo "   $DELETE_RESULT"
        echo "✅ 기존 인덱스 삭제 완료"
    else
        echo "❌ 작업 취소됨"
        exit 0
    fi
else
    echo "ℹ️  기존 인덱스 없음"
fi
echo ""

# 3. 새 인덱스 생성
echo "🔨 새 인덱스 생성 중..."
echo "   - Mapping file: $MAPPING_FILE"

CREATE_RESULT=$(curl -s -X PUT "$ELASTICSEARCH_URL/$INDEX_NAME" \
    -H "Content-Type: application/json" \
    -d @"$MAPPING_FILE")

if echo "$CREATE_RESULT" | grep -q '"acknowledged":true'; then
    echo "✅ 인덱스 생성 성공!"
    echo ""
    echo "$CREATE_RESULT" | python -m json.tool 2>/dev/null || echo "$CREATE_RESULT"
else
    echo "❌ 인덱스 생성 실패"
    echo "$CREATE_RESULT"
    exit 1
fi

echo ""
echo "🎉 인덱스 설정 완료!"
echo ""
echo "📊 인덱스 정보 확인:"
curl -s "$ELASTICSEARCH_URL/$INDEX_NAME/_settings,_mappings" | python -m json.tool 2>/dev/null || curl -s "$ELASTICSEARCH_URL/$INDEX_NAME/_settings,_mappings"
