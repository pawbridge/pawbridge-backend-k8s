package com.pawbridge.backend.user.internal.emailverification.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisVerificationStoreTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisVerificationStore store = new RedisVerificationStore(redis);

    @Test
    void given__limit_reached_marker__when__counting_sends__then__false_with_original_script_and_arguments() {
        when(redis.execute(any(RedisScript.class), eq(List.of("email:send:count:a@b.c")), eq("5"), eq("300")))
                .thenReturn(-1L, 1L, null);

        assertFalse(store.tryIncrementSendCount("email:send:count:a@b.c", 5, 300));
        assertTrue(store.tryIncrementSendCount("email:send:count:a@b.c", 5, 300));
        assertTrue(store.tryIncrementSendCount("email:send:count:a@b.c", 5, 300));

        var script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redis, org.mockito.Mockito.times(3)).execute(script.capture(), eq(List.of("email:send:count:a@b.c")),
                eq("5"), eq("300"));
        String source = script.getValue().getScriptAsString();
        assertTrue(source.contains("if count and tonumber(count) >= tonumber(ARGV[1]) then"));
        assertTrue(source.contains("return -1"));
        assertTrue(source.contains("redis.call('INCR', KEYS[1])"));
        assertTrue(source.contains("redis.call('EXPIRE', KEYS[1], ARGV[2])"));
        assertEquals(Long.class, script.getValue().getResultType());
    }

    @Test
    void given__keys__when__reading_writing_deleting_and_incrementing__then__plain_string_operations_with_second_ttl() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("k")).thenReturn("v");

        assertEquals("v", store.get("k"));
        store.put("k", "v", 3600);
        store.delete("k");
        store.incrementWithExpire("attempts", 300);

        verify(values).set("k", "v", 3600L, TimeUnit.SECONDS);
        verify(redis).delete("k");
        var script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redis).execute(script.capture(), eq(List.of("attempts")), eq("300"));
        assertTrue(script.getValue().getScriptAsString().contains("redis.call('EXPIRE', KEYS[1], ARGV[1])"));
    }
}
