package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands;
import com.pawbridge.backend.store.internal.wishlist.infrastructure.JdbcWishlistCommands;
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandController;
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandExceptionHandler;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
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
