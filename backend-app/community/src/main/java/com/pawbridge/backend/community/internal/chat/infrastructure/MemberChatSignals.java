package com.pawbridge.backend.community.internal.chat.infrastructure;
import com.pawbridge.backend.community.internal.chat.application.MemberChat;
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException;
import com.pawbridge.backend.community.internal.chat.web.MemberChatSessions;
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Signal;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.user.SimpUserRegistry;

public class MemberChatSignals implements MessageListener, MemberChatHints {
    private static final Logger log = LoggerFactory.getLogger(MemberChatSignals.class);
    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messaging;
    private final MemberChatSessions sessions;
    private final SimpUserRegistry registry;
    private final ObjectMapper mapper;
    private final String channel;

    public MemberChatSignals(StringRedisTemplate redis, SimpMessagingTemplate messaging,
                             MemberChatSessions sessions, ObjectMapper mapper, SimpUserRegistry registry,
                             @Value("${pawbridge.chat.namespace}") String namespace) {
        this.redis = redis;
        this.messaging = messaging;
        this.sessions = sessions;
        this.registry = registry;
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
                var member = registry.getUser(Long.toString(signal.memberId()));
                if (member == null) return;
                for (var session : member.getSessions()) {
                    // Each ordered user-destination send needs its own mutable headers.
                    // Reuse the session-targeted receipt path instead of one fanout message.
                    var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
                    headers.setSessionId(session.getId());
                    headers.setLeaveMutable(true);
                    try {
                        messaging.convertAndSendToUser(session.getId(), "/queue/chat", signal, headers.getMessageHeaders());
                    } catch (RuntimeException unavailable) {
                        // A failing connection must not suppress another browser's signal.
                        log.warn("Chat session signal delivery unavailable; REST reconciliation required");
                    }
                }
            }
        } catch (Exception unavailable) {
            log.warn("Chat signal processing unavailable; REST reconciliation required");
        }
    }
}
