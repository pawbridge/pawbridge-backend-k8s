package com.pawbridge.animalservice.lostsearch;

import com.pawbridge.animalservice.client.PythonLostSearchClient;
import com.sun.net.httpserver.HttpServer;
import feign.Logger;
import feign.Request;
import feign.form.FormData;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.*;

class PythonLostSearchClientTest {
    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = PythonLostSearchClient.class)
    static class Root {
        @Bean Logger.Level existingGlobalLoggerLevel() { return Logger.Level.FULL; }
    }

    @Test
    void givenMultipartContract__whenCallingPython__thenSendBinaryAndOptionalFieldsWithIsolatedLogging() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> type = new AtomicReference<>();
        server.createContext("/internal/animals/lost-candidates", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            key.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            type.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] json = "{\"candidates\":[{\"animalId\":7,\"imageScore\":0.8,\"matchedEvidence\":[]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    FeignAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class))
                    .withUserConfiguration(Root.class)
                    .withPropertyValues("python-ai-service.url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(context -> {
                        var client = context.getBean(PythonLostSearchClient.class);
                        var result = client.search("test-key", new FormData("application/octet-stream", "photo", "photo-bytes".getBytes()),
                                "DOG", "2026-09-08", "상주시", "갈색 귀", true);
                        assertThat(result.candidates()).extracting(PythonLostSearchResponse.Candidate::animalId).containsExactly(7L);
                        assertThat(type.get()).startsWith("multipart/form-data;");
                        assertThat(key.get()).isEqualTo("test-key");
                        assertThat(body.get()).contains("name=\"image\"", "filename=\"photo\"", "photo-bytes",
                                "name=\"species\"", "DOG", "name=\"lostDate\"", "2026-09-08", "상주시", "갈색 귀",
                                "name=\"includeAdoptedOrReturned\"", "true");
                        var factory = context.getBean(FeignClientFactory.class);
                        assertThat(factory.getInstance("python-lost-search", Logger.Level.class)).isEqualTo(Logger.Level.NONE);
                        assertThat(factory.getInstance("python-lost-search", Request.Options.class).isFollowRedirects()).isFalse();
                        client.search("test-key", new FormData("application/octet-stream", "photo", new byte[]{1}), "CAT", null, null, null, false);
                        assertThat(body.get()).doesNotContain("name=\"lostDate\"", "name=\"region\"", "name=\"description\"");
                        assertThat(body.get()).contains("name=\"includeAdoptedOrReturned\"", "false");
                    });
        } finally { server.stop(0); }
    }
}
