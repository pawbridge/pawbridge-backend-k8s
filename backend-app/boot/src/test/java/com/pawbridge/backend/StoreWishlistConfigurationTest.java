package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings;
import com.pawbridge.backend.boot.configuration.StoreWishlistConfiguration;
import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands;
import com.pawbridge.backend.store.internal.wishlist.infrastructure.JdbcWishlistCommands;
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandController;
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandExceptionHandler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @Test void given__explicit_flag_and_store_credentials__when__assembling_and_closing__then__bind_separate_bounded_writer_and_system_clock_without_clients_or_connection() throws Exception {
        var values=F3TestInputs.values();var reader=ReadDatabaseSettings.fromEnvironment(values::get);HikariConfig[] config=new HikariConfig[1];
        try(var constructions=mockConstruction(HikariDataSource.class,(mock,construction)->config[0]=(HikariConfig)construction.arguments().getFirst())) {
            new ApplicationContextRunner().withUserConfiguration(StoreWishlistConfiguration.class)
                    .withBean(ReadDatabaseSettings.class,()->reader)
                    .withPropertyValues("pawbridge.store-wishlist.enabled=true","STORE_WISHLIST_DB_WRITER_USER=fixture-store-writer","STORE_WISHLIST_DB_WRITER_PASSWORD=synthetic-store-only")
                    .run(context->{
                        assertThat(context).hasNotFailed();assertThat(constructions.constructed()).hasSize(1);
                        HikariDataSource source=context.getBean("storeWishlistWriteDataSource",HikariDataSource.class);
                        JdbcTemplate jdbc=context.getBean("storeWishlistWriteJdbc",JdbcTemplate.class);
                        var manager=context.getBean("storeWishlistWriteTransactionManager",JdbcTransactionManager.class);
                        var transaction=context.getBean("storeWishlistWriteTransaction",TransactionTemplate.class);
                        assertThat(jdbc.getDataSource()).isSameAs(source);assertThat(jdbc.getQueryTimeout()).isEqualTo(5);
                        assertThat(manager.getDataSource()).isSameAs(source);assertThat(transaction.getTransactionManager()).isSameAs(manager);
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
            var source=constructions.constructed().getFirst();verify(source).close();verify(source,never()).getConnection();verify(source,never()).getConnection(anyString(),anyString());
            assertThat(config[0].getJdbcUrl()).isEqualTo(reader.jdbcUrl());assertThat(config[0].getUsername()).isEqualTo("fixture-store-writer");
            assertThat(config[0].getPassword()).isEqualTo("synthetic-store-only");assertThat(config[0].isReadOnly()).isFalse();
            assertThat(config[0].getPoolName()).isEqualTo("pawbridge-store-wishlist-write");assertThat(config[0].getMaximumPoolSize()).isEqualTo(1);
            assertThat(config[0].getMinimumIdle()).isZero();assertThat(config[0].getInitializationFailTimeout()).isEqualTo(-1);
            assertThat(config[0].getConnectionTimeout()).isEqualTo(2000);assertThat(config[0].getValidationTimeout()).isEqualTo(1000);
        }
    }

    @ParameterizedTest @CsvSource(value={"'',synthetic-store-only,STORE_WISHLIST_DB_WRITER_USER","fixture-reader,synthetic-store-only,STORE_WISHLIST_DB_WRITER_USER","fixture-store-writer,'',STORE_WISHLIST_DB_WRITER_PASSWORD"})
    void given__blank_or_reader_credentials__when__enabled__then__fail_before_pool_construction_without_secret_message(String username,String password,String key) {
        var values=F3TestInputs.values();var reader=ReadDatabaseSettings.fromEnvironment(values::get);
        try(var constructions=mockConstruction(HikariDataSource.class)) {
            new ApplicationContextRunner().withUserConfiguration(StoreWishlistConfiguration.class).withBean(ReadDatabaseSettings.class,()->reader)
                    .withPropertyValues("pawbridge.store-wishlist.enabled=true","STORE_WISHLIST_DB_WRITER_USER="+username,"STORE_WISHLIST_DB_WRITER_PASSWORD="+password)
                    .run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasRootCauseMessage("Missing or invalid explicit setting: "+key);});
            assertThat(constructions.constructed()).isEmpty();
        }
    }
}
