package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service

import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotRateLimitExceededException
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.function.Supplier

class ChatbotRateLimitServiceTest {
    private val manager = mockk<ProxyManager<String>>()
    private val buckets: List<BucketProxy> = (0 until 5).map { mockk<BucketProxy>() }
    private val quotas = ChatbotRateLimitService(manager, 5, 20, 20, 100, 300)

    @Test
    fun `Redis 버킷 다섯 개가 모두 성공할 때__확인하면__키와 한도와 탐욕 간격과 버킷당 토큰 하나를 유지한다`() {
        // given
        val configs = KEYS.map { slot<Supplier<BucketConfiguration>>() }

        for (i in 0 until 5) {
            every { manager.getProxy(KEYS[i], any()) } returns buckets[i]
            every { buckets[i].tryConsume(1) } returns true
        }

        // when
        quotas.check("browser", "hash")

        // then
        verifyOrder {
            for (i in 0 until 5) {
                manager.getProxy(KEYS[i], capture(configs[i]))
            }
        }
        confirmVerified(manager)
        val limits = longArrayOf(5, 20, 20, 100, 300)
        val periods = longArrayOf(
            Duration.ofMinutes(10).toNanos(),
            Duration.ofDays(1).toNanos(),
            Duration.ofMinutes(10).toNanos(),
            Duration.ofDays(1).toNanos(),
            Duration.ofDays(1).toNanos(),
        )
        for (i in 0 until 5) {
            val bandwidth = configs[i].captured.get().bandwidths[0]
            assertEquals(limits[i], bandwidth.capacity)
            assertEquals(limits[i], bandwidth.refillTokens)
            assertEquals(periods[i], bandwidth.refillPeriodNanos)
            assertFalse(bandwidth.isRefillIntervally)
            verify(exactly = 1) { buckets[i].tryConsume(1) }
        }
        confirmVerified(*buckets.toTypedArray())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4])
    fun `버킷 하나가 거절할 때__확인하면__이후 버킷은 건너뛰고 앞서 소비한 토큰은 환불하지 않는다`(
        rejected: Int,
    ) {
        // given
        for (i in 0 until 5) {
            every { manager.getProxy(KEYS[i], any()) } returns buckets[i]
            every { buckets[i].tryConsume(1) } returns true
        }
        every { buckets[rejected].tryConsume(1) } returns false

        // when
        val thrown = assertThrows(ChatbotRateLimitExceededException::class.java) { quotas.check("browser", "hash") }

        // then
        assertEquals("챗봇 사용량이 많습니다. 잠시 후 다시 시도해주세요.", thrown.message)
        verifyOrder {
            for (i in 0..rejected) {
                manager.getProxy(KEYS[i], any())
            }
        }
        for (i in rejected + 1 until 5) {
            verify(exactly = 0) { manager.getProxy(KEYS[i], any()) }
        }
        confirmVerified(manager)
        for (i in 0..rejected) {
            verify(exactly = 1) { buckets[i].tryConsume(1) }
        }
        confirmVerified(*buckets.toTypedArray())
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
