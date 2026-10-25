package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands
import com.pawbridge.backend.store.internal.wishlist.infrastructure.JdbcWishlistCommands
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandController
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandExceptionHandler
import com.zaxxer.hikari.HikariDataSource
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Prepared only: duplicate concurrency, identity/least grants and runtime handover gate activation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.store-wishlist", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class StoreWishlistConfiguration {
    @Bean
    fun storeWishlistWriteJdbc(
        @Qualifier("storeWishlistWriteDataSource") source: HikariDataSource,
    ): JdbcTemplate {
        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 5
        return jdbc
    }

    @Bean
    fun storeWishlistWriteTransactionManager(
        @Qualifier("storeWishlistWriteDataSource") source: HikariDataSource,
    ): JdbcTransactionManager {
        return JdbcTransactionManager(source)
    }

    @Bean
    fun storeWishlistWriteTransaction(
        @Qualifier("storeWishlistWriteTransactionManager") manager: JdbcTransactionManager,
    ): TransactionTemplate {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 10
        return transaction
    }

    @Bean
    fun storeWishlistClock(): Clock {
        return Clock.systemDefaultZone()
    }

    @Bean
    fun wishlistCommands(
        @Qualifier("storeWishlistWriteJdbc") jdbc: JdbcTemplate,
        @Qualifier("storeWishlistWriteTransaction") write: TransactionTemplate,
        @Qualifier("storeWishlistClock") clock: Clock,
    ): WishlistCommands {
        return JdbcWishlistCommands(NamedParameterJdbcTemplate(jdbc), write, clock)
    }

    @Bean
    fun wishlistCommandController(commands: WishlistCommands): WishlistCommandController {
        return WishlistCommandController(commands)
    }

    @Bean
    fun wishlistCommandExceptionHandler(): WishlistCommandExceptionHandler {
        return WishlistCommandExceptionHandler()
    }
}
