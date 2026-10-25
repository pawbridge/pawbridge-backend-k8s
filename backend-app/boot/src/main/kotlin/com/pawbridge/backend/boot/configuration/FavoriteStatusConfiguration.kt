package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery
import com.pawbridge.backend.user.internal.favorite.infrastructure.JdbcFavoriteStatusQuery
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class FavoriteStatusConfiguration {
    @Bean
    fun favoriteStatusQuery(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): FavoriteStatusQuery {
        return JdbcFavoriteStatusQuery(jdbc, transaction)
    }

    @Bean
    fun favoriteStatusController(favorites: FavoriteStatusQuery): FavoriteStatusController {
        return FavoriteStatusController(favorites)
    }

    @Bean
    fun favoriteStatusExceptionHandler(): FavoriteStatusExceptionHandler {
        return FavoriteStatusExceptionHandler()
    }
}
