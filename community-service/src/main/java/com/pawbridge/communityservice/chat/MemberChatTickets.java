package com.pawbridge.communityservice.chat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "pawbridge.chat.enabled", havingValue = "true")
public class MemberChatTickets {
    public record Ticket(String ticket, long expiresAt) {}
    public record Identity(long memberId, long expiresAt) implements java.security.Principal {
        @Override public String getName() { return Long.toString(memberId); }
    }
    private static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>(
            "local v=redis.call('GET',KEYS[1]); if v then redis.call('DEL',KEYS[1]) end; return v", String.class);
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String prefix;

    public MemberChatTickets(StringRedisTemplate redis, Clock clock,
                             @Value("${pawbridge.chat.namespace}") String namespace) {
        if (!namespace.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Chat namespace is required");
        this.redis = redis;
        this.clock = clock;
        prefix = "pawbridge:" + namespace + ":chat:";
    }

    public Ticket issue(long member, long tokenExpiry) {
        long now = clock.millis();
        if (tokenExpiry <= now) throw MemberChatService.error(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다.");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = HexFormat.of().formatHex(bytes);
        long expiresAt = Math.min(tokenExpiry, now + 300_000);
        // A short-lived per-member issuance guard also bounds abandoned tickets.
        Boolean accepted = redis.opsForValue().setIfAbsent(prefix + "ticket-rate:" + member, "1", Duration.ofSeconds(2));
        if (!Boolean.TRUE.equals(accepted)) {
            throw MemberChatService.error(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 연결을 다시 시도하세요.");
        }
        redis.opsForValue().set(prefix + "ticket:" + ticket, member + ":" + expiresAt,
                Duration.ofMillis(Math.min(30_000, tokenExpiry - now)));
        return new Ticket(ticket, expiresAt);
    }

    public Identity consume(String ticket) {
        if (ticket == null || !ticket.matches("[0-9a-f]{64}")) {
            throw MemberChatService.error(HttpStatus.UNAUTHORIZED, "연결 인증이 필요합니다.");
        }
        String value = redis.execute(CONSUME, List.of(prefix + "ticket:" + ticket));
        if (value == null) throw MemberChatService.error(HttpStatus.UNAUTHORIZED, "연결 인증이 만료되었습니다.");
        String[] parts = value.split(":");
        Identity identity = new Identity(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        if (identity.expiresAt() <= clock.millis()) throw MemberChatService.error(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다.");
        return identity;
    }
}
