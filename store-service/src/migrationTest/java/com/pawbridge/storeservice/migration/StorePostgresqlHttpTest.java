package com.pawbridge.storeservice.migration;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.DriverManager;
import java.util.List;
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
import org.redisson.api.RedissonClient;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgresql-http")
@EnabledIfEnvironmentVariable(named="PG_HTTP_TEST_PORT",matches="[0-9]{1,5}")
@ActiveProfiles("postgresql")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.flyway.enabled=false","spring.kafka.bootstrap-servers=127.0.0.1:1","spring.kafka.listener.auto-startup=false",
 "spring.data.redis.port=1","management.tracing.enabled=false","R2_ACCESS_KEY_ID=isolated-test","R2_SECRET_ACCESS_KEY=isolated-test",
 "R2_ENDPOINT=http://127.0.0.1:1","R2_BUCKET_NAME=isolated-test","R2_REGION=us-east-1","pawbridge.search.rebuild-enabled=false"})
class StorePostgresqlHttpTest {
 @Autowired TestRestTemplate http;
 @Autowired JdbcTemplate jdbc;
 @MockitoBean RedissonClient redis;
 @MockitoBean com.pawbridge.storeservice.domain.cart.scheduler.CartSyncScheduler cartSchedule;
 @MockitoBean com.pawbridge.storeservice.domain.product.service.ProductCacheService cache;
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
        properties.add("STORE_POSTGRESQL_JDBC_URL",()->url);
        properties.add("STORE_POSTGRESQL_USERNAME",()->"postgres");
        properties.add("STORE_POSTGRESQL_PASSWORD",()->"local_pg_test_only");
        properties.add("STORE_POSTGRESQL_POOL_MAX",()->"2");
    }

 @Test void product_search_order_and_insufficient_stock_remain_consistent() {
  String skuCode="http-"+UUID.randomUUID();
  ResponseEntity<JsonNode> created=http.postForEntity("/api/v1/products",Map.of("name","하얀 강아지 사료","description","격리 검증 상품","skus",List.of(Map.of("skuCode",skuCode,"price",12000,"stockQuantity",3,"optionValueIds",List.of()))),JsonNode.class);
  assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  long id=created.getBody().path("id").asLong();assertThat(id).isPositive();
  long sku=jdbc.queryForObject("SELECT id FROM product_skus WHERE sku_code=?",Long.class,skuCode);
  ResponseEntity<JsonNode> searched=http.getForEntity("/api/v1/products?keyword=사료",JsonNode.class);
  assertThat(searched.getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(searched.getBody().path("items").toString()).contains("하얀 강아지 사료");
  HttpHeaders headers=new HttpHeaders();headers.set("X-User-Id","101");
  Map<String,Object> order=Map.of("skuId",sku,"quantity",2,"receiverName","격리회원","receiverPhone","00000000000","deliveryAddress","테스트 주소");
  ResponseEntity<JsonNode> ordered=http.exchange("/api/v1/orders/direct",HttpMethod.POST,new HttpEntity<>(order,headers),JsonNode.class);
  assertThat(ordered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  assertThat(ordered.getBody().path("totalAmount").asLong()).isEqualTo(24000);
  assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product_skus WHERE id=?",Integer.class,sku)).isEqualTo(1);
  long orders=jdbc.queryForObject("SELECT count(*) FROM orders",Long.class);
  long outbox=jdbc.queryForObject("SELECT count(*) FROM outbox",Long.class);
  assertThat(http.exchange("/api/v1/orders/direct",HttpMethod.POST,new HttpEntity<>(order,headers),JsonNode.class).getStatusCode().isError()).isTrue();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM orders",Long.class)).isEqualTo(orders);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox",Long.class)).isEqualTo(outbox);
  assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product_skus WHERE id=?",Integer.class,sku)).isEqualTo(1);
  assertThat(http.exchange("/api/v1/products/"+id,HttpMethod.PATCH,new HttpEntity<>(Map.of("name","검정 고양이 간식")),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(http.getForEntity("/api/v1/products?keyword=검정",JsonNode.class).getBody().path("items").toString()).contains("검정 고양이 간식");
  assertThat(http.getForEntity("/api/v1/products?keyword=하얀",JsonNode.class).getBody().path("items").size()).isZero();
 }
}
