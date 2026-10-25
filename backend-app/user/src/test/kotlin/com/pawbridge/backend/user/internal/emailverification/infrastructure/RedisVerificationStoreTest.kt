package com.pawbridge.backend.user.internal.emailverification.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript
import java.util.concurrent.TimeUnit

class RedisVerificationStoreTest {
    private val redis: StringRedisTemplate = mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST")
    private val values: ValueOperations<String, String> = mock(ValueOperations::class.java) as ValueOperations<String, String>
    private val store = RedisVerificationStore(redis)

    @Test
    fun given__limit_reached_marker__when__counting_sends__then__false_with_original_script_and_arguments() {
        `when`(redis.execute(any<RedisScript<Long>>(), eq(listOf("email:send:count:a@b.c")), eq("5"), eq("300")))
            .thenReturn(-1L, 1L, null)

        assertFalse(store.tryIncrementSendCount("email:send:count:a@b.c", 5, 300))
        assertTrue(store.tryIncrementSendCount("email:send:count:a@b.c", 5, 300))
        assertTrue(store.tryIncrementSendCount("email:send:count:a@b.c", 5, 300))

        @Suppress("UNCHECKED_CAST")
        val script = ArgumentCaptor.forClass(RedisScript::class.java) as ArgumentCaptor<RedisScript<Long>>
        verify(redis, times(3)).execute(
            script.capture(),
            eq(listOf("email:send:count:a@b.c")),
            eq("5"),
            eq("300"),
        )
        val source = script.value.scriptAsString
        assertTrue(source.contains("if count and tonumber(count) >= tonumber(ARGV[1]) then"))
        assertTrue(source.contains("return -1"))
        assertTrue(source.contains("redis.call('INCR', KEYS[1])"))
        assertTrue(source.contains("redis.call('EXPIRE', KEYS[1], ARGV[2])"))
        assertEquals(Long::class.javaObjectType, script.value.resultType)
    }

    @Test
    fun given__keys__when__reading_writing_deleting_and_incrementing__then__plain_string_operations_with_second_ttl() {
        `when`(redis.opsForValue()).thenReturn(values)
        `when`(values.get("k")).thenReturn("v")

        assertEquals("v", store.get("k"))
        store.put("k", "v", 3600)
        store.delete("k")
        store.incrementWithExpire("attempts", 300)

        verify(values).set("k", "v", 3600L, TimeUnit.SECONDS)
        verify(redis).delete("k")
        @Suppress("UNCHECKED_CAST")
        val script = ArgumentCaptor.forClass(RedisScript::class.java) as ArgumentCaptor<RedisScript<Long>>
        verify(redis).execute(script.capture(), eq(listOf("attempts")), eq("300"))
        assertTrue(script.value.scriptAsString.contains("redis.call('EXPIRE', KEYS[1], ARGV[1])"))
    }
}
