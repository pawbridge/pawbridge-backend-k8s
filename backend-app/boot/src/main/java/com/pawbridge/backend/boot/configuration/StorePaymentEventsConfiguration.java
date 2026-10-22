package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.api.StorePaymentEvents;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.infrastructure.JpaStockAdjustments;
import com.pawbridge.backend.store.internal.directorder.infrastructure.ProductSnapshots;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository;
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository;
import com.pawbridge.backend.store.internal.payment.infrastructure.JpaStorePaymentEvents;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Default-off original Store payment result business; no message transport is registered here. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.store-payment-events",name="enabled",havingValue="true")
@Import({StoreOrderSupportConfiguration.class,StorePaymentTransportConfiguration.class})
public class StorePaymentEventsConfiguration {
    @Bean Clock storePaymentEventClock() { return Clock.systemDefaultZone(); }

    @Bean StorePaymentEvents.StockRestorer storePaymentEventStocks(
            @Qualifier("storeDirectOrderEntityManager") EntityManager writer,
            @Qualifier("storeDirectOrderSearchJdbc") NamedParameterJdbcTemplate search,
            @Qualifier("storeDirectOrderSearchTerms") KoreanSearchTerms terms,
            ObjectMapper mapper) {
        var repositories=new JpaRepositoryFactory(writer);
        var snapshots=new ProductSnapshots(writer,search,terms,mapper);
        return new SharedWriterStockRestorer(new JpaStockAdjustments(repositories.getRepository(ProductRepository.class),
                repositories.getRepository(ProductSKURepository.class),snapshots));
    }

    @Bean StorePaymentEvents.Ranking storePaymentEventRanking(
            @Qualifier("storePaymentRankingRedis") ObjectProvider<StringRedisTemplate> sharedRedis) {
        // Reuse an explicitly named existing/shared resource; do not allocate a second Redis client here.
        return (key,productId,quantity) -> {
            StringRedisTemplate redis=sharedRedis.getIfAvailable();
            if(redis==null) throw new IllegalStateException("Explicit Store payment ranking Redis resource is required");
            redis.opsForZSet().incrementScore(key,productId,quantity);
        };
    }

    @Bean StorePaymentEvents storePaymentEvents(
            @Qualifier("storeDirectOrderEntityManager") EntityManager writer,
            @Qualifier("storeDirectOrderWrites") TransactionTemplate writes,
            @Qualifier("storePaymentEventStocks") StorePaymentEvents.StockRestorer stocks,
            @Qualifier("storePaymentEventRanking") StorePaymentEvents.Ranking ranking,
            ObjectMapper mapper,@Qualifier("storePaymentEventClock") Clock clock) {
        return new JpaStorePaymentEvents(writer,writes,stocks,ranking,mapper,clock);
    }

    static final class SharedWriterStockRestorer implements StorePaymentEvents.StockRestorer {
        final JpaStockAdjustments delegate;
        SharedWriterStockRestorer(JpaStockAdjustments delegate) { this.delegate=delegate; }
        @Override public void increaseStocks(Map<Long,Integer> quantities) { delegate.increaseStocks(quantities); }
    }
}
