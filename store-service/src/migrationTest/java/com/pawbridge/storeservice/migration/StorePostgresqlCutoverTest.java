package com.pawbridge.storeservice.migration;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.DriverManager;
import java.util.List;
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
import org.redisson.api.RedissonClient;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgresql-cutover")
@EnabledIfEnvironmentVariable(named="PG_CUTOVER_PORT",matches="[0-9]{1,5}")
@ActiveProfiles("postgresql")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.flyway.enabled=false","spring.kafka.bootstrap-servers=127.0.0.1:1","spring.kafka.listener.auto-startup=true",
 "spring.data.redis.port=1","management.tracing.enabled=false","R2_ACCESS_KEY_ID=isolated-test","R2_SECRET_ACCESS_KEY=isolated-test",
 "R2_ENDPOINT=http://127.0.0.1:1","R2_BUCKET_NAME=isolated-test","R2_REGION=us-east-1","pawbridge.search.rebuild-enabled=false"})
class StorePostgresqlCutoverTest {
 @Autowired TestRestTemplate http;
 @Autowired JdbcTemplate jdbc;
 @MockitoBean RedissonClient redis;
 @MockitoBean com.pawbridge.storeservice.domain.cart.scheduler.CartSyncScheduler cartSchedule;
 @MockitoBean com.pawbridge.storeservice.domain.product.service.ProductCacheService cache;
 @Autowired KafkaListenerEndpointRegistry kafka;
    @DynamicPropertySource
    static void guardedDatabase(DynamicPropertyRegistry properties) throws Exception {
        CutoverProbe.bind(properties,"STORE");
    }
    @MockitoBean org.springframework.data.redis.core.StringRedisTemplate rankings;
 @Test
 void payment_cdc_retries_database_failure_and_duplicate_does_not_repeat_ranking() throws Exception {
  org.springframework.data.redis.core.ZSetOperations<String,String> scores=org.mockito.Mockito.mock(org.springframework.data.redis.core.ZSetOperations.class);
  org.mockito.Mockito.when(rankings.opsForZSet()).thenReturn(scores);
  String topic="payment.events"; String group="payment-group";
  jdbc.execute("ALTER TABLE orders ADD CONSTRAINT cutover_reject_paid CHECK(status<>'PAID') NOT VALID");
  try {
   new org.springframework.transaction.support.TransactionTemplate(
       new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(transaction -> {
       jdbc.update("UPDATE pawbridge_payment.payments SET status='DONE' WHERE id=100");
       CutoverProbe.emit(jdbc,"payment",topic,"PaymentConfirmed","unused","{\"orderId\":\"order100\",\"status\":\"DONE\"}");
   });
   CutoverProbe.await("payment reached Kafka",()->CutoverProbe.end(topic)>0);
   CutoverProbe.retained(group,topic,0);
   assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=100",String.class)).isEqualTo("PENDING");
   System.out.println("CUTOVER_REDIS_ATTEMPTS_BEFORE_DB_COMMIT=" + org.mockito.Mockito.mockingDetails(scores).getInvocations().size());
  } finally { jdbc.execute("ALTER TABLE orders DROP CONSTRAINT cutover_reject_paid"); }
  CutoverProbe.await("payment retry committed",()->"PAID".equals(jdbc.queryForObject("SELECT status FROM orders WHERE id=100",String.class)));
  CutoverProbe.drained(group,topic);
  org.mockito.Mockito.clearInvocations(scores);
  CutoverProbe.repeat(topic,"{\"orderId\":\"order100\",\"status\":\"DONE\"}");
  CutoverProbe.drained(group,topic);
  org.mockito.Mockito.verifyNoInteractions(scores);
  assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=100",String.class)).isEqualTo("PAID");
 }

}
