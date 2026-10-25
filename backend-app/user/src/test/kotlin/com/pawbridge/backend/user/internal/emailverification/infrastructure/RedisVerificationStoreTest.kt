package com.pawbridge.backend.user.internal.emailverification.infrastructure

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript
import java.util.concurrent.TimeUnit

class RedisVerificationStoreTest {
    private val redis = mockk<StringRedisTemplate>()
    private val values = mockk<ValueOperations<String, String>>()
    private val store = RedisVerificationStore(redis)

    @Test
    fun `발송 한도 도달 표식이 있을 때__발송 횟수를 세면__거짓을 돌려주고 원래의 스크립트와 인자를 쓴다`() {
        // given
        every<Long?> {
            redis.execute(any<RedisScript<Long?>>(), any<List<String>>(), *anyVararg<Any>())
        } returnsMany listOf(-1L, 1L, null)

        // when
        val limitReached = store.tryIncrementSendCount("email:send:count:a@b.c", 5, 300)
        val firstSend = store.tryIncrementSendCount("email:send:count:a@b.c", 5, 300)
        val emptyResult = store.tryIncrementSendCount("email:send:count:a@b.c", 5, 300)

        // then
        assertFalse(limitReached)
        assertTrue(firstSend)
        assertTrue(emptyResult)
        val scripts = mutableListOf<RedisScript<Long>>()
        verify(exactly = 3) {
            redis.execute(
                capture(scripts),
                listOf("email:send:count:a@b.c"),
                "5",
                "300",
            )
        }
        val script = scripts.last()
        val source = script.scriptAsString
        assertTrue(source.contains("if count and tonumber(count) >= tonumber(ARGV[1]) then"))
        assertTrue(source.contains("return -1"))
        assertTrue(source.contains("redis.call('INCR', KEYS[1])"))
        assertTrue(source.contains("redis.call('EXPIRE', KEYS[1], ARGV[2])"))
        assertEquals(Long::class.javaObjectType, script.resultType)
    }

    @Test
    fun `키가 있을 때__읽고 쓰고 지우고 증가시키면__초 단위 만료를 가진 문자열 연산을 쓴다`() {
        // given
        every { redis.opsForValue() } returns values
        every { values.get(any<String>()) } returns "v"
        justRun { values.set(any<String>(), any<String>(), any<Long>(), any<TimeUnit>()) }
        every { redis.delete(any<String>()) } returns true
        every { redis.execute(any<RedisScript<Long>>(), any<List<String>>(), *anyVararg<Any>()) } returns 1L

        // when
        val read = store.get("k")
        store.put("k", "v", 3600)
        store.delete("k")
        store.incrementWithExpire("attempts", 300)

        // then
        assertEquals("v", read)
        verify(exactly = 1) { values.set("k", "v", 3600L, TimeUnit.SECONDS) }
        verify(exactly = 1) { redis.delete("k") }
        val script = slot<RedisScript<Long>>()
        verify(exactly = 1) { redis.execute(capture(script), listOf("attempts"), "300") }
        assertTrue(script.captured.scriptAsString.contains("redis.call('EXPIRE', KEYS[1], ARGV[1])"))
    }
}
