package com.pawbridge.communityservice.chat;

import java.util.Arrays;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

@Configuration
@Profile("postgresql")
@ConditionalOnProperty(name = "pawbridge.chat.enabled", havingValue = "true")
@EnableWebSocketMessageBroker
public class MemberChatSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private final MemberChatTickets tickets;
    private final ObjectProvider<MemberChatSessions> sessions;
    private final ObjectProvider<MemberChatService> service;
    private final String[] origins;

    public MemberChatSocketConfiguration(MemberChatTickets tickets, ObjectProvider<MemberChatSessions> sessions,
            ObjectProvider<MemberChatService> service,
            @Value("${pawbridge.chat.allowed-origins}") String origins) {
        this.tickets = tickets;
        this.sessions = sessions;
        this.service = service;
        this.origins = Arrays.stream(origins.split(",")).map(String::strip).toArray(String[]::new);
        if (this.origins.length == 0 || Arrays.stream(this.origins).anyMatch(origin ->
                !origin.matches("https?://[a-zA-Z0-9.:-]+") || origin.contains("*"))) {
            throw new IllegalArgumentException("Explicit chat origins required");
        }
    }

    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(new MemberChatProtocolErrors());
        registry.setPreserveReceiveOrder(true);
        registry.addEndpoint("/api/v1/chats/socket").setAllowedOrigins(origins);
    }

    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
        registry.enableSimpleBroker("/queue").setTaskScheduler(chatHeartbeatScheduler()).setHeartbeatValue(new long[]{15000,15000});
    }

    @Bean public ThreadPoolTaskScheduler chatHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-heartbeat-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Override public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(16 * 1024).setSendBufferSizeLimit(64 * 1024).setSendTimeLimit(10_000);
        registry.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
                if (!sessions.getObject().open(socket)) { socket.close(CloseStatus.POLICY_VIOLATION); return; }
                try { super.afterConnectionEstablished(socket); }
                catch (Exception failure) { sessions.getObject().removed(socket.getId()); throw failure; }
            }
            @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) throws Exception {
                try { super.afterConnectionClosed(socket, status); }
                finally { sessions.getObject().removed(socket.getId()); }
            }
        });
    }

    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(2).maxPoolSize(4).queueCapacity(100);
        registration.interceptors(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                try { return authorized(message, headers); }
                catch (RuntimeException denied) {
                    // Ordered receive catches thrown errors and can log the entire private frame.
                    // Close explicitly and drop the frame; do not pass the exception or payload onward.
                    if (headers != null && headers.getSessionId() != null) {
                        try { sessions.getObject().close(headers.getSessionId()); }
                        catch (RuntimeException unavailable) { /* Lease expiry is the bounded fallback. */ }
                    }
                    return null;
                }
            }

            private Message<?> authorized(Message<?> message, StompHeaderAccessor headers) {
                if (headers == null) throw new IllegalArgumentException("STOMP required");
                StompCommand command = headers.getCommand();
                if (command == StompCommand.DISCONNECT) return message;
                if (command == StompCommand.CONNECT) {
                    var identity = tickets.consume(headers.getFirstNativeHeader("ticket"));
                    service.getObject().active(identity.memberId());
                    sessions.getObject().authenticate(headers.getSessionId(), identity);
                    headers.setUser(identity);
                    return message;
                }
                sessions.getObject().requireCurrent(headers.getSessionId());
                if (command == StompCommand.SUBSCRIBE && !"/user/queue/chat".equals(headers.getDestination())) {
                    throw new IllegalArgumentException("Only own chat queue is permitted");
                }
                if (command == StompCommand.SUBSCRIBE) {
                    if (headers.getSubscriptionId() == null || headers.getSubscriptionId().length() > 64) {
                        throw new IllegalArgumentException("Invalid subscription");
                    }
                    synchronized (headers.getSessionAttributes()) {
                        if (headers.getSessionAttributes().putIfAbsent("chatSubscription", headers.getSubscriptionId()) != null) {
                            throw new IllegalArgumentException("Only one chat subscription per connection is permitted");
                        }
                    }
                }
                if (command == StompCommand.UNSUBSCRIBE) {
                    synchronized (headers.getSessionAttributes()) {
                        headers.getSessionAttributes().remove("chatSubscription", headers.getSubscriptionId());
                    }
                }
                if (command == StompCommand.SEND && !"/app/chat/send".equals(headers.getDestination())) {
                    throw new IllegalArgumentException("Chat destination not permitted");
                }
                if (command != null && command != StompCommand.SEND && command != StompCommand.SUBSCRIBE &&
                    command != StompCommand.UNSUBSCRIBE) throw new IllegalArgumentException("Command not permitted");
                return message;
            }
        });
    }

    @Override public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(2).maxPoolSize(4).queueCapacity(100);
        registration.interceptors(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (headers != null && headers.getCommand() == StompCommand.MESSAGE) {
                    try { sessions.getObject().requireCurrent(headers.getSessionId()); }
                    catch (RuntimeException expired) { return null; }
                }
                return message;
            }
        });
    }

    @Bean public RedisMessageListenerContainer chatRedisListener(RedisConnectionFactory factory,
                                                                MemberChatSignals signals, ThreadPoolTaskExecutor chatSignalExecutor) {
        RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(factory);
        listener.setTaskExecutor(chatSignalExecutor);
        listener.addMessageListener(signals, new ChannelTopic(signals.channel()));
        return listener;
    }

    @Bean public ThreadPoolTaskExecutor chatSignalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("chat-signal-");
        return executor;
    }
}
