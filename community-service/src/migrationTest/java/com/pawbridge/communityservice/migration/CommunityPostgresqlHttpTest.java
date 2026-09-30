package com.pawbridge.communityservice.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.service.S3Service;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** Full application and real HTTP; only remote storage/user lookup are replaced. */
@Tag("postgresql-http")
@EnabledIfEnvironmentVariable(named="PG_HTTP_TEST_PORT",matches="[0-9]{1,5}")
@ActiveProfiles("postgresql")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.flyway.enabled=false","spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.listener.auto-startup=false","management.tracing.enabled=false",
        "R2_ACCESS_KEY_ID=isolated-test","R2_SECRET_ACCESS_KEY=isolated-test",
        "R2_ENDPOINT=http://127.0.0.1:1","R2_BUCKET_NAME=isolated-test","R2_REGION=us-east-1",
        "pawbridge.search.rebuild-enabled=false"})
class CommunityPostgresqlHttpTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean S3Service storage;
    @MockitoBean UserServiceClient users;

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
        properties.add("COMMUNITY_POSTGRESQL_JDBC_URL",()->url);
        properties.add("COMMUNITY_POSTGRESQL_USERNAME",()->"postgres");
        properties.add("COMMUNITY_POSTGRESQL_PASSWORD",()->"local_pg_test_only");
        properties.add("COMMUNITY_POSTGRESQL_POOL_MAX",()->"2");
    }
    @BeforeEach void remoteBoundaries() {
        when(storage.uploadImages(any())).thenReturn(List.of());
        when(storage.uploadReportImages(org.mockito.ArgumentMatchers.isNull())).thenReturn(List.of());
        when(users.getUserNickname(anyLong())).thenReturn("격리 검증 회원");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings={" ", "   "})
    void missing_empty_or_blank_keyword_keeps_public_report_lists_available(String keyword) {
        ResponseEntity<JsonNode> created=reportWrite(HttpMethod.POST,"/api/v1/reports","검색어 없는 목록 검증",101);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        long id=created.getBody().path("data").path("reportId").asLong();
        for(String kind : new String[]{null, "SIGHTING", "MISSING"}) {
            var uri=org.springframework.web.util.UriComponentsBuilder.fromPath("/api/v1/reports")
                    .queryParam("size",50);
            if(kind!=null)uri.queryParam("kind",kind);
            if(keyword!=null)uri.queryParam("keyword",keyword);
            ResponseEntity<JsonNode> listed=http.getForEntity(uri.build().toUriString(),JsonNode.class);
            assertThat(listed.getStatusCode()).as("kind=%s, keyword=%s",kind,keyword).isEqualTo(HttpStatus.OK);
            String countSql="SELECT count(*) FROM animal_reports WHERE deleted_at IS NULL";
            long expected=kind==null ? jdbc.queryForObject(countSql,Long.class)
                    : jdbc.queryForObject(countSql+" AND report_kind=?",Long.class,kind);
            assertThat(listed.getBody().path("data").path("totalElements").asLong()).isEqualTo(expected);
            if(!"MISSING".equals(kind)) {
                assertThat(listed.getBody().path("data").path("content").toString()).contains("\"reportId\":"+id);
            } else {
                assertThat(listed.getBody().path("data").path("content").toString()).doesNotContain("\"reportId\":"+id);
            }
        }
    }

    @Test void animal_report_http_flow_uses_v4_and_hides_soft_deleted_reports() {
        ResponseEntity<JsonNode> created=reportWrite(HttpMethod.POST,"/api/v1/reports","공원에서 발견",101);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        long id=created.getBody().path("data").path("reportId").asLong();
        assertThat(id).isPositive();
        assertThat(created.getBody().path("data").path("kind").asText()).isEqualTo("SIGHTING");
        assertThat(http.getForEntity("/api/v1/reports/"+id,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> listed=http.getForEntity("/api/v1/reports?kind=SIGHTING&keyword=마포",JsonNode.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody().path("data").path("content").toString()).contains("\"reportId\":"+id);
        assertThat(reportWrite(HttpMethod.PUT,"/api/v1/reports/"+id,"권한 없는 수정",102).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reportWrite(HttpMethod.PUT,"/api/v1/reports/"+id,"위치 갱신",101).getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers=new HttpHeaders();headers.set("X-User-Id","101");
        assertThat(http.exchange("/api/v1/reports/"+id,HttpMethod.DELETE,new HttpEntity<>(headers),JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/v1/reports/"+id,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM animal_reports WHERE report_id=? AND deleted_at IS NOT NULL",Long.class,id)).isEqualTo(1);
    }
    @Test void create_update_delete_keep_http_search_and_outbox_consistent() {
        ResponseEntity<JsonNode> created=write(HttpMethod.POST,"/api/v1/posts","하얀 강아지",101);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        long id=created.getBody().path("data").path("postId").asLong();
        assertThat(id).isPositive();
        assertThat(search("하얀").toString()).contains("하얀 강아지");
        assertThat(write(HttpMethod.PUT,"/api/v1/posts/"+id,"잘못된 수정",999).getStatusCode().is4xxClientError()).isTrue();
        assertThat(write(HttpMethod.PUT,"/api/v1/posts/"+id,"검정 고양이",101).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(search("하얀").path("data")).isEmpty();
        assertThat(search("검정").toString()).contains("검정 고양이");
        HttpHeaders headers=new HttpHeaders();headers.set("X-User-Id","101");
        assertThat(http.exchange("/api/v1/posts/"+id,HttpMethod.DELETE,new HttpEntity<>(headers),JsonNode.class)
                .getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(search("검정").path("data")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_search_documents WHERE post_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?",Long.class,Long.toString(id))).isEqualTo(3);
    }
    @Test void failed_outbox_write_rolls_back_http_post_and_search_document() {
        long posts=jdbc.queryForObject("SELECT count(*) FROM posts",Long.class);
        long docs=jdbc.queryForObject("SELECT count(*) FROM post_search_documents",Long.class);
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT http_test_reject_create CHECK(type<>'POST_CREATED') NOT VALID");
        try {
            assertThat(write(HttpMethod.POST,"/api/v1/posts","롤백 대상",101).getStatusCode().is5xxServerError()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM posts",Long.class)).isEqualTo(posts);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM post_search_documents",Long.class)).isEqualTo(docs);
        } finally {jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT http_test_reject_create");}
    }
    private JsonNode search(String term) {
        ResponseEntity<JsonNode> response=http.getForEntity("/api/v1/posts/search?keyword={term}",JsonNode.class,term);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);return response.getBody();
    }
    private ResponseEntity<JsonNode> write(HttpMethod method,String path,String title,long user) {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.MULTIPART_FORM_DATA);headers.set("X-User-Id",Long.toString(user));
        MultiValueMap<String,Object> fields=new LinkedMultiValueMap<>();fields.add("title",title);fields.add("content","격리 데이터베이스 검증");fields.add("boardType","ADOPTION");
        return http.exchange(path,method,new HttpEntity<>(fields,headers),JsonNode.class);
    }
    private ResponseEntity<JsonNode> reportWrite(HttpMethod method,String path,String description,long user) {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.MULTIPART_FORM_DATA);headers.set("X-User-Id",Long.toString(user));
        HttpHeaders partHeaders=new HttpHeaders();partHeaders.setContentType(MediaType.APPLICATION_JSON);
        String report="{\"kind\":\"SIGHTING\",\"occurredOn\":\"2026-09-20\",\"region\":\"서울 마포구\","
                +"\"species\":\"강아지\",\"description\":\""+description+"\"}";
        MultiValueMap<String,Object> fields=new LinkedMultiValueMap<>();
        fields.add("report",new HttpEntity<>(report,partHeaders));
        return http.exchange(path,method,new HttpEntity<>(fields,headers),JsonNode.class);
    }
}
