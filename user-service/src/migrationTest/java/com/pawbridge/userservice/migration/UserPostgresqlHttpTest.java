package com.pawbridge.userservice.migration;
import com.fasterxml.jackson.databind.JsonNode;
import com.pawbridge.userservice.email.service.EmailVerificationService;
import com.pawbridge.userservice.client.AnimalServiceClient;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Tag("postgresql-http")
@EnabledIfEnvironmentVariable(named="PG_HTTP_TEST_PORT",matches="[0-9]{1,5}")
@ActiveProfiles("postgresql")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.flyway.enabled=false","spring.kafka.bootstrap-servers=127.0.0.1:1","spring.kafka.listener.auto-startup=false",
 "spring.mail.host=127.0.0.1","spring.mail.port=1","spring.data.redis.port=1","management.tracing.enabled=false",
 "GOOGLE_EMAIL=isolated@example.test","GOOGLE_EMAIL_SECRET_KEY=isolated-test","GOOGLE_CLIENT_ID=isolated-test",
 "GOOGLE_SECRET_KEY=isolated-test","REDIRECT_URI=http://127.0.0.1:1/callback","OAUTH2_REDIRECT_URI=http://127.0.0.1:1/",
 "jwt.secret=isolated-test-signing-key-not-for-production-012345678901234567890123456789",
 "jwt.access-token-expiration=3600000","jwt.refresh-token-expiration=7200000"})
class UserPostgresqlHttpTest {
 @Autowired TestRestTemplate http;
 @Autowired JdbcTemplate jdbc;
 @MockitoBean EmailVerificationService email;
 @MockitoBean AnimalServiceClient animals;
 @MockitoBean(name="org.springframework.kafka.config.internalKafkaListenerEndpointRegistry") KafkaListenerEndpointRegistry kafka;
    @DynamicPropertySource
    static void guardedDatabase(DynamicPropertyRegistry properties) throws Exception {
        String port=System.getenv("PG_HTTP_TEST_PORT");
        if(port==null || !port.matches("[0-9]{1,5}"))throw new IllegalStateException("Isolated port required");
        String url="jdbc:postgresql://127.0.0.1:"+port+"/pawbridge";
        try(java.sql.Connection connection=DriverManager.getConnection(url,"postgres","local_pg_test_only");
            java.sql.Statement statement=connection.createStatement();
            java.sql.ResultSet result=statement.executeQuery("SELECT marker FROM migration_test_guard.guard")) {
            if(!result.next() || !result.getString(1).equals("http-rehearsal") || result.next())
                throw new IllegalStateException("Isolated database guard required");
        }
        properties.add("USER_POSTGRESQL_JDBC_URL",()->url);
        properties.add("USER_POSTGRESQL_USERNAME",()->"postgres");
        properties.add("USER_POSTGRESQL_PASSWORD",()->"local_pg_test_only");
        properties.add("USER_POSTGRESQL_POOL_MAX",()->"2");
    }

 @Test void signup_login_favorite_refresh_logout_use_real_postgresql_transactions() {
  when(email.isVerified(anyString())).thenReturn(true);
  String address="http-"+UUID.randomUUID()+"@example.test";
  ResponseEntity<JsonNode> signup=http.postForEntity("/api/v1/users/signup",Map.of("email",address,"name","격리회원","password","test-password","rePassword","test-password","role","ROLE_USER"),JsonNode.class);
  assertThat(signup.getStatusCode().is2xxSuccessful()).isTrue();
  long id=signup.getBody().path("data").path("userId").asLong();assertThat(id).isPositive();
  assertThat(http.postForEntity("/api/v1/auth/login",Map.of("email",address,"password","incorrect"),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  ResponseEntity<JsonNode> login=http.postForEntity("/api/v1/auth/login",Map.of("email",address.toUpperCase(java.util.Locale.ROOT),"password","test-password"),JsonNode.class);
  assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
  String refresh=login.getBody().path("data").path("refreshToken").asText();assertThat(refresh).isNotBlank();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id=?",Long.class,id)).isEqualTo(1);
  HttpHeaders headers=new HttpHeaders();headers.set("X-User-Id",Long.toString(id));
  assertThat(http.exchange("/api/v1/favorites/42",HttpMethod.POST,new HttpEntity<>(headers),JsonNode.class).getStatusCode().is2xxSuccessful()).isTrue();
  assertThat(http.exchange("/api/v1/favorites/42/check",HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class).getBody().path("data").asBoolean()).isTrue();
  assertThat(http.exchange("/api/v1/favorites/42",HttpMethod.DELETE,new HttpEntity<>(headers),JsonNode.class).getStatusCode().is2xxSuccessful()).isTrue();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM favorites WHERE user_id=?",Long.class,id)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?",Long.class,Long.toString(id))).isEqualTo(2);
  ResponseEntity<JsonNode> refreshed=http.postForEntity("/api/v1/auth/refresh",Map.of("refreshToken",refresh),JsonNode.class);
  assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(http.exchange("/api/v1/auth/logout",HttpMethod.POST,new HttpEntity<>(headers),JsonNode.class).getStatusCode().is2xxSuccessful()).isTrue();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id=?",Long.class,id)).isZero();
  assertThat(http.postForEntity("/api/v1/auth/refresh",Map.of("refreshToken",refreshed.getBody().path("data").path("refreshToken").asText()),JsonNode.class).getStatusCode().is4xxClientError()).isTrue();
  jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT http_test_reject_favorite CHECK(event_type<>'FAVORITE_ADDED') NOT VALID");
  try {
   assertThat(http.exchange("/api/v1/favorites/43",HttpMethod.POST,new HttpEntity<>(headers),JsonNode.class).getStatusCode().isError()).isTrue();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM favorites WHERE user_id=?",Long.class,id)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?",Long.class,Long.toString(id))).isEqualTo(2);
  } finally {jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT http_test_reject_favorite");}
 }
}
