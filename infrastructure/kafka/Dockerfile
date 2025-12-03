# Confluent Kafka Connect + Debezium MySQL Connector + Elasticsearch Sink Connector
FROM confluentinc/cp-kafka-connect:7.5.0

USER root

# 1. Debezium MySQL Connector 수동 다운로드 및 설치 (Maven Central)
RUN curl -L https://repo1.maven.org/maven2/io/debezium/debezium-connector-mysql/2.4.0.Final/debezium-connector-mysql-2.4.0.Final-plugin.tar.gz \
    -o /tmp/debezium-mysql.tar.gz && \
    mkdir -p /usr/share/confluent-hub-components/debezium-mysql && \
    tar -xzf /tmp/debezium-mysql.tar.gz -C /usr/share/confluent-hub-components/debezium-mysql --strip-components=1 && \
    rm /tmp/debezium-mysql.tar.gz

# 2. Elasticsearch Sink Connector 설치 (버전 latest 사용)
RUN confluent-hub install --no-prompt confluentinc/kafka-connect-elasticsearch:latest

USER appuser
