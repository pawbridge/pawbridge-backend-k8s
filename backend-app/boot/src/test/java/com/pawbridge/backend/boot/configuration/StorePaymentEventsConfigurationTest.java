package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.api.StorePaymentEvents;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Real writer-unit wiring and dormant Redis resource contracts; no DB/Redis connection is requested. */
class StorePaymentEventsConfigurationTest {
    @ParameterizedTest @ValueSource(strings={"absent","false","yes","on"})
    void given__payment_events_not_explicitly_enabled__when__assembling__then__create_no_business_writer_or_redis_resources(String flag) {
        var runner=runner();if(!flag.equals("absent"))runner=runner.withPropertyValues("pawbridge.store-payment-events.enabled="+flag);
        runner.run(context->assertThat(context).hasNotFailed().doesNotHaveBean(StorePaymentEvents.class)
                .doesNotHaveBean(EntityManagerFactory.class).doesNotHaveBean(DataSource.class)
                .doesNotHaveBean(RedisConnectionFactory.class).doesNotHaveBean(StringRedisTemplate.class));
    }

    @Test void given__explicit_business_only__when__assembling__then__reuse_one_store_writer_and_actual_stock_projection_without_starting_clients() {
        DataSource writer=mock(DataSource.class);
        inputs(writer).run(context->{
            assertThat(context).hasNotFailed().hasSingleBean(StorePaymentEvents.class).hasSingleBean(EntityManagerFactory.class)
                    .hasSingleBean(DataSource.class).doesNotHaveBean(RedisConnectionFactory.class).hasSingleBean(StringRedisTemplate.class);
            var service=context.getBean(StorePaymentEvents.class);
            var shared=context.getBean("storeDirectOrderEntityManager",EntityManager.class);
            var factory=context.getBean("storeDirectOrderEntityManagerFactory",EntityManagerFactory.class);
            var writes=context.getBean("storeDirectOrderWrites",TransactionTemplate.class);
            assertThat(field(service,"writes")).isSameAs(writes);
            assertThat(writes.getTransactionManager()).isSameAs(context.getBean("storeDirectOrderJpaTransactionManager"));
            assertThat(context.getBean("storeDirectOrderJpaTransactionManager",JpaTransactionManager.class).getDataSource()).isSameAs(writer);
            assertThat(shared.getEntityManagerFactory()).isSameAs(factory);
            assertThat(repositoryEntityManager(field(service,"orders")).getEntityManagerFactory()).isSameAs(factory);
            var mandatory=(TransactionTemplate)field(service,"mandatoryWrites");
            assertThat(mandatory.getTransactionManager()).isSameAs(context.getBean("storeDirectOrderJpaTransactionManager"));
            assertThat(mandatory.getPropagationBehavior()).isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_MANDATORY);
            assertThat(mandatory.isReadOnly()).isFalse();
            var bridge=context.getBean("storePaymentEventStocks",StorePaymentEvents.StockRestorer.class);
            var stock=field(bridge,"delegate");var snapshots=field(stock,"snapshots");var documents=field(snapshots,"documents");
            assertThat(repositoryEntityManager(field(stock,"products")).getEntityManagerFactory()).isSameAs(factory);
            assertThat(repositoryEntityManager(field(stock,"skus")).getEntityManagerFactory()).isSameAs(factory);
            assertThat(field(documents,"jdbc")).isSameAs(context.getBean("storeDirectOrderSearchJdbc"));
            assertThat(((NamedParameterJdbcTemplate)field(documents,"jdbc")).getJdbcTemplate().getDataSource()).isSameAs(writer);
            assertThat(field(documents,"terms")).isSameAs(context.getBean("storeDirectOrderSearchTerms"));
            assertThat(context.getBean("storePaymentEventClock",Clock.class).getZone()).isEqualTo(ZoneId.systemDefault());
            assertThat(context.getBean("storePaymentRankingRedis",StringRedisTemplate.class).getConnectionFactory())
                    .isInstanceOf(org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class);
        });
        verifyNoInteractions(writer);
    }

    @Test void given__an_explicit_existing_redis_template__when__ranking_is_invoked__then__delegate_original_sorted_set_increment_without_creating_another_client() {
        DataSource writer=mock(DataSource.class);var redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ZSetOperations<String,String> sorted=mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(sorted);
        inputs(writer).withBean("memberChatRedis",StringRedisTemplate.class,()->redis).run(context->{
            assertThat(context).hasNotFailed().doesNotHaveBean(RedisConnectionFactory.class);
            assertThat(context.getBean("storePaymentRankingRedis")).isSameAs(redis);
            context.getBean(StorePaymentEvents.Ranking.class).increment("store:ranking:daily:20261021","73",2);
            verify(sorted).incrementScore("store:ranking:daily:20261021","73",2);
            verifyNoMoreInteractions(sorted);
        });
        verifyNoInteractions(writer);
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(StorePaymentEventsConfiguration.class);
    }
    private static ApplicationContextRunner inputs(DataSource writer) {
        return runner().withPropertyValues("pawbridge.store-payment-events.enabled=true","spring.data.redis.host=127.0.0.1")
                .withBean("storePaymentOwnedRedisFactory",StorePaymentTransportConfiguration.OwnedRedisFactory.class,
                        ()->settings->mock(org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class))
                .withBean("writeDataSource",DataSource.class,()->writer)
                .withBean("writeJdbc",JdbcTemplate.class,()->new JdbcTemplate(writer))
                .withBean(ObjectMapper.class,ObjectMapper::new);
    }
    private static Object field(Object target,String name) { return ReflectionTestUtils.getField(target,name); }
    private static EntityManager repositoryEntityManager(Object repository) {
        return (EntityManager)field(AopTestUtils.getUltimateTargetObject(repository),"entityManager");
    }
}
