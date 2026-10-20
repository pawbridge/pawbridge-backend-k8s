package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands;
import com.pawbridge.backend.store.internal.wishlist.infrastructure.JdbcWishlistCommands;
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandController;
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandExceptionHandler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Prepared only: duplicate concurrency, identity/least grants and runtime handover gate activation. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.store-wishlist",name="enabled",havingValue="true",matchIfMissing=false)
public class StoreWishlistConfiguration {
    @Bean(destroyMethod="close")
    HikariDataSource storeWishlistWriteDataSource(ReadDatabaseSettings reader,
            @Value("${STORE_WISHLIST_DB_WRITER_USER:}") String username,
            @Value("${STORE_WISHLIST_DB_WRITER_PASSWORD:}") String password) {
        if(username.isBlank() || username.equals(reader.username()))
            throw new IllegalArgumentException("Missing or invalid explicit setting: STORE_WISHLIST_DB_WRITER_USER");
        if(password.isBlank()) throw new IllegalArgumentException("Missing or invalid explicit setting: STORE_WISHLIST_DB_WRITER_PASSWORD");
        HikariConfig config=new HikariConfig();
        config.setJdbcUrl(reader.jdbcUrl());config.setUsername(username);config.setPassword(password);
        config.setPoolName("pawbridge-store-wishlist-write");config.setMaximumPoolSize(1);config.setMinimumIdle(0);
        config.setConnectionTimeout(2000);config.setValidationTimeout(1000);config.setInitializationFailTimeout(-1);config.setReadOnly(false);
        return new HikariDataSource(config);
    }
    @Bean JdbcTemplate storeWishlistWriteJdbc(@Qualifier("storeWishlistWriteDataSource") HikariDataSource source) {
        JdbcTemplate jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(5);return jdbc;
    }
    @Bean JdbcTransactionManager storeWishlistWriteTransactionManager(@Qualifier("storeWishlistWriteDataSource") HikariDataSource source) {
        return new JdbcTransactionManager(source);
    }
    @Bean TransactionTemplate storeWishlistWriteTransaction(@Qualifier("storeWishlistWriteTransactionManager") JdbcTransactionManager manager) {
        TransactionTemplate transaction=new TransactionTemplate(manager);transaction.setTimeout(10);return transaction;
    }
    @Bean Clock storeWishlistClock() { return Clock.systemDefaultZone(); }
    @Bean WishlistCommands wishlistCommands(@Qualifier("storeWishlistWriteJdbc") JdbcTemplate jdbc,
            @Qualifier("storeWishlistWriteTransaction") TransactionTemplate write,@Qualifier("storeWishlistClock") Clock clock) {
        return new JdbcWishlistCommands(new NamedParameterJdbcTemplate(jdbc),write,clock);
    }
    @Bean WishlistCommandController wishlistCommandController(WishlistCommands commands) { return new WishlistCommandController(commands); }
    @Bean WishlistCommandExceptionHandler wishlistCommandExceptionHandler() { return new WishlistCommandExceptionHandler(); }
}
