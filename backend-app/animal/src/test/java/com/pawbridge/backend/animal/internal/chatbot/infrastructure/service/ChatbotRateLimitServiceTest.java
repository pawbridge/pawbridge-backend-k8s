package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.pawbridge.backend.animal.internal.chatbot.infrastructure.exception.ChatbotRateLimitExceededException;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ChatbotRateLimitServiceTest {
    private static final List<String> KEYS=List.of("chatbot:bucket:anon:browser:10m","chatbot:bucket:anon:browser:daily",
            "chatbot:bucket:ip:hash:10m","chatbot:bucket:ip:hash:daily","chatbot:bucket:global:daily");
    @SuppressWarnings("unchecked") private final ProxyManager<String> manager=mock(ProxyManager.class);
    private final List<BucketProxy> buckets=java.util.stream.IntStream.range(0,5).mapToObj(i->mock(BucketProxy.class)).toList();
    private final ChatbotRateLimitService quotas=new ChatbotRateLimitService(manager,5,20,20,100,300);

    @BeforeEach void setup() {
        for(int i=0;i<5;i++){
            when(manager.getProxy(eq(KEYS.get(i)),any())).thenReturn(buckets.get(i));
            when(buckets.get(i).tryConsume(1)).thenReturn(true);
        }
        clearInvocations(manager);
    }

    @Test void given__five_successful_redis_buckets__when__checking__then__preserve_keys_limits_greedy_intervals_and_one_token_per_bucket() {
        quotas.check("browser","hash");
        @SuppressWarnings("unchecked") ArgumentCaptor<Supplier<BucketConfiguration>> config=ArgumentCaptor.forClass(Supplier.class);
        var order=inOrder(manager);for(String key:KEYS)order.verify(manager).getProxy(eq(key),config.capture());verifyNoMoreInteractions(manager);
        long[] limits={5,20,20,100,300};long[] periods={Duration.ofMinutes(10).toNanos(),Duration.ofDays(1).toNanos(),Duration.ofMinutes(10).toNanos(),Duration.ofDays(1).toNanos(),Duration.ofDays(1).toNanos()};
        for(int i=0;i<5;i++){
            var bandwidth=config.getAllValues().get(i).get().getBandwidths()[0];assertEquals(limits[i],bandwidth.getCapacity());
            assertEquals(limits[i],bandwidth.getRefillTokens());assertEquals(periods[i],bandwidth.getRefillPeriodNanos());assertFalse(bandwidth.isRefillIntervally());
            verify(buckets.get(i)).tryConsume(1);
        }
        verifyNoMoreInteractions(buckets.toArray());
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4})
    void given__a_bucket_rejection__when__checking__then__short_circuit_later_buckets_without_refunding_earlier_consumption(int rejected) {
        when(buckets.get(rejected).tryConsume(1)).thenReturn(false);
        assertEquals("챗봇 사용량이 많습니다. 잠시 후 다시 시도해주세요.",assertThrows(ChatbotRateLimitExceededException.class,()->quotas.check("browser","hash")).getMessage());
        var order=inOrder(manager);for(int i=0;i<=rejected;i++)order.verify(manager).getProxy(eq(KEYS.get(i)),any());
        for(int i=rejected+1;i<5;i++)verify(manager,never()).getProxy(eq(KEYS.get(i)),any());verifyNoMoreInteractions(manager);
        for(int i=0;i<=rejected;i++)verify(buckets.get(i)).tryConsume(1);
        for(int i=rejected+1;i<5;i++)verifyNoInteractions(buckets.get(i));verifyNoMoreInteractions(buckets.toArray());
    }
}
