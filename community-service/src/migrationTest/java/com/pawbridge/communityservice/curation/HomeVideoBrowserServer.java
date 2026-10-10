package com.pawbridge.communityservice.curation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** Real local controllers/services/PostgreSQL, with ONLY the external YouTube provider synthesized.
 * No production credentials, User login or background jobs are used.
 */
@Configuration
@ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class, ValidationAutoConfiguration.class})
@Import({HomeVideoRepository.class, HomeVideoService.class, HomeVideoController.class, HomeVideoExceptionAdvice.class})
public class HomeVideoBrowserServer {
    static DataSource source;
    static HttpServer provider;
    public static void main(String[] args) throws Exception {
        String port=System.getenv("HOME_VIDEOS_PG_TEST_PORT");
        if(port==null || !port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Disposable DB port required");
        source=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+port+"/pawbridge","postgres","local_pg_test_only");
        var jdbc=new JdbcTemplate(source);
        if(!jdbc.queryForList("SELECT marker FROM migration_test_guard.guard",String.class)
                .equals(java.util.List.of("services-pg-disposable"))) throw new IllegalStateException("Disposable guard missing");
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS pawbridge_community");
        Flyway.configure().dataSource(source).defaultSchema("pawbridge_community").locations("classpath:db/postgresql")
                .cleanDisabled(true).outOfOrder(false).loggers(new String[0]).load().migrate();
        if(jdbc.queryForObject("SELECT count(*) FROM pawbridge_community.home_videos",Long.class) != 0)
            throw new IllegalStateException("Browser fixture requires an empty task-owned video catalog; will not clear existing data");
        provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/videos", exchange -> {
            String query=exchange.getRequestURI().getQuery();
            String ids=java.util.Arrays.stream(query.split("&")).filter(p -> p.startsWith("id="))
                    .map(p -> p.substring(3)).findFirst().orElse("");
            String items=java.util.Arrays.stream(ids.split(",")).filter(id -> id.matches("[A-Za-z0-9_-]{11}"))
                    .map(id -> """
                            {"id":"%s","snippet":{"title":"함께하는 일상 %s","channelTitle":"로컬 검증 채널",
                            "liveBroadcastContent":"none","thumbnails":{"high":{"url":"https://i.ytimg.com/vi/%s/hqdefault.jpg"}}},
                            "contentDetails":{"duration":"PT3M24S"},"status":{"privacyStatus":"public","uploadStatus":"processed","embeddable":true}}
                            """.formatted(id,id,id)).collect(java.util.stream.Collectors.joining(","));
            byte[] body=("{\"items\":["+items+"]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        provider.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> provider.stop(0)));
        new SpringApplication(HomeVideoBrowserServer.class).run(
                "--server.address=127.0.0.1", "--server.port=28282",
                "--spring.main.banner-mode=off");
    }
    @Bean DataSource source() {return source;}
    @Bean JdbcTemplate jdbcTemplate() {return new JdbcTemplate(source);}
    @Bean DataSourceTransactionManager transactionManager() {return new DataSourceTransactionManager(source);}
    @Bean Clock videoClock() {return Clock.systemUTC();}
    @Bean YouTubeVideoClient youtube(ObjectMapper mapper) {
        return new YouTubeVideoClient(mapper,"synthetic-local-key",HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:"+provider.getAddress().getPort()+"/videos"));
    }
}
