package com.pawbridge.communityservice.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.communityservice.chat.MemberChatModels.Signal;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("postgresql")
@ConditionalOnProperty(name = "pawbridge.chat.enabled", havingValue = "true")
public class MemberChatSignals implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(MemberChatSignals.class);
    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messaging;
    private final MemberChatSessions sessions;
    private final ObjectMapper mapper;
    private final String channel;

    public MemberChatSignals(StringRedisTemplate redis, SimpMessagingTemplate messaging,
                             MemberChatSessions sessions, ObjectMapper mapper,
                             @Value("${pawbridge.chat.namespace}") String namespace) {
        this.redis = redis;
        this.messaging = messaging;
        this.sessions = sessions;
        this.mapper = mapper;
        channel = "pawbridge:" + namespace + ":chat:signals";
    }

    public String channel() { return channel; }

    public void publish(Signal signal) {
        try { redis.convertAndSend(channel, mapper.writeValueAsString(signal)); }
        catch (Exception unavailable) {
            // The DB transaction is already committed. Browser reconciliation repairs a lost signal.
            log.warn("Chat signal delivery unavailable; REST reconciliation required");
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            if (message.getBody().length > 1024) return;
            Signal signal = mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), Signal.class);
            if (signal.memberId() <= 0) return;
            if ("WITHDRAWN".equals(signal.kind())) sessions.closeMember(signal.memberId());
            else if (java.util.List.of("SENT", "MESSAGE", "READ", "HIDDEN").contains(signal.kind())
                    && signal.roomId() != null && signal.sequence() >= 0) {
                messaging.convertAndSendToUser(Long.toString(signal.memberId()), "/queue/chat", signal);
            }
        } catch (Exception unavailable) {
            log.warn("Chat signal processing unavailable; REST reconciliation required");
        }
    }
}
