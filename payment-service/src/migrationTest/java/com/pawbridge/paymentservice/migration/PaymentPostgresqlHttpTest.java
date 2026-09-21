package com.pawbridge.paymentservice.migration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.paymentservice.client.StoreServiceClient;
import com.pawbridge.paymentservice.client.TossPaymentsClient;
import com.pawbridge.paymentservice.domain.payment.dto.StoreOrderResponse;
import com.pawbridge.paymentservice.domain.payment.dto.TossPaymentResponse;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("postgresql-http")
@EnabledIfEnvironmentVariable(named="PG_HTTP_TEST_PORT",matches="[0-9]{1,5}")
@ActiveProfiles("postgresql")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.flyway.enabled=false","management.tracing.enabled=false",
 "TOSS_SECRET_KEY=isolated-test-only","STORE_SERVICE_URL=http://127.0.0.1:1"})
class PaymentPostgresqlHttpTest {
 @Autowired TestRestTemplate http;
 @Autowired JdbcTemplate jdbc;
 @Autowired ObjectMapper mapper;
 @MockitoBean TossPaymentsClient toss;
 @MockitoBean StoreServiceClient store;
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
        properties.add("PAYMENT_POSTGRESQL_JDBC_URL",()->url);
        properties.add("PAYMENT_POSTGRESQL_USERNAME",()->"postgres");
        properties.add("PAYMENT_POSTGRESQL_PASSWORD",()->"local_pg_test_only");
        properties.add("PAYMENT_POSTGRESQL_POOL_MAX",()->"2");
    }

 @Test void confirm_duplicate_and_provider_failure_keep_payment_outbox_contract() throws Exception {
  String order=UUID.randomUUID().toString();String key="test-"+UUID.randomUUID();
  when(store.getOrder(anyString())).thenReturn(mapper.readValue("{\"totalAmount\":12000,\"status\":\"PENDING\"}",StoreOrderResponse.class));
  when(toss.confirmPayment(anyString(),any())).thenReturn(done(key,order));
  assertThat(confirm(key,order).getBody().path("status").asText()).isEqualTo("DONE");
  assertThat(confirm(key,order).getBody().path("status").asText()).isEqualTo("DONE");
  verify(toss,times(1)).confirmPayment(anyString(),any());
  assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? AND status='DONE'",Long.class,order)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='PAYMENT_COMPLETED'",Long.class,key)).isEqualTo(1);
  when(toss.confirmPayment(anyString(),any())).thenThrow(new IllegalStateException("isolated provider rejection"));
  String rejectedOrder=UUID.randomUUID().toString();String rejectedKey="test-"+UUID.randomUUID();
  assertThat(confirm(rejectedKey,rejectedOrder).getBody().path("status").asText()).isEqualTo("ABORTED");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?",Long.class,rejectedOrder)).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='PAYMENT_FAILED'",Long.class,rejectedKey)).isEqualTo(1);
 }
 @Test void outbox_failure_rolls_back_database_payment_and_requests_provider_cancellation() throws Exception {
  String order=UUID.randomUUID().toString();String key="test-"+UUID.randomUUID();
  when(store.getOrder(anyString())).thenReturn(mapper.readValue("{\"totalAmount\":12000}",StoreOrderResponse.class));
  when(toss.confirmPayment(anyString(),any())).thenReturn(done(key,order));
  jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT http_test_reject_payment CHECK(event_type<>'PAYMENT_COMPLETED') NOT VALID");
  try {
   assertThat(confirm(key,order).getStatusCode().is5xxServerError()).isTrue();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?",Long.class,order)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=?",Long.class,key)).isZero();
   verify(toss).cancelPayment(anyString(),eq(key),any());
  }finally{jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT http_test_reject_payment");}
 }
 private TossPaymentResponse done(String key,String order) {
  return TossPaymentResponse.builder().paymentKey(key).orderId(order).totalAmount(12000L).method("카드").status("DONE").requestedAt(OffsetDateTime.now()).approvedAt(OffsetDateTime.now()).build();
 }
 private ResponseEntity<JsonNode> confirm(String key,String order) {
  HttpHeaders headers=new HttpHeaders();headers.set("X-User-Id","101");
  return http.exchange("/api/payments/confirm",HttpMethod.POST,new HttpEntity<>(Map.of("paymentKey",key,"orderId",order,"amount",12000),headers),JsonNode.class);
 }
}
