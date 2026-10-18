package com.pawbridge.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import static org.assertj.core.api.Assertions.assertThat;

/** No arbitrary target: loopback ports plus a per-run database marker are required. */
public final class CutoverProbe {
    private CutoverProbe() { }
    public static String broker() { return "127.0.0.1:" + port("PG_CUTOVER_KAFKA_PORT"); }
    private static String port(String name) {
        String value = System.getenv(name);
        if (value == null || !value.matches("[0-9]{1,5}") || Integer.parseInt(value) < 1
                || Integer.parseInt(value) > 65535) throw new IllegalStateException("Missing isolated port");
        return value;
    }
    public static void bind(DynamicPropertyRegistry properties, String service) throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + port("PG_CUTOVER_PORT") + "/pawbridge";
        String marker = System.getenv("PG_CUTOVER_MARKER");
        if (marker == null || !marker.matches("pawbridge-rollback-test-[a-f0-9]{10}"))
            throw new IllegalStateException("Missing owned rehearsal marker");
        try (Connection connection = DriverManager.getConnection(url,"postgres","isolated_rollback_test_only");
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
            assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo(marker);
            assertThat(result.next()).isFalse();
        }
        properties.add(service + "_POSTGRESQL_JDBC_URL", () -> url);
        properties.add(service + "_POSTGRESQL_USERNAME", () -> "postgres");
        properties.add(service + "_POSTGRESQL_PASSWORD", () -> "isolated_rollback_test_only");
        properties.add(service + "_POSTGRESQL_POOL_MAX", () -> "2");
        properties.add(service + "_POSTGRESQL_MAX_POOL_SIZE", () -> "2");
        properties.add(service + "_POSTGRESQL_MIN_IDLE", () -> "0");
        properties.add("spring.kafka.bootstrap-servers", CutoverProbe::broker);
        properties.add("SPRING_KAFKA_BOOTSTRAP_SERVERS", CutoverProbe::broker);
        properties.add("spring.kafka.listener.auto-startup", () -> "true");
        properties.add("logging.level.org.apache.kafka", () -> "WARN");
    }
    public static void await(String label, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out: " + label);
    }
    public static void repeat(String topic, String payload) throws Exception {
        try (KafkaProducer<String,String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers",broker(),"key.serializer",StringSerializer.class,
                "value.serializer",StringSerializer.class,"max.block.ms",10000,"delivery.timeout.ms",15000,
                "request.timeout.ms",10000))) {
            producer.send(new ProducerRecord<>(topic,"\"100\"",payload)).get(20,TimeUnit.SECONDS);
        }
    }
    public static long offset(String group, String topic) {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers",broker(),"default.api.timeout.ms",5000,"request.timeout.ms",3000))) {
            org.apache.kafka.clients.consumer.OffsetAndMetadata value = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get(6,TimeUnit.SECONDS).get(new TopicPartition(topic,0));
            return value == null ? 0 : value.offset();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    public static long end(String topic) {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers",broker(),"default.api.timeout.ms",5000,"request.timeout.ms",3000))) {
            return admin.listOffsets(Map.of(new TopicPartition(topic,0),OffsetSpec.latest())).all()
                    .get(6,TimeUnit.SECONDS).get(new TopicPartition(topic,0)).offset();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    public static void drained(String group, String topic) throws Exception {
        await("consumer caught up: " + group, () -> end(topic) > 0 && offset(group,topic) == end(topic));
    }
    public static void retained(String group, String topic, long before) throws Exception {
        // Longer than production's initial retry cycle; an unresolved recovery must not commit.
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        do { assertThat(offset(group,topic)).isEqualTo(before); Thread.sleep(250); }
        while (System.nanoTime() < deadline);
    }
    public static void emit(JdbcTemplate jdbc, String service, String topic, String type, String eventId, String payload) {
        if (!Set.of("user","animal","payment").contains(service)) throw new IllegalArgumentException("fixture service");
        if (service.equals("payment")) {
            jdbc.update("INSERT INTO pawbridge_payment.outbox(aggregate_id,aggregate_type,created_at,event_type,payload) VALUES ('100','payment',CURRENT_TIMESTAMP,?,?::json)",type,payload);
        } else {
            jdbc.update("INSERT INTO pawbridge_" + service + ".outbox_events(aggregate_id,aggregate_type,created_at,event_type,event_id,topic,payload) VALUES ('100','Favorite',CURRENT_TIMESTAMP,?,?,?,?::json)",type,eventId,topic,payload);
        }
    }
}
