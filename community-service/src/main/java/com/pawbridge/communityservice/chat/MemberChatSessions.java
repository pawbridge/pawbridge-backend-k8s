package com.pawbridge.communityservice.chat;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
@Profile("postgresql")
@ConditionalOnProperty(name = "pawbridge.chat.enabled", havingValue = "true")
public class MemberChatSessions {
    private static final DefaultRedisScript<Long> LEASE = new DefaultRedisScript<>("""
            for _,k in ipairs(KEYS) do redis.call('ZREMRANGEBYSCORE',k,'-inf',ARGV[1]) end
            local known=redis.call('ZSCORE',KEYS[1],ARGV[3])
            if not known and (redis.call('ZCARD',KEYS[1])>=3 or redis.call('ZCARD',KEYS[2])>=300) then return 0 end
            for _,k in ipairs(KEYS) do redis.call('ZADD',k,ARGV[2],ARGV[3]);redis.call('EXPIRE',k,40) end
            return 1
            """, Long.class);
    private static final class Connection {
        final WebSocketSession socket;
        final long openedAt;
        final String leaseId;
        MemberChatTickets.Identity identity;
        long validatedAt;
        Connection(WebSocketSession socket, long now, String leaseId) {
            this.socket = socket; openedAt = now; validatedAt = now; this.leaseId = leaseId;
        }
    }
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final ObjectProvider<MemberChatService> service;
    private final Clock clock;
    private final String prefix;
    private final String instance = UUID.randomUUID().toString();

    public MemberChatSessions(StringRedisTemplate redis, ObjectProvider<MemberChatService> service,
                              Clock clock, @Value("${pawbridge.chat.namespace}") String namespace) {
        this.redis = redis;
        this.service = service;
        this.clock = clock;
        prefix = "pawbridge:" + namespace + ":chat:";
    }

    public synchronized boolean open(WebSocketSession socket) {
        if (connections.size() >= 300) return false;
        connections.put(socket.getId(), new Connection(socket, clock.millis(), instance + ":" + socket.getId()));
        return true;
    }

    public void authenticate(String sessionId, MemberChatTickets.Identity identity) {
        Connection connection = connections.get(sessionId);
        if (connection == null) {
            throw MemberChatService.error(HttpStatus.UNAUTHORIZED, "연결을 다시 시작하세요.");
        }
        synchronized (connection) {
            if (connections.get(sessionId) != connection || !connection.socket.isOpen() || connection.identity != null) {
                throw MemberChatService.error(HttpStatus.UNAUTHORIZED, "연결을 다시 시작하세요.");
            }
            if (!lease(connection, identity)) {
                throw MemberChatService.error(HttpStatus.TOO_MANY_REQUESTS, "채팅 연결 한도에 도달했습니다.");
            }
            connection.identity = identity;
        }
    }

    public void requireCurrent(String sessionId) {
        Connection connection = connections.get(sessionId);
        if (connection == null) throw MemberChatService.error(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다.");
        synchronized (connection) {
            if (connections.get(sessionId) == connection && connection.identity != null
                    && connection.identity.expiresAt() > clock.millis()) return;
        }
        close(sessionId);
        throw MemberChatService.error(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다.");
    }

    private boolean lease(Connection connection, MemberChatTickets.Identity identity) {
        Long result = redis.execute(LEASE, List.of(prefix + "connections:" + identity.memberId(), prefix + "connections"),
                Long.toString(clock.millis()), Long.toString(clock.millis() + 30_000), connection.leaseId);
        return Long.valueOf(1).equals(result);
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void sweep() {
        for (var entry : connections.entrySet()) {
            var connection = entry.getValue();
            try {
                boolean expired;
                synchronized (connection) {
                    if (connections.get(entry.getKey()) != connection) continue;
                    var identity = connection.identity;
                    expired = identity == null ? clock.millis() - connection.openedAt > 8_000
                            : identity.expiresAt() <= clock.millis() || !lease(connection, identity);
                    if (!expired && identity != null && clock.millis() - connection.validatedAt >= 30_000) {
                        service.getObject().active(identity.memberId());
                        connection.validatedAt = clock.millis();
                    }
                }
                if (expired) close(entry.getKey());
            } catch (RuntimeException unavailable) {
                close(entry.getKey()); // Fail closed; never print Redis payload or member data.
            }
        }
    }

    public void closeMember(long member) {
        for (var entry : connections.entrySet()) {
            var connection = entry.getValue();
            boolean matches;
            synchronized (connection) { matches = connection.identity != null && connection.identity.memberId() == member; }
            if (matches) close(entry.getKey());
        }
    }

    public void close(String sessionId) {
        Connection connection = connections.get(sessionId);
        if (connection == null) return;
        try { connection.socket.close(CloseStatus.POLICY_VIOLATION); }
        catch (IOException ignored) { /* Transport callback or lease expiry releases the slot. */ }
        finally { removed(sessionId); }
    }

    public void removed(String sessionId) {
        Connection connection = connections.remove(sessionId);
        if (connection == null) return;
        synchronized (connection) {
            if (connection.identity != null) {
                try {
                    redis.opsForZSet().remove(prefix + "connections:" + connection.identity.memberId(), connection.leaseId);
                    redis.opsForZSet().remove(prefix + "connections", connection.leaseId);
                } catch (RuntimeException unavailable) { /* Bounded 30 second lease expires independently. */ }
            }
        }
    }
}
