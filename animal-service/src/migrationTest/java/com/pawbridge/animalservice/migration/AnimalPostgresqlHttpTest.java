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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
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

@Tag("postgresql-http")
@EnabledIfEnvironmentVariable(named="PG_HTTP_TEST_PORT", matches="[0-9]{1,5}")
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
class AnimalPostgresqlHttpTest {
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
    @MockitoBean(name="org.springframework.kafka.config.internalKafkaListenerEndpointRegistry")
    KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void guardedDatabase(DynamicPropertyRegistry properties) throws Exception {
        String port=System.getenv("PG_HTTP_TEST_PORT");
        if (port==null || !port.matches("[0-9]{1,5}")) throw new IllegalStateException("Isolated port required");
        String url="jdbc:postgresql://127.0.0.1:"+port+"/pawbridge";
        try (Connection connection=DriverManager.getConnection(url,"postgres","local_pg_test_only");
             Statement statement=connection.createStatement();
             ResultSet result=statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
            if (!result.next() || !result.getString(1).equals("http-rehearsal") || result.next())
                throw new IllegalStateException("Isolated database guard required");
        }
        properties.add("ANIMAL_POSTGRESQL_JDBC_URL",()->url);
        properties.add("ANIMAL_POSTGRESQL_USERNAME",()->"postgres");
        properties.add("ANIMAL_POSTGRESQL_PASSWORD",()->"local_pg_test_only");
        properties.add("ANIMAL_POSTGRESQL_MAX_POOL_SIZE",()->"2");
        properties.add("ANIMAL_POSTGRESQL_MIN_IDLE",()->"0");
    }

    @Test
    void postgresql_profile_serves_writes_search_and_current_recommendations_without_elasticsearch() {
        assertThat(context.getBeansOfType(ElasticsearchOperations.class)).isEmpty();
        assertThat(context.getBeansOfType(ElasticsearchIndexService.class)).isEmpty();
        assertThat(context.containsBean("elasticsearchIndexStep")).isFalse();
        assertThat(context.containsBean("apmsAnimalSyncJob")).isTrue();
        String care=prepareShelter();
        long source=create(care,"하얀 강아지", "PROTECT");
        long available=create(care,"검정 고양이와 지내요", "PROTECT");
        long adopted=create(care,"입양 완료", "ADOPTED");
        assertThat(searchIds("하얀")).contains(source);
        assertThat(http.exchange("/api/v1/animals/"+source+"/description",HttpMethod.PATCH,
                new HttpEntity<>(Map.of("description","갈색 강아지")),JsonNode.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(searchIds("하얀")).doesNotContain(source);
        assertThat(searchIds("갈색")).contains(source);
        when(python.recommend(anyString(),eq(source),eq("DOG"))).thenReturn(List.of(adopted,source,available));
        ResponseEntity<JsonNode> candidates=http.getForEntity("/api/v1/animals/"+source+"/similar",JsonNode.class);
        assertThat(candidates.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(candidates.getBody().size()).isEqualTo(1);
        assertThat(candidates.getBody().get(0).path("id").asLong()).isEqualTo(available);
        assertThat(http.exchange("/api/v1/animals/"+available+"/status",HttpMethod.PATCH,
                new HttpEntity<>(Map.of("newStatus","ADOPTED")),JsonNode.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(http.getForObject("/api/v1/animals/"+source+"/similar",JsonNode.class).size()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM animal_search_documents WHERE animal_id IN (?,?,?)",Long.class,source,available,adopted)).isEqualTo(3);
    }

    @Test
    void registered_animals_count_and_pages_include_only_manual_source_on_postgresql() {
        String care = prepareShelter();
        long shelterId = jdbc.queryForObject("SELECT id FROM shelters WHERE care_reg_no=?", Long.class, care);
        String otherCare = prepareShelter();
        long otherShelterId = jdbc.queryForObject("SELECT id FROM shelters WHERE care_reg_no=?", Long.class, otherCare);
        List<Long> expected = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            expected.add(registeredFixture(shelterId, "MANUAL"));
        }
        for (int index = 0; index < 25; index++) {
            registeredFixture(shelterId, "APMS_ANIMAL");
        }
        registeredFixture(shelterId, "GYEONGGI");
        registeredFixture(shelterId, "UNKNOWN");
        registeredFixture(otherShelterId, "MANUAL");
        Collections.reverse(expected);
        List<Long> actual = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            ResponseEntity<JsonNode> response = http.getForEntity(
                    "/api/v1/mypage/animals/by-shelter/{id}?page={page}&size=20&sort=createdAt,desc&sort=id,desc",
                    JsonNode.class, shelterId, page);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            JsonNode body = response.getBody();
            assertThat(body.path("totalElements").asLong()).isEqualTo(21);
            assertThat(body.path("totalPages").asInt()).isEqualTo(2);
            assertThat(body.path("content").size()).isEqualTo(page == 0 ? 20 : page == 1 ? 1 : 0);
            body.path("content").forEach(animal -> actual.add(animal.path("id").asLong()));
        }
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private long registeredFixture(long shelterId, String source) {
        return jdbc.queryForObject("""
                INSERT INTO animals(created_at,api_source,apms_notice_no,favorite_count,species,status,gender,
                                    neuter_status,notice_start_date,notice_end_date,shelter_id)
                VALUES (CURRENT_TIMESTAMP,?,?,0,'DOG','PROTECT','UNKNOWN','UNKNOWN',
                        DATE '2026-09-01',DATE '2026-09-11',?) RETURNING id
                """, Long.class, source, UUID.randomUUID().toString(), shelterId);
    }

    @Test
    void outbox_failure_rolls_back_animal_and_search_document_together() {
        String care=prepareShelter();
        long animals=jdbc.queryForObject("SELECT count(*) FROM animals",Long.class);
        long documents=jdbc.queryForObject("SELECT count(*) FROM animal_search_documents",Long.class);
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT http_test_reject_animal CHECK(event_type<>'ANIMAL_CREATED') NOT VALID");
        try {
            assertThat(http.postForEntity("/api/v1/animals",animal(care,"롤백 동물","PROTECT"),JsonNode.class).getStatusCode().is5xxServerError()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM animals",Long.class)).isEqualTo(animals);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM animal_search_documents",Long.class)).isEqualTo(documents);
        } finally {
            jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT http_test_reject_animal");
        }
    }

    @Test
    void real_postgresql_batch_metadata_records_success_and_preserves_query_checkpoints() throws Exception {
        when(apms.getAbandonmentAnimals(anyString(),anyInt(),anyInt(),anyString(),anyString(),
                isNull(),isNull(),eq("json"),nullable(String.class),nullable(String.class)))
                .thenReturn(new com.pawbridge.animalservice.dto.apms.ApmsRootResponse<>(
                        new com.pawbridge.animalservice.dto.apms.ApmsResponse<>(
                                com.pawbridge.animalservice.dto.apms.ApmsHeader.builder().resultCode("00").build(),
                                new com.pawbridge.animalservice.dto.apms.ApmsBody<>(
                                        new com.pawbridge.animalservice.dto.apms.ApmsItems<>(List.of()),"1000","1","0"))));
        java.time.LocalDate day=java.time.LocalDate.of(2026,9,20);
        com.pawbridge.animalservice.batch.ApmsSyncPlan plan=new com.pawbridge.animalservice.batch.ApmsSyncPlan(day,day,day,day);
        org.springframework.batch.core.JobParameters parameters=new org.springframework.batch.core.JobParametersBuilder(plan.parameters())
                .addString("http-test-run",UUID.randomUUID().toString()).toJobParameters();
        org.springframework.batch.core.JobExecution execution=launcher.run(apmsAnimalSyncJob,parameters);
        assertThat(execution.getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions()).hasSize(3);
        assertThat(com.pawbridge.animalservice.batch.ApmsQueryProgress.verifiedResults(execution,plan)).hasSize(plan.queries().size());
        assertThat(jdbc.queryForObject("SELECT status FROM batch_job_execution WHERE job_execution_id=?",String.class,execution.getId())).isEqualTo("COMPLETED");
    }

    private String prepareShelter() {
        String care=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO shelters(created_at,care_reg_no,name,address) VALUES (CURRENT_TIMESTAMP,?,'격리 보호소','서울')",care);
        projector.refreshShelters(100);
        return care;
    }
    private Map<String,Object> animal(String care,String description,String status) {
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("careRegNo",care); body.put("apmsNoticeNo",UUID.randomUUID().toString());
        body.put("noticeStartDate","2026-09-01"); body.put("noticeEndDate","2026-09-11");
        body.put("species","DOG"); body.put("gender","MALE"); body.put("neuterStatus","UNKNOWN");
        body.put("status",status); body.put("description",description);
        return body;
    }
    private long create(String care,String description,String status) {
        ResponseEntity<JsonNode> response=http.postForEntity("/api/v1/animals",animal(care,description,status),JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().path("id").asLong()).isPositive();
        return response.getBody().path("id").asLong();
    }
    private List<Long> searchIds(String keyword) {
        ResponseEntity<JsonNode> response=http.getForEntity("/api/v1/animals?keyword={keyword}",JsonNode.class,keyword);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode content=response.getBody().path("content");
        assertThat(content.isArray()).isTrue();
        return java.util.stream.StreamSupport.stream(content.spliterator(),false)
                .map(item->item.path("id").asLong()).toList();
    }
}
