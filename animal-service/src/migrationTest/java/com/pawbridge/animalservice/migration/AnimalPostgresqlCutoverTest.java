package com.pawbridge.animalservice.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pawbridge.animalservice.client.PythonLostSearchClient;
import com.pawbridge.animalservice.search.PostgresqlSearchProjector;
import com.pawbridge.animalservice.service.ElasticsearchIndexService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import com.pawbridge.migration.CutoverProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@Tag("postgresql-cutover")
@EnabledIfEnvironmentVariable(named="PG_CUTOVER_PORT", matches="[0-9]{1,5}")
@ActiveProfiles("postgresql")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.flyway.enabled=false", "management.tracing.enabled=false",
    "R2_ACCESS_KEY_ID=isolated-test-only", "R2_SECRET_ACCESS_KEY=isolated-test-only",
    "R2_REGION=us-east-1", "R2_ENDPOINT=http://127.0.0.1:1", "R2_BUCKET_NAME=isolated-test",
    "SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:1",
    "PYTHON_AI_SERVICE_URL=http://127.0.0.1:1", "PYTHON_AI_SERVICE_INTERNAL_API_KEY=isolated-test-only",
    "APMS_API_BASE_URL=http://127.0.0.1:1", "APMS_API_SERVICE_KEY=isolated-test-only",
    "spring.elasticsearch.uris=http://127.0.0.1:1", "spring.elasticsearch.connection-timeout=100ms",
    "spring.elasticsearch.socket-timeout=100ms"
})
class AnimalPostgresqlCutoverTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Autowired PostgresqlSearchProjector projector;
    @MockitoBean RedissonClient redis;
    @MockitoBean com.pawbridge.animalservice.chatbot.service.ChatbotRateLimitService chatbotRateLimits;
    @MockitoBean PythonLostSearchClient python;
    @MockitoBean com.pawbridge.animalservice.client.ApmsApiClient apms;
    @Autowired org.springframework.batch.core.Job apmsAnimalSyncJob;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("apmsJobLauncher")
    org.springframework.batch.core.launch.JobLauncher launcher;
    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void guardedDatabase(DynamicPropertyRegistry properties) throws Exception {
        CutoverProbe.bind(properties,"ANIMAL");
    }
    @Test
    void cdc_favorite_duplicate_and_failed_compensation_keep_database_and_offsets_consistent() throws Exception {
        String topic = "user.favorite.events";
        String group = "animal-service-favorite-group";
        String first = "{\"eventId\":\"cutover-favorite\",\"eventType\":\"FAVORITE_ADDED\",\"animalId\":100,\"userId\":100}";
        CutoverProbe.emit(jdbc,"user",topic,"FAVORITE_ADDED","cutover-favorite",first);
        CutoverProbe.await("favorite committed", () -> jdbc.queryForObject("SELECT favorite_count FROM animals WHERE id=100",Integer.class)==1);
        CutoverProbe.drained(group,topic);
        CutoverProbe.repeat(topic,first);
        CutoverProbe.drained(group,topic);
        assertThat(jdbc.queryForObject("SELECT favorite_count FROM animals WHERE id=100",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id='cutover-favorite'",Integer.class)).isEqualTo(1);
        long before = CutoverProbe.offset(group,topic);
        jdbc.update("INSERT INTO pawbridge_user.favorites(animal_id,created_at,user_id) VALUES (990001,CURRENT_TIMESTAMP,100)");
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT cutover_reject_compensation CHECK(event_type<>'FAVORITE_COMPENSATION_REQUIRED') NOT VALID");
        try {
            CutoverProbe.emit(jdbc,"user",topic,"FAVORITE_ADDED","cutover-missing",
                    "{\"eventId\":\"cutover-missing\",\"eventType\":\"FAVORITE_ADDED\",\"animalId\":990001,\"userId\":100}");
            CutoverProbe.await("failed event reached Kafka", () -> CutoverProbe.end(topic)>before);
            CutoverProbe.retained(group,topic,before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type='FAVORITE_COMPENSATION_REQUIRED'",Integer.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT cutover_reject_compensation"); }
        CutoverProbe.await("durable compensation after repair", () -> jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type='FAVORITE_COMPENSATION_REQUIRED'",Integer.class)>0);
        CutoverProbe.drained(group,topic);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id='cutover-missing'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT favorite_count FROM animals WHERE id=100",Integer.class)).isEqualTo(1);
    }

}
