package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pawbridge.communityservice.client.UserServiceClient;
import feign.Feign;
import feign.FeignException;
import feign.Request;
import feign.Retryer;
import feign.optionals.OptionalDecoder;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The pinned legacy client is decoded with Spring Cloud's default decoder chain. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class LegacyNicknameDecoderCompatibilityTest {
    private ServletWebServerApplicationContext context;
    private UserServiceClient legacyClient;

    @BeforeAll
    void startTestOnlyPlainStringEndpointOnLoopback() {
        context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(OracleWebConfiguration.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
                        "--spring.application.name=f2-legacy-nickname-oracle", "--spring.sql.init.mode=never",
                        "--spring.jmx.enabled=false", "--server.shutdown=immediate");
        var converters = context.getBean(HttpMessageConverters.class);
        legacyClient = Feign.builder()
                .contract(new SpringMvcContract())
                .decoder(new OptionalDecoder(new ResponseEntityDecoder(new SpringDecoder(() -> converters))))
                .options(new Request.Options(2, TimeUnit.SECONDS, 3, TimeUnit.SECONDS, true))
                .retryer(Retryer.NEVER_RETRY)
                .target(UserServiceClient.class, "http://127.0.0.1:" + context.getWebServer().getPort());
    }

    @AfterAll
    void closeTestOnlyServer() {
        if (context != null) context.close();
    }

    @Test
    void givenNormalPlainString__whenLegacyFeignDecodes__thenNicknameIsPreserved() {
        assertThat(legacyClient.getUserNickname(1L)).isEqualTo("달빛 닉네임");
    }

    @Test
    void givenEmptyPlainString__whenLegacyFeignDecodes__thenBodyIsNullWithoutAnHttpFailure() {
        assertThat(legacyClient.getUserNickname(2L)).isNull();
    }

    @Test
    void givenWhitespacePlainString__whenLegacyFeignDecodes__thenWhitespaceIsPreserved() {
        assertThat(legacyClient.getUserNickname(3L)).isEqualTo("  ");
    }

    @Test
    void givenMissingUser__whenLegacyFeignDecodes__thenBadRequestRemainsAFailure() {
        assertThatThrownBy(() -> legacyClient.getUserNickname(4L))
                .isInstanceOf(FeignException.BadRequest.class);
    }

    @Test
    void givenTechnicalFailure__whenLegacyFeignDecodes__thenServerErrorRemainsAFailure() {
        assertThatThrownBy(() -> legacyClient.getUserNickname(5L))
                .isInstanceOf(FeignException.InternalServerError.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class,
            ManagementWebSecurityAutoConfiguration.class
    })
    @Import(PlainStringNicknameEndpoint.class)
    static class OracleWebConfiguration {}

    @RestController
    static class PlainStringNicknameEndpoint {
        @GetMapping("/api/v1/users/internal/{userId}/nickname")
        ResponseEntity<String> nickname(@PathVariable("userId") long userId) {
            return switch ((int) userId) {
                case 1 -> ResponseEntity.ok("달빛 닉네임");
                case 2 -> ResponseEntity.ok("");
                case 3 -> ResponseEntity.ok("  ");
                case 4 -> ResponseEntity.badRequest().build();
                default -> ResponseEntity.internalServerError().body("test-only failure");
            };
        }
    }
}
