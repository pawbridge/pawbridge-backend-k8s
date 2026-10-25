package com.pawbridge.backend.community.internal.chat.infrastructure

import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException
import java.security.Principal
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.regex.Pattern
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus

open class MemberChatTickets(
    private val redis: StringRedisTemplate,
    private val clock: Clock,
    @Value("\${pawbridge.chat.namespace}") namespace: String,
) {

    @JvmRecord
    data class Ticket(
        val ticket: String?,
        val expiresAt: Long,
    )

    @JvmRecord
    data class Identity(
        val memberId: Long,
        val expiresAt: Long,
    ) : Principal {
        override fun getName(): String = memberId.toString()
    }

    private val random = SecureRandom()
    private val prefix: String

    init {
        if (!NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw IllegalArgumentException("Chat namespace is required")
        }
        prefix = "pawbridge:" + namespace + ":chat:"
    }

    open fun issue(member: Long, tokenExpiry: Long): Ticket {
        val now = clock.millis()
        if (tokenExpiry <= now) {
            throw MemberChatRejectedException(HttpStatus.UNAUTHORIZED.value(), "로그인이 만료되었습니다.")
        }
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val ticket = HexFormat.of().formatHex(bytes)
        val expiresAt = Math.min(tokenExpiry, now + 300_000)
        // A short-lived per-member issuance guard also bounds abandoned tickets.
        val accepted = redis.opsForValue().setIfAbsent(prefix + "ticket-rate:" + member, "1", Duration.ofSeconds(2))
        if (accepted != true) {
            throw MemberChatRejectedException(HttpStatus.TOO_MANY_REQUESTS.value(), "잠시 후 연결을 다시 시도하세요.")
        }
        redis.opsForValue().set(
            prefix + "ticket:" + ticket,
            member.toString() + ":" + expiresAt,
            Duration.ofMillis(Math.min(30_000L, tokenExpiry - now)),
        )
        return Ticket(ticket, expiresAt)
    }

    open fun consume(ticket: String?): Identity {
        if (ticket == null || !TICKET_PATTERN.matcher(ticket).matches()) {
            throw MemberChatRejectedException(HttpStatus.UNAUTHORIZED.value(), "연결 인증이 필요합니다.")
        }
        val value: String? = redis.execute(CONSUME, listOf(prefix + "ticket:" + ticket))
        if (value == null) {
            throw MemberChatRejectedException(HttpStatus.UNAUTHORIZED.value(), "연결 인증이 만료되었습니다.")
        }
        // Pattern.split matches Java String.split (drops trailing empty strings); Kotlin split keeps them.
        val parts = COLON.split(value)
        val identity = Identity(java.lang.Long.parseLong(parts[0]), java.lang.Long.parseLong(parts[1]))
        if (identity.expiresAt <= clock.millis()) {
            throw MemberChatRejectedException(HttpStatus.UNAUTHORIZED.value(), "로그인이 만료되었습니다.")
        }
        return identity
    }

    private companion object {
        private val NAMESPACE_PATTERN = Pattern.compile("[a-z0-9-]{1,40}")
        private val TICKET_PATTERN = Pattern.compile("[0-9a-f]{64}")
        private val COLON = Pattern.compile(":")
        private val CONSUME = DefaultRedisScript(
            "local v=redis.call('GET',KEYS[1]); if v then redis.call('DEL',KEYS[1]) end; return v",
            String::class.java,
        )
    }
}
