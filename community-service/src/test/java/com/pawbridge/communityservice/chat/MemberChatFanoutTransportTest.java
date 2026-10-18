package com.pawbridge.communityservice.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;

class MemberChatFanoutTransportTest {
    @Test @Timeout(30)
    void givenTwoConnectionsOfSameMember_whenSignalsRepeat_thenDeliverEverySignalToBothLiveSubscriptions() throws Exception {
        try (var context = new AnnotationConfigServletWebServerApplicationContext(Config.class, SocketConfig.class)) {
            int port = context.getWebServer().getPort();
            var registry = context.getBean(SimpUserRegistry.class);
            var signals = context.getBean(MemberChatSignals.class);
            try (var first = new LiveSocket(port); var second = new LiveSocket(port); var otherMember = new LiveSocket(port, "other-member")) {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < until && (registry.getUser("7") == null
                        || registry.getUser("7").getSessions().stream().mapToInt(s -> s.getSubscriptions().size()).sum() != 2
                        || registry.getUser("8") == null
                        || registry.getUser("8").getSessions().stream().mapToInt(s -> s.getSubscriptions().size()).sum() != 1)) {
                    Thread.sleep(20);
                }
                assertThat(registry.getUser("7").getSessions()).hasSize(2);
                assertThat(registry.getUser("7").getSessions().stream().mapToInt(s -> s.getSubscriptions().size()).sum()).isEqualTo(2);
                assertThat(registry.getUser("8").getSessions().stream().mapToInt(s -> s.getSubscriptions().size()).sum()).isEqualTo(1);
                UUID room = UUID.randomUUID();
                for (int sequence = 1; sequence <= 10; sequence++) {
                    var body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(new MemberChatModels.Signal(7, "MESSAGE", room, sequence));
                    signals.onMessage(new org.springframework.data.redis.connection.DefaultMessage(new byte[0], body), null);
                    assertThat(first.frames.poll(2, TimeUnit.SECONDS)).as("first connection sequence %s", sequence).contains("\"sequence\":" + sequence);
                    assertThat(second.frames.poll(2, TimeUnit.SECONDS)).as("second connection sequence %s", sequence).contains("\"sequence\":" + sequence);
                }
                assertThat(otherMember.frames.poll(250, TimeUnit.MILLISECONDS)).isNull();
                first.close();
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < until && registry.getUser("7").getSessions().size() != 1) Thread.sleep(20);
                assertThat(registry.getUser("7").getSessions()).hasSize(1);
                var last = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(new MemberChatModels.Signal(7, "SENT", room, 11));
                signals.onMessage(new org.springframework.data.redis.connection.DefaultMessage(new byte[0], last), null);
                assertThat(second.frames.poll(2, TimeUnit.SECONDS)).contains("\"sequence\":11");
                assertThat(otherMember.frames.poll(250, TimeUnit.MILLISECONDS)).isNull();
            }
        }
    }

    static class LiveSocket implements AutoCloseable, WebSocket.Listener {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> connected = new CompletableFuture<>();
        final StringBuilder partial = new StringBuilder();
        final WebSocket socket;
        LiveSocket(int port) throws Exception { this(port, "synthetic-ticket"); }
        LiveSocket(int port, String ticket) throws Exception {
            socket = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", "https://example.test")
                    .buildAsync(URI.create("ws://localhost:" + port + "/api/v1/chats/socket"), this).get(5, TimeUnit.SECONDS);
            socket.sendText("CONNECT\naccept-version:1.2\nheart-beat:0,0\nticket:" + ticket + "\n\n\0", true).join();
            connected.get(5, TimeUnit.SECONDS);
            socket.sendText("SUBSCRIBE\nid:own-chat\ndestination:/user/queue/chat\nack:auto\n\n\0", true).join();
        }
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            partial.append(text);
            if (last) {
                String frame = partial.toString(); partial.setLength(0);
                if (frame.startsWith("CONNECTED")) connected.complete(null);
                else if (frame.startsWith("MESSAGE")) frames.add(frame);
                else if (frame.startsWith("ERROR")) connected.completeExceptionally(new IllegalStateException("STOMP setup failed"));
            }
            socket.request(1); return null;
        }
        @Override public void close() { socket.abort(); }
    }

    @Configuration(proxyBeanMethods = false) @EnableWebMvc
    static class Config {
        @Bean TomcatServletWebServerFactory server() {
            var factory = new TomcatServletWebServerFactory(0);
            factory.addContextCustomizers(context -> context.addServletContainerInitializer(new org.apache.tomcat.websocket.server.WsSci(), null));
            return factory;
        }
        @Bean ServletRegistrationBean<DispatcherServlet> dispatcher(WebApplicationContext context) {
            return new ServletRegistrationBean<>(new DispatcherServlet(context), "/");
        }
        @Bean MemberChatTickets tickets() {
            var tickets = mock(MemberChatTickets.class);
            when(tickets.consume(anyString())).thenAnswer(x -> new MemberChatTickets.Identity(
                    "other-member".equals(x.getArgument(0)) ? 8 : 7, Instant.now().plusSeconds(300).toEpochMilli()));
            return tickets;
        }
        @Bean MemberChatSessions sessions() {
            var sessions = mock(MemberChatSessions.class); when(sessions.open(any())).thenReturn(true); return sessions;
        }
        @Bean MemberChatService service() { return mock(MemberChatService.class); }
        @Bean MemberChatSignals signals(SimpMessagingTemplate messaging, MemberChatSessions sessions, SimpUserRegistry registry) {
            return new MemberChatSignals(mock(org.springframework.data.redis.core.StringRedisTemplate.class), messaging,
                    sessions, new com.fasterxml.jackson.databind.ObjectMapper(), registry, "synthetic-test");
        }
        @Bean RedisConnectionFactory redis() { return mock(RedisConnectionFactory.class); }
    }

    @Configuration @EnableWebSocketMessageBroker
    static class SocketConfig extends MemberChatSocketConfiguration {
        SocketConfig(MemberChatTickets tickets, ObjectProvider<MemberChatSessions> sessions, ObjectProvider<MemberChatService> service) {
            super(tickets, sessions, service, "https://example.test");
        }
        @Override @Bean public RedisMessageListenerContainer chatRedisListener(RedisConnectionFactory factory,
                MemberChatSignals signals, @org.springframework.beans.factory.annotation.Qualifier("chatSignalExecutor") ThreadPoolTaskExecutor executor) { return mock(RedisMessageListenerContainer.class); }
    }
}
