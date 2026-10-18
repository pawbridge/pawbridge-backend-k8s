package com.pawbridge.userservice.migration;
import com.fasterxml.jackson.databind.JsonNode;
import com.pawbridge.userservice.email.service.EmailVerificationService;
import com.pawbridge.userservice.client.AnimalServiceClient;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import com.pawbridge.migration.CutoverProbe;
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

@Tag("postgresql-cutover")
@EnabledIfEnvironmentVariable(named="PG_CUTOVER_PORT",matches="[0-9]{1,5}")
@ActiveProfiles("postgresql")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.flyway.enabled=false","spring.kafka.bootstrap-servers=127.0.0.1:1","spring.kafka.listener.auto-startup=true",
 "spring.mail.host=127.0.0.1","spring.mail.port=1","spring.data.redis.port=1","management.tracing.enabled=false",
 "GOOGLE_EMAIL=isolated@example.test","GOOGLE_EMAIL_SECRET_KEY=isolated-test","GOOGLE_CLIENT_ID=isolated-test",
 "GOOGLE_SECRET_KEY=isolated-test","REDIRECT_URI=http://127.0.0.1:1/callback","OAUTH2_REDIRECT_URI=http://127.0.0.1:1/",
 "jwt.secret=isolated-test-signing-key-not-for-production-012345678901234567890123456789",
 "jwt.access-token-expiration=3600000","jwt.refresh-token-expiration=7200000"})
class UserPostgresqlCutoverTest {
 @Autowired TestRestTemplate http;
 @Autowired JdbcTemplate jdbc;
 @MockitoBean EmailVerificationService email;
 @MockitoBean AnimalServiceClient animals;
 @Autowired KafkaListenerEndpointRegistry kafka;
    @DynamicPropertySource
    static void guardedDatabase(DynamicPropertyRegistry properties) throws Exception {
        CutoverProbe.bind(properties,"USER");
    }
 @Test
 void cdc_compensation_deletes_favorite_once_and_failed_database_write_retains_offset() throws Exception {
  String topic="user.compensation.events"; String group="user-service-group";
  CutoverProbe.await("compensation applied", () -> jdbc.queryForObject("SELECT count(*) FROM favorites WHERE animal_id=990001",Integer.class)==0);
  CutoverProbe.drained(group,topic);
  String payload=jdbc.queryForObject("SELECT payload::text FROM pawbridge_animal.outbox_events WHERE event_type='FAVORITE_COMPENSATION_REQUIRED' ORDER BY id LIMIT 1",String.class);
  long processed=jdbc.queryForObject("SELECT count(*) FROM processed_events",Long.class);
  CutoverProbe.repeat(topic,payload); CutoverProbe.drained(group,topic);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events",Long.class)).isEqualTo(processed);
  jdbc.update("INSERT INTO favorites(animal_id,created_at,user_id) VALUES (990002,CURRENT_TIMESTAMP,100)");
  long before=CutoverProbe.offset(group,topic);
  // DELETE trigger fails the actual compensation write after its dedup insert.
  jdbc.execute("CREATE FUNCTION cutover_reject_delete() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''cutover injected delete failure''; END'");
  jdbc.execute("CREATE TRIGGER cutover_reject_delete BEFORE DELETE ON favorites FOR EACH ROW EXECUTE FUNCTION cutover_reject_delete()");
  try {
   CutoverProbe.emit(jdbc,"animal",topic,"FAVORITE_COMPENSATION_REQUIRED","cutover-repair",
     "{\"eventId\":\"cutover-repair\",\"originalEventId\":\"cutover-original\",\"compensationType\":\"ROLLBACK_FAVORITE_ADDED\",\"userId\":100,\"animalId\":990002}");
   CutoverProbe.await("blocked compensation reached Kafka",()->CutoverProbe.end(topic)>before);
   CutoverProbe.retained(group,topic,before);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id='cutover-repair'",Integer.class)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM favorites WHERE animal_id=990002",Integer.class)).isEqualTo(1);
  } finally {
   jdbc.execute("DROP TRIGGER cutover_reject_delete ON favorites"); jdbc.execute("DROP FUNCTION cutover_reject_delete()");
  }
  CutoverProbe.await("compensation retry committed",()->jdbc.queryForObject("SELECT count(*) FROM favorites WHERE animal_id=990002",Integer.class)==0);
  CutoverProbe.drained(group,topic);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id='cutover-repair'",Integer.class)).isEqualTo(1);
 }

}
