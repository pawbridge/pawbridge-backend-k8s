package com.pawbridge.communityservice.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.context.WebApplicationContext;

class PrivateNoteStreamTransportTest {
    private AnnotationConfigServletWebServerApplicationContext context;
    private PrivateNoteStream stream;
    private int port;

    @BeforeEach
    void start() {
        context = new AnnotationConfigServletWebServerApplicationContext(Config.class);
        port = context.getWebServer().getPort();
        stream = context.getBean(PrivateNoteStream.class);
    }

    @AfterEach
    void stop() { if (context != null) context.close(); }

    @Test
    @Timeout(30)
    void givenTwoLiveHttpStreams_whenOneTabReloadsRepeatedly_thenKeepOtherStreamAndAdmitEveryReplacement() throws Exception {
        UUID firstTab = UUID.randomUUID(), secondTab = UUID.randomUUID();
        try (LiveStream other = open(secondTab)) {
            assertThat(other.status).isEqualTo(200);
            assertThat(other.event()).isEqualTo("ready");
            for (int reload = 0; reload < 12; reload++) {
                try (LiveStream refreshing = open(firstTab)) {
                    assertThat(refreshing.status).as("reload %s", reload).isEqualTo(200);
                    assertThat(refreshing.event()).isEqualTo("ready");
                }
                stream.heartbeat();
                assertThat(other.event()).as("other tab after reload %s", reload).isEqualTo("heartbeat");
            }
        }
    }

    @Test
    @Timeout(20)
    void givenThreeLiveTabIdentities_whenFourthRequests_thenRejectWithoutClosingExistingStreams() throws Exception {
        try (LiveStream one = open(UUID.randomUUID()); LiveStream two = open(UUID.randomUUID());
                LiveStream three = open(UUID.randomUUID()); LiveStream fourth = open(UUID.randomUUID())) {
            assertThat(one.event()).isEqualTo("ready");
            assertThat(two.event()).isEqualTo("ready");
            assertThat(three.event()).isEqualTo("ready");
            assertThat(fourth.status).isEqualTo(429);
            assertThat(fourth.connection.getHeaderField("Retry-After")).isEqualTo("15");
            stream.heartbeat();
            assertThat(one.event()).isEqualTo("heartbeat");
            assertThat(two.event()).isEqualTo("heartbeat");
            assertThat(three.event()).isEqualTo("heartbeat");
        }
    }

    private LiveStream open(UUID clientId) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port
                + "/api/v1/notes/stream?clientId=" + clientId).toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("X-User-Id", "7");
        connection.setRequestProperty("X-Auth-Expires-At", Long.toString(Instant.now().plusSeconds(120).toEpochMilli()));
        return new LiveStream(connection);
    }

    private static final class LiveStream implements AutoCloseable {
        final HttpURLConnection connection;
        final int status;
        final BufferedReader reader;
        LiveStream(HttpURLConnection connection) throws Exception {
            this.connection = connection;
            status = connection.getResponseCode();
            reader = status == 200 ? new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) : null;
        }
        String event() throws Exception {
            String event = null, line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) event = line.substring(6).trim();
                if (line.isEmpty()) return event;
            }
            return null;
        }
        @Override public void close() throws Exception {
            try { if (reader != null) reader.close(); }
            finally { connection.disconnect(); }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Config {
        @Bean TomcatServletWebServerFactory server() { return new TomcatServletWebServerFactory(0); }
        @Bean ServletRegistrationBean<DispatcherServlet> dispatcher(WebApplicationContext webContext) {
            ServletRegistrationBean<DispatcherServlet> registration =
                    new ServletRegistrationBean<>(new DispatcherServlet(webContext), "/");
            registration.setAsyncSupported(true);
            return registration;
        }
        @Bean PrivateNoteStream stream() { return new PrivateNoteStream(); }
        @Bean PrivateNoteService service() { return mock(PrivateNoteService.class); }
        @Bean PrivateNoteController controller(PrivateNoteService service, PrivateNoteStream stream) {
            return new PrivateNoteController(service, stream);
        }
    }
}
