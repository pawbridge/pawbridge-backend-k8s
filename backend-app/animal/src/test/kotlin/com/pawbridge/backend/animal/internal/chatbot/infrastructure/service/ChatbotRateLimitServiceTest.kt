package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotRateLimitExceededException
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import java.time.Duration
import java.util.function.Supplier

class ChatbotRateLimitServiceTest {
    @Suppress("UNCHECKED_CAST")
    private val manager: ProxyManager<String> = mock(ProxyManager::class.java) as ProxyManager<String>
    private val buckets: List<BucketProxy> = (0 until 5).map { mock(BucketProxy::class.java) }
    private val quotas = ChatbotRateLimitService(manager, 5, 20, 20, 100, 300)

    @BeforeEach
    fun setup() {
        for (i in 0 until 5) {
            `when`(manager.getProxy(eq(KEYS[i]), any())).thenReturn(buckets[i])
            `when`(buckets[i].tryConsume(1)).thenReturn(true)
        }
        clearInvocations(manager)
    }

    @Test
    fun given__five_successful_redis_buckets__when__checking__then__preserve_keys_limits_greedy_intervals_and_one_token_per_bucket() {
        quotas.check("browser", "hash")

        @Suppress("UNCHECKED_CAST")
        val config = ArgumentCaptor.forClass(Supplier::class.java) as ArgumentCaptor<Supplier<BucketConfiguration>>
        val order = inOrder(manager)
        for (key in KEYS) {
            order.verify(manager).getProxy(eq(key), config.capture())
        }
        verifyNoMoreInteractions(manager)
        val limits = longArrayOf(5, 20, 20, 100, 300)
        val periods = longArrayOf(
            Duration.ofMinutes(10).toNanos(),
            Duration.ofDays(1).toNanos(),
            Duration.ofMinutes(10).toNanos(),
            Duration.ofDays(1).toNanos(),
            Duration.ofDays(1).toNanos(),
        )
        for (i in 0 until 5) {
            val bandwidth = config.allValues[i].get().bandwidths[0]
            assertEquals(limits[i], bandwidth.capacity)
            assertEquals(limits[i], bandwidth.refillTokens)
            assertEquals(periods[i], bandwidth.refillPeriodNanos)
            assertFalse(bandwidth.isRefillIntervally)
            verify(buckets[i]).tryConsume(1)
        }
        verifyNoMoreInteractions(*buckets.toTypedArray())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4])
    fun given__a_bucket_rejection__when__checking__then__short_circuit_later_buckets_without_refunding_earlier_consumption(
        rejected: Int,
    ) {
        `when`(buckets[rejected].tryConsume(1)).thenReturn(false)

        assertEquals(
            "챗봇 사용량이 많습니다. 잠시 후 다시 시도해주세요.",
            assertThrows(ChatbotRateLimitExceededException::class.java) { quotas.check("browser", "hash") }.message,
        )
        val order = inOrder(manager)
        for (i in 0..rejected) {
            order.verify(manager).getProxy(eq(KEYS[i]), any())
        }
        for (i in rejected + 1 until 5) {
            verify(manager, never()).getProxy(eq(KEYS[i]), any())
        }
        verifyNoMoreInteractions(manager)
        for (i in 0..rejected) {
            verify(buckets[i]).tryConsume(1)
        }
        for (i in rejected + 1 until 5) {
            verifyNoInteractions(buckets[i])
        }
        verifyNoMoreInteractions(*buckets.toTypedArray())
    }

    companion object {
        private val KEYS: List<String> = listOf(
            "chatbot:bucket:anon:browser:10m",
            "chatbot:bucket:anon:browser:daily",
            "chatbot:bucket:ip:hash:10m",
            "chatbot:bucket:ip:hash:daily",
            "chatbot:bucket:global:daily",
        )
    }
}
