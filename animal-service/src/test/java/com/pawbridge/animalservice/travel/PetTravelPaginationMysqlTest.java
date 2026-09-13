package com.pawbridge.animalservice.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in fresh local MySQL only. Never accepts an arbitrary JDBC URL or credentials. */
@EnabledIfEnvironmentVariable(named="PAWBRIDGE_TRAVEL_PAGINATION_MYSQL_PORT", matches="[0-9]{1,5}")
class PetTravelPaginationMysqlTest {
    private static AnnotationConfigApplicationContext context;
    private static PetTravelCatalog catalog;
    private static PetTravelService service;
    private static DataSource source;

    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource source() {
            int port=Integer.parseInt(System.getenv("PAWBRIDGE_TRAVEL_PAGINATION_MYSQL_PORT"));
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid disposable port");
            return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"+port+"/pawbridge_travel_pagination_test",
                    "travel_test","local_pagination_test_only");
        }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean PetTravelCatalog catalog(DataSource source,PlatformTransactionManager manager) {
            return spy(new PetTravelCatalog(new JdbcTemplate(source),new ObjectMapper(),manager));
        }
        @Bean PetTravelService service(PetTravelCatalog catalog) { return new PetTravelService(catalog); }
    }

    @BeforeAll static void fixture() {
        context=new AnnotationConfigApplicationContext(Config.class);
        source=context.getBean(DataSource.class);
        var jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class))
                .as("fresh disposable schema required; do not reuse any populated database").isZero();
        new ResourceDatabasePopulator(
                new FileSystemResource("src/migration/resources/db/migration/V2__pet_travel_catalog.sql"),
                new FileSystemResource("src/migration/resources/db/migration/V3__pet_travel_list_first.sql"),
                new FileSystemResource("src/migration/resources/db/migration/V6__pet_travel_bulk_details.sql")).execute(source);
        catalog=context.getBean(PetTravelCatalog.class);
        service=context.getBean(PetTravelService.class);
        catalog.saveRegions(Map.of("11","서울","26","부산"),Instant.EPOCH);
        for (int i=0;i<20;i++) basic(String.valueOf(1000+i),"11","1");
        basic("9000","11","0"); // hidden target
        basic("9001","26","1"); // different region
        jdbc.update("INSERT INTO pet_travel_targets(provider,content_id,area_code,modified_time,shown,generation,pending,observed_at) "
                +"VALUES ('KOREA_TOURISM_ORGANIZATION','1020','11','20260913000000',TRUE,1,FALSE,NOW()),"
                +"('KOREA_TOURISM_ORGANIZATION','9002','11','20260913000000',TRUE,1,FALSE,NOW())");
        // One legacy visible snapshot is readable without basic_data; the other has no public data.
        jdbc.update("INSERT INTO pet_travel_places(provider,content_id,area_code,title,common_data,pet_data,visible,published_at) "
                +"VALUES ('KOREA_TOURISM_ORGANIZATION','1020','11','공원',JSON_OBJECT('contentid','1020','title','공원'),JSON_OBJECT(),TRUE,NOW())");
    }

    private static void basic(String id,String region,String shown) {
        catalog.observeBasic(Map.of("contentid",id,"title","공원","modifiedtime","20260913000000","showflag",shown),region,Instant.EPOCH);
    }

    @AfterAll static void close() { if(context!=null) context.close(); }

    @Test void givenMixedPublicData__whenPaging__thenStableTiesAndMatchingCount() {
        var first=service.places("11",0);
        var second=service.places("11",1);
        var last=service.places("11",2);
        assertThat(first.totalElements()).isEqualTo(21);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.items()).extracting(PetTravelResponse.Place::contentId)
                .containsExactlyElementsOf(IntStream.range(1000,1010).mapToObj(String::valueOf).toList());
        assertThat(second.items()).extracting(PetTravelResponse.Place::contentId)
                .containsExactlyElementsOf(IntStream.range(1010,1020).mapToObj(String::valueOf).toList());
        assertThat(last.items()).extracting(PetTravelResponse.Place::contentId).containsExactly("1020");
        assertThat(service.places("11",Integer.MAX_VALUE).items()).isEmpty();
        assertThat(service.places("26",0).totalElements()).isEqualTo(1);
    }

    @Test void givenConcurrentHideAfterCount__whenRead__thenOneResponseUsesSameSnapshot() throws Exception {
        doAnswer(call -> {
            long count=(long)call.callRealMethod();
            // A separate committed connection represents a concurrent collector.
            try(var connection=source.getConnection();var statement=connection.createStatement()) {
                statement.executeUpdate("UPDATE pet_travel_targets SET shown=FALSE WHERE content_id='1020'");
            }
            return count;
        }).when(catalog).countPlaces("11");
        try {
            var response=service.places("11",2);
            assertThat(response.totalElements()).isEqualTo(21);
            assertThat(response.items()).extracting(PetTravelResponse.Place::contentId).containsExactly("1020");
        } finally {
            doCallRealMethod().when(catalog).countPlaces("11");
            new JdbcTemplate(source).update("UPDATE pet_travel_targets SET shown=TRUE WHERE content_id='1020'");
        }
    }
}
