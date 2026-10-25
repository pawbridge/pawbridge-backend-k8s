package com.pawbridge.backend.community.internal.chat.web

import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatRejectedException
import com.pawbridge.backend.community.internal.chat.infrastructure.MemberChatTickets
import java.io.IOException
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession

open class MemberChatSessions(
    private val redis: StringRedisTemplate,
    private val service: ObjectProvider<MemberChat>,
    private val clock: Clock,
    @Value("\${pawbridge.chat.namespace}") namespace: String,
) : DisposableBean {

    private class Connection(
        val socket: WebSocketSession,
        now: Long,
        val leaseId: String,
    ) {
        val openedAt: Long = now
        var identity: MemberChatTickets.Identity? = null
        var validatedAt: Long = now
    }

    private val connections: MutableMap<String, Connection> = ConcurrentHashMap()
    private val prefix: String = "pawbridge:" + namespace + ":chat:"
    private val instance: String = UUID.randomUUID().toString()

    @Synchronized
    open fun open(socket: WebSocketSession): Boolean {
        if (connections.size >= 300) {
            return false
        }
        connections[socket.id] = Connection(socket, clock.millis(), instance + ":" + socket.id)
        return true
    }

    open fun authenticate(sessionId: String, identity: MemberChatTickets.Identity) {
        val connection = connections[sessionId]
        if (connection == null) {
            throw MemberChatRejectedException(HttpStatus.UNAUTHORIZED.value(), "연결을 다시 시작하세요.")
        }
        synchronized(connection) {
            if (connections[sessionId] !== connection || !connection.socket.isOpen || connection.identity != null) {
                throw MemberChatRejectedException(HttpStatus.UNAUTHORIZED.value(), "연결을 다시 시작하세요.")
            }
            if (!lease(connection, identity)) {
                throw MemberChatRejectedException(HttpStatus.TOO_MANY_REQUESTS.value(), "채팅 연결 한도에 도달했습니다.")
            }
            connection.identity = identity
        }
    }

    open fun requireCurrent(sessionId: String) {
        val connection = connections[sessionId]
        if (connection == null) {
            throw MemberChatRejectedException(HttpStatus.UNAUTHORIZED.value(), "로그인이 만료되었습니다.")
        }
        val current = synchronized(connection) {
            val identity = connection.identity
            connections[sessionId] === connection && identity != null && identity.expiresAt > clock.millis()
        }
        if (current) {
            return
        }
        close(sessionId)
        throw MemberChatRejectedException(HttpStatus.UNAUTHORIZED.value(), "로그인이 만료되었습니다.")
    }

    private fun lease(connection: Connection, identity: MemberChatTickets.Identity): Boolean {
        val result: Long? = redis.execute(
            LEASE,
            listOf(prefix + "connections:" + identity.memberId, prefix + "connections"),
            clock.millis().toString(),
            (clock.millis() + 30_000).toString(),
            connection.leaseId,
        )
        return result == 1L
    }

    @Scheduled(scheduler = "memberChatMaintenanceScheduler", fixedDelay = 5_000, initialDelay = 5_000)
    open fun sweep() {
        for (entry in connections.entries) {
            val connection = entry.value
            try {
                // Null means the connection was replaced meanwhile and is skipped (Java `continue`).
                val expired: Boolean? = synchronized(connection) {
                    if (connections[entry.key] !== connection) {
                        return@synchronized null
                    }
                    val identity = connection.identity
                    val expired = if (identity == null) {
                        clock.millis() - connection.openedAt > 8_000
                    } else {
                        identity.expiresAt <= clock.millis() || !lease(connection, identity)
                    }
                    if (!expired && identity != null && clock.millis() - connection.validatedAt >= 30_000) {
                        service.getObject().active(identity.memberId)
                        connection.validatedAt = clock.millis()
                    }
                    expired
                }
                if (expired == true) {
                    close(entry.key)
                }
            } catch (unavailable: RuntimeException) {
                close(entry.key) // Fail closed; never print Redis payload or member data.
            }
        }
    }

    open fun closeMember(member: Long) {
        for (entry in connections.entries) {
            val connection = entry.value
            val matches = synchronized(connection) {
                val identity = connection.identity
                identity != null && identity.memberId == member
            }
            if (matches) {
                close(entry.key)
            }
        }
    }

    open fun close(sessionId: String) {
        val connection = connections[sessionId]
        if (connection == null) {
            return
        }
        try {
            connection.socket.close(CloseStatus.POLICY_VIOLATION)
        } catch (ignored: IOException) {
            // Transport callback or lease expiry releases the slot.
        } finally {
            removed(sessionId)
        }
    }

    open fun removed(sessionId: String) {
        val connection = connections.remove(sessionId)
        if (connection == null) {
            return
        }
        synchronized(connection) {
            val identity = connection.identity
            if (identity != null) {
                try {
                    redis.opsForZSet().remove(prefix + "connections:" + identity.memberId, connection.leaseId)
                    redis.opsForZSet().remove(prefix + "connections", connection.leaseId)
                } catch (unavailable: RuntimeException) {
                    // Bounded 30 second lease expires independently.
                }
            }
        }
    }

    override fun destroy() {
        val owned = java.util.List.copyOf(connections.values)
        // Detach before transport callbacks: shutdown must not perform up to 600 sequential Redis calls.
        // The original bounded 30-second leases expire independently if Redis is unavailable.
        connections.clear()
        for (connection in owned) {
            try {
                connection.socket.close(CloseStatus.POLICY_VIOLATION)
            } catch (ignored: IOException) {
                // The server transport also owns socket shutdown.
            }
        }
    }

    private companion object {
        private val LEASE = DefaultRedisScript(
            """
            for _,k in ipairs(KEYS) do redis.call('ZREMRANGEBYSCORE',k,'-inf',ARGV[1]) end
            local known=redis.call('ZSCORE',KEYS[1],ARGV[3])
            if not known and (redis.call('ZCARD',KEYS[1])>=3 or redis.call('ZCARD',KEYS[2])>=300) then return 0 end
            for _,k in ipairs(KEYS) do redis.call('ZADD',k,ARGV[2],ARGV[3]);redis.call('EXPIRE',k,40) end
            return 1
            """.trimIndent() + "\n",
            Long::class.javaObjectType,
        )
    }
}
