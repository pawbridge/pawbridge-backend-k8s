package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.boot.configuration.StoreWishlistConfiguration;
import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands;
import com.pawbridge.backend.store.internal.wishlist.infrastructure.JdbcWishlistCommands;
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandController;
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandExceptionHandler;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class StoreWishlistConfigurationTest {
    @Test void given__absent_or_false_flag__when__loading_imported_configuration_without_inputs__then__no_writer_or_routes() {
        assertThat(Arrays.asList(PawBridgeApplication.class.getAnnotation(Import.class).value())).contains(StoreWishlistConfiguration.class);
        for(String flag:new String[]{"","pawbridge.store-wishlist.enabled=false"}) {
            var runner=new ApplicationContextRunner().withUserConfiguration(StoreWishlistConfiguration.class);
            if(!flag.isEmpty()) runner=runner.withPropertyValues(flag);
            runner.run(context->{
                assertThat(context).hasNotFailed();assertThat(context.getBeansOfType(HikariDataSource.class)).isEmpty();
                assertThat(context.getBeansOfType(JdbcTemplate.class)).isEmpty();assertThat(context.getBeansOfType(TransactionTemplate.class)).isEmpty();
                assertThat(context.getBeansOfType(Clock.class)).isEmpty();assertThat(context.getBeansOfType(WishlistCommands.class)).isEmpty();
                assertThat(context.getBeansOfType(WishlistCommandController.class)).isEmpty();assertThat(context.getBeansOfType(WishlistCommandExceptionHandler.class)).isEmpty();
            });
        }
    }

    @Test void given__explicit_flag__when__assembling_and_closing__then__bind_the_shared_pool_and_system_clock_without_own_pool_clients_or_connection() throws Exception {
        var shared=mock(HikariDataSource.class);
        try(var constructions=mockConstruction(HikariDataSource.class)) {
            new ApplicationContextRunner().withUserConfiguration(StoreWishlistConfiguration.class)
                    .withBean("storeWishlistWriteDataSource",HikariDataSource.class,()->shared)
                    .withPropertyValues("pawbridge.store-wishlist.enabled=true","STORE_WISHLIST_DB_WRITER_USER=ignored-legacy-writer","STORE_WISHLIST_DB_WRITER_PASSWORD=ignored-legacy-only")
                    .run(context->{
                        assertThat(context).hasNotFailed();assertThat(context.getBeansOfType(HikariDataSource.class)).containsOnlyKeys("storeWishlistWriteDataSource");
                        JdbcTemplate jdbc=context.getBean("storeWishlistWriteJdbc",JdbcTemplate.class);
                        var manager=context.getBean("storeWishlistWriteTransactionManager",JdbcTransactionManager.class);
                        var transaction=context.getBean("storeWishlistWriteTransaction",TransactionTemplate.class);
                        assertThat(jdbc.getDataSource()).isSameAs(shared);assertThat(jdbc.getQueryTimeout()).isEqualTo(5);
                        assertThat(manager.getDataSource()).isSameAs(shared);assertThat(transaction.getTransactionManager()).isSameAs(manager);
                        assertThat(transaction.getTimeout()).isEqualTo(10);assertThat(transaction.isReadOnly()).isFalse();
                        var command=(JdbcWishlistCommands)context.getBean(WishlistCommands.class);
                        assertThat(((NamedParameterJdbcTemplate)ReflectionTestUtils.getField(command,"jdbc")).getJdbcOperations()).isSameAs(jdbc);
                        assertThat(ReflectionTestUtils.getField(command,"write")).isSameAs(transaction);
                        var clock=context.getBean("storeWishlistClock",Clock.class);assertThat(clock.getZone()).isEqualTo(ZoneId.systemDefault());
                        assertThat(ReflectionTestUtils.getField(command,"clock")).isSameAs(clock);
                        assertThat(context.getBeansOfType(WishlistCommandController.class)).hasSize(1);
                        assertThat(context.getBeansOfType(WishlistCommandExceptionHandler.class)).hasSize(1);
                        assertThat(context.getBeansOfType(java.net.http.HttpClient.class)).isEmpty();
                        assertThat(context.getBeansOfType(org.springframework.web.client.RestClient.class)).isEmpty();
                        assertThat(context.getBeansOfType(software.amazon.awssdk.services.s3.S3Client.class)).isEmpty();
                    });
            assertThat(constructions.constructed()).isEmpty();
            verify(shared,never()).getConnection();verify(shared,never()).getConnection(anyString(),anyString());
        }
    }
}
