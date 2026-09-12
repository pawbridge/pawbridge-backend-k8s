package com.pawbridge.animalservice.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.web.bind.annotation.*;

/** Opt-in local E2E: actual collector, MySQL, read service and HTTP; synthetic provider only. */
public final class PetTravelContractServer {
    private static final AtomicInteger requests=new AtomicInteger();
    private static final AtomicBoolean hidden=new AtomicBoolean();
    public static void main(String[] args) {
        if (!"true".equals(System.getenv("PAWBRIDGE_TRAVEL_CONTRACT_TEST"))) throw new IllegalStateException("Explicit opt-in required");
        new SpringApplicationBuilder(Config.class).web(WebApplicationType.SERVLET)
                .profiles("travel-contract-fixture").properties("spring.config.name=travel-contract-fixture",
                "server.port=18081","server.address=127.0.0.1","spring.main.banner-mode=off").run();
        System.out.println("TRAVEL_DB_CONTRACT_READY requests="+requests.get());
    }
    @Configuration(proxyBeanMethods=false)
    @Profile("travel-contract-fixture")
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class,DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class})
    static class Config {
        @Bean PetTravelCatalog catalog() throws Exception {
            String port=System.getenv("ANIMAL_MIGRATION_TEST_PORT");
            if (port==null || !port.matches("[0-9]{1,5}")) throw new IllegalStateException("Disposable DB port required");
            var ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"+port+"/pawbridge_animal","root","local_flyway_test_only");
            var jdbc=new JdbcTemplate(ds);
            if (!"animal-flyway-disposable".equals(jdbc.queryForObject("SELECT marker FROM flyway_test_guard.guard",String.class)))
                throw new IllegalStateException("Disposable marker required");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM pet_travel_targets",Integer.class)!=0)
                throw new IllegalStateException("Fresh catalog required");
            var catalog=new PetTravelCatalog(jdbc,new ObjectMapper(),new DataSourceTransactionManager(ds));
            var properties=new TourApiProperties();properties.setEnabled(true);properties.setMaxDetailsPerRun(1);
            var client=new TourApiClient(properties,new ObjectMapper()) {
                @Override public List<Map<String,String>> fetch(Operation operation,String argument) {
                    requests.incrementAndGet();
                    return switch(operation) {
                        case REGIONS -> List.of(Map.of("code","36110","name","세종특별자치시"),Map.of("code","26","name","부산"));
                        case COMMON -> List.of(Map.of("contentid",argument,"title","123".equals(argument)?"통합 검증 장소":"동반 안내 미제공 장소",
                                "addr1","세종특별자치시 테스트 주소","overview","DB 수집 검증용 가상 장소"));
                        case PET -> "124".equals(argument)?List.of():List.of(Map.of("contentid",argument,"acmpyNeedMtr","목줄 착용·배변 봉투 지참"));
                        default -> throw new AssertionError("Unexpected provider operation");
                    };
                }
                @Override public Page fetchPage(Operation operation,String shown,int page) {
                    requests.incrementAndGet();
                    if ("0".equals(shown)) return new Page(hidden.get()?List.of(Map.of(
                            "contentid","125","lDongRegnCd","36110","modifiedtime","20260913000000","showflag","0")):List.of(),hidden.get()?1:0);
                    var rows=new ArrayList<Map<String,String>>(List.of(
                            Map.of("contentid","123","title","통합 검증 장소","lDongRegnCd","36110","modifiedtime","20260912000000","showflag","1"),
                            Map.of("contentid","124","title","동반 안내 미제공 장소","lDongRegnCd","36110","modifiedtime","20260912000000","showflag","1")));
                    if (!hidden.get()) rows.add(Map.of("contentid","125","title","비표출 전환 검증 장소","lDongRegnCd","36110","modifiedtime","20260912000000","showflag","1"));
                    return new Page(rows,rows.size());
                }
            };
            collector=new PetTravelCollector(ds,catalog,client,properties,Clock.systemUTC());
            var result=collector.collect();
            if (!"PARTIAL".equals(result.status()) || result.published()!=1) throw new IllegalStateException("Fixture collection failed");
            return catalog;
        }
        @Bean PetTravelService service(PetTravelCatalog catalog) { return new PetTravelService(catalog); }
        @Bean PetTravelController controller(PetTravelService service) { return new PetTravelController(service); }
        @Bean FixtureState fixtureState(PetTravelCatalog catalog) { return new FixtureState(catalog); }
    }
    private static PetTravelCollector collector;
    @RestController
    static class FixtureState {
        private final PetTravelCatalog catalog;
        FixtureState(PetTravelCatalog catalog) {this.catalog=catalog;}
        @GetMapping("/__fixture/requests") Map<String,Integer> requests() {return Map.of("requests",requests.get());}
        @PostMapping("/__fixture/enrich") PetTravelCollector.Result enrich() throws Exception { return collector.collect(); }
        @PostMapping("/__fixture/hide") PetTravelCollector.Result hide() throws Exception {
            hidden.set(true);
            var result=collector.collect();
            // Add after the final inventory cycle: this fixture region has never been collected.
            catalog.saveRegions(Map.of("51","강원"),Instant.now());
            return result;
        }
        @PostMapping("/__fixture/failure") void failure() {
            catalog.finishRun("fixture-display-state","FAILED","COLLECTION_FAILED",0,0,0,Instant.now());
        }
    }
}
