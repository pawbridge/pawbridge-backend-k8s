package com.pawbridge.communityservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.config.ElasticsearchConfig;
import com.pawbridge.communityservice.contact.PrivateNoteController;
import com.pawbridge.communityservice.contact.PrivateNoteModels.ContactMember;
import com.pawbridge.communityservice.contact.PrivateNoteModels.SendNote;
import com.pawbridge.communityservice.contact.PrivateNoteStream;
import com.pawbridge.communityservice.service.S3Service;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Full application/HTTP and real PostgreSQL. Remote storage and user lookup are doubles. */
@Testcontainers
@ActiveProfiles({"postgresql", "isolated-test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityServiceApplicationTests {
    @Container
    static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse(
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193"))
            .withDatabaseName("community_application_test")
            .withUsername("isolated_test")
            .withPassword("isolated_test_only")
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withMemory(384 * 1024L * 1024)
                    .withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                            new ExposedPort(5432))))
            .withInitScript("db/community-test-guard.sql");

    @DynamicPropertySource
    static void guardedDatabase(DynamicPropertyRegistry properties) throws Exception {
        // No externally supplied JDBC URL is accepted; the test owns this new container.
        try (var connection = DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
             var statement = connection.createStatement();
             var marker = statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
            if (!marker.next() || !"community-application-disposable".equals(marker.getString(1))
                    || marker.next()) {
                throw new IllegalStateException("Disposable application database guard required");
            }
        }
        Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .defaultSchema("pawbridge_community").locations("classpath:db/postgresql")
                .cleanDisabled(true).load().migrate();
        // Register the literal test overlay above process environment variables, including CI.
        for (var source : new YamlPropertySourceLoader().load("isolated-test",
                new ClassPathResource("application-isolated-test.yml"))) {
            var values = (EnumerablePropertySource<?>) source;
            for (String name : values.getPropertyNames()) {
                properties.add(name, () -> values.getProperty(name));
            }
        }
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", database::getUsername);
        properties.add("spring.datasource.password", database::getPassword);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    @Autowired ApplicationContext context;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PrivateNoteStream stream;
    @LocalServerPort int port;
    @MockitoBean S3Service storage;
    @MockitoBean UserServiceClient users;
    // @EnableScheduling is explicit in production, so a Boot property alone does not disable it.
    @MockitoBean(name = "taskScheduler") TaskScheduler scheduler;

    @BeforeEach
    void remoteBoundaries() {
        when(users.getContactMember(anyLong())).thenAnswer(call ->
                new ContactMember(call.getArgument(0), "격리 테스트 회원", true, false));
        when(users.getContactMembers(anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            return ids.stream().map(id -> new ContactMember(id, "격리 테스트 회원", true, false)).toList();
        });
    }

    @Test
    void contextLoads() {
        assertThat(context.getEnvironment().getActiveProfiles()).contains("postgresql", "isolated-test");
        assertThat(context.getBean(PrivateNoteController.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT max(version::integer) FROM flyway_schema_history "
                + "WHERE success AND version IS NOT NULL", Integer.class)).isGreaterThanOrEqualTo(9);
        assertThat(context.getBeansOfType(ElasticsearchConfig.class)).isEmpty();
        assertThat(context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers()).isEmpty();
        assertThat(mockingDetails(context.getBean(TaskScheduler.class)).isMock()).isTrue();
        assertThat(context.getEnvironment().getProperty("spring.cloud.aws.s3.endpoint"))
                .isEqualTo("http://127.0.0.1:1");
        verifyNoInteractions(storage, users);
    }

    @Test
    void givenWrongDisposableMarker_whenDatabasePrepared_thenRejectBeforeMigrationOrPropertyRegistration() {
        long migrations = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Long.class);
        jdbc.update("UPDATE migration_test_guard.guard SET marker='not-this-test'");
        var registered = new AtomicInteger();
        try {
            assertThatThrownBy(() -> guardedDatabase((name, supplier) -> registered.incrementAndGet()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Disposable application database guard required");
            assertThat(registered).hasValue(0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Long.class))
                    .isEqualTo(migrations);
        } finally {
            jdbc.update("UPDATE migration_test_guard.guard SET marker='community-application-disposable'");
        }
    }

    @Test
    void givenSentNote_whenRetriedAndReadThroughHttp_thenOneOriginalAndIndependentMailboxState() {
        long sender = 101;
        long recipient = 102;
        SendNote draft = new SendNote(recipient, "격리 HTTP 쪽지", UUID.randomUUID(), null, null, null);
        JsonNode receipt = send(sender, draft);
        String noteId = receipt.path("noteId").asText();
        assertThat(send(sender, draft)).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM private_notes WHERE note_id=?::uuid",
                Long.class, noteId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM private_note_mailboxes WHERE note_id=?::uuid",
                Long.class, noteId)).isEqualTo(2);
        JsonNode senderReadAt = get(sender, "/api/v1/notes/" + noteId).path("readAt");

        assertThat(get(recipient, "/api/v1/notes/notifications").path("unreadCount").asLong()).isEqualTo(1);
        var foreign = http.exchange("/api/v1/notes/" + noteId, HttpMethod.GET,
                new HttpEntity<>(headers(103)), JsonNode.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var read = http.exchange("/api/v1/notes/" + noteId + "/read", HttpMethod.PUT,
                new HttpEntity<>(headers(recipient)), Void.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(recipient, "/api/v1/notes/notifications").path("unreadCount").asLong()).isZero();
        assertThat(get(recipient, "/api/v1/notes/" + noteId).path("readAt").isNull()).isFalse();
        assertThat(get(sender, "/api/v1/notes/" + noteId).path("readAt")).isEqualTo(senderReadAt);
        verifyNoInteractions(storage);
    }

    @Test
    void givenConnectedRecipient_whenNoteCommitted_thenSseAndRestReturnTheSameNotification() throws Exception {
        long sender = 201;
        long recipient = 202;
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/notes/stream"))
                .header("X-User-Id", Long.toString(recipient))
                .header("X-Auth-Expires-At", Long.toString(Instant.now().plusSeconds(60).toEpochMilli()))
                .timeout(Duration.ofSeconds(10)).build();
        var readers = Executors.newSingleThreadExecutor();
        try {
            var response = HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .get(10, TimeUnit.SECONDS);
            // Close the HTTP body first on timeout to unblock the reader; BufferedReader.close()
            // itself waits for an in-progress read and must not be the first cleanup action.
            try (var body = response.body()) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("content-type").orElseThrow()).startsWith("text/event-stream");
                var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
                String ready = readers.submit(() -> readEvent(reader, "ready")).get(10, TimeUnit.SECONDS);
                assertThat(mapper.readTree(ready)).isEqualTo(mapper.createObjectNode());
                JsonNode receipt = send(sender, new SendNote(recipient, "SSE 비공개 본문", UUID.randomUUID(), null, null, null));
                String event = readers.submit(() -> readEvent(reader, "note")).get(10, TimeUnit.SECONDS);
                JsonNode notification = mapper.readTree(event);
                assertThat(notification.path("noteId").asText()).isEqualTo(receipt.path("noteId").asText());
                JsonNode restored = get(recipient, "/api/v1/notes/notifications").path("content").get(0);
                // PostgreSQL stores microseconds; live Clock instants can carry nanoseconds.
                var liveFields = (ObjectNode) notification.deepCopy();
                var restoredFields = (ObjectNode) restored.deepCopy();
                liveFields.remove("createdAt");
                restoredFields.remove("createdAt");
                assertThat(liveFields).isEqualTo(restoredFields);
                assertThat(Duration.between(Instant.parse(notification.path("createdAt").asText()),
                        Instant.parse(restored.path("createdAt").asText())).abs())
                        .isLessThanOrEqualTo(Duration.ofNanos(1_000));
                assertThat(notification.has("body")).isFalse();
                assertThat(event).doesNotContain("SSE 비공개 본문");
            }
        } finally {
            stream.close(recipient);
            readers.shutdownNow();
        }
        verifyNoInteractions(storage);
    }

    private JsonNode send(long sender, SendNote draft) {
        var response = http.postForEntity("/api/v1/notes", new HttpEntity<>(draft, headers(sender)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().path("data");
    }

    private JsonNode get(long member, String path) {
        var response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(member)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().path("data");
    }

    private HttpHeaders headers(long member) {
        var headers = new HttpHeaders();
        headers.set("X-User-Id", Long.toString(member));
        return headers;
    }

    private String readEvent(BufferedReader reader, String expected) throws Exception {
        String event = "";
        String data = "";
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event:")) event = line.substring(6).strip();
            if (line.startsWith("data:")) data = line.substring(5).strip();
            if (line.isEmpty()) {
                if (expected.equals(event)) return data;
                event = "";
                data = "";
            }
        }
        throw new IllegalStateException("SSE ended before " + expected);
    }
}
