package com.pawbridge.communityservice.visits;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import com.fasterxml.jackson.databind.JsonNode;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.service.S3Service;
import java.time.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Actual Spring/JPA transaction manager + HTTP + guarded PostgreSQL, no external service calls. */
@Testcontainers
@ActiveProfiles("isolated-test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(VisitApplicationTest.FixedClock.class)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class VisitApplicationTest {
    static final Instant NOW=Instant.parse("2026-10-12T01:00:00Z");
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>(DockerImageName.parse(
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193"))
            .withDatabaseName("community_application_test").withUsername("isolated_test").withPassword("isolated_test_only")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(384 * 1024L * 1024)
                    .withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                            com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),
                            new com.github.dockerjava.api.model.ExposedPort(5432))))
            .withInitScript("db/community-test-guard.sql");

    @DynamicPropertySource static void database(DynamicPropertyRegistry props) throws Exception {
        try (var connection=java.sql.DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
             var statement=connection.createStatement();var guard=statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
            if (!guard.next() || !"community-application-disposable".equals(guard.getString(1)) || guard.next())
                throw new IllegalStateException("Disposable visit application database required");
        }
        Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())
                .defaultSchema("pawbridge_community").locations("classpath:db/postgresql").cleanDisabled(true).load().migrate();
        props.add("spring.datasource.url",DB::getJdbcUrl);
        props.add("spring.datasource.username",DB::getUsername);
        props.add("spring.datasource.password",DB::getPassword);
        props.add("pawbridge.visits.enabled",() -> "true");
        props.add("pawbridge.visits.signing-key",() -> VisitCookieTest.KEY);
        props.add("pawbridge.chat.enabled",() -> "false");
    }
    @TestConfiguration(proxyBeanMethods=false) static class FixedClock {
        @Bean @Primary Clock visitorApplicationClock() {return Clock.fixed(NOW,ZoneOffset.UTC);}
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockitoBean S3Service storage;
    @MockitoBean UserServiceClient users;
    @MockitoBean(name="taskScheduler") TaskScheduler scheduler;

    @BeforeEach void resetOwnedCounters() {
        jdbc.execute("DELETE FROM pawbridge_community.daily_visitor");
        jdbc.execute("DELETE FROM pawbridge_community.daily_visit_count");
    }
    HttpHeaders headers(String cookie) {
        var headers=new HttpHeaders();headers.setOrigin("https://www.pawbridge.kr");headers.set("User-Agent","LocalVisitTest");
        if(cookie!=null) headers.set(HttpHeaders.COOKIE,cookie);
        return headers;
    }
    String bootstrap() {
        var result=http.exchange("/api/v1/visits/identity",HttpMethod.POST,new HttpEntity<>(headers(null)),Void.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String value=result.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(value).contains("HttpOnly","Secure","SameSite=Lax").doesNotContain("Domain=");
        return value.split(";")[0];
    }
    ResponseEntity<JsonNode> count(String cookie) {
        return http.exchange("/api/v1/visits/today",HttpMethod.POST,new HttpEntity<>(headers(cookie)),JsonNode.class);
    }

    @Test void givenEnabledApplication_whenBootstrapCountAndRead_thenActualWiringIsIdempotentAndMinimal() {
        assertThat(manager).isInstanceOf(org.springframework.orm.jpa.JpaTransactionManager.class);
        String cookie=bootstrap();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pawbridge_community.daily_visitor",Long.class)).isZero();
        var first=count(cookie);assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().path("data").path("count").asLong()).isEqualTo(1);
        assertThat(count(cookie).getBody().path("data").path("count").asLong()).isEqualTo(1);
        var today=http.getForEntity("/api/v1/visits/today",JsonNode.class);
        assertThat(today.getBody().path("data").path("date").asText()).isEqualTo("2026-10-12");
        assertThat(today.getHeaders().getCacheControl()).isEqualTo("no-store");
        Instant started=jdbc.queryForObject("SELECT started_at FROM pawbridge_community.visit_collection WHERE singleton",
                (row,n) -> row.getTimestamp(1).toInstant());
        assertThat(started).isEqualTo(NOW);
        verifyNoInteractions(storage,users);
    }
    @Test void givenCountWriteFails_whenActualJpaTransactionRollsBack_thenNoIdentityRemainsAndRetrySucceeds() {
        String cookie=bootstrap();
        jdbc.execute("ALTER TABLE pawbridge_community.daily_visit_count ADD CONSTRAINT synthetic_fail CHECK(unique_visitors<1)");
        try {
            var failed=count(cookie);assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pawbridge_community.daily_visitor",Long.class)).isZero();
        } finally {jdbc.execute("ALTER TABLE pawbridge_community.daily_visit_count DROP CONSTRAINT synthetic_fail");}
        assertThat(count(cookie).getBody().path("data").path("count").asLong()).isEqualTo(1);
    }
}
