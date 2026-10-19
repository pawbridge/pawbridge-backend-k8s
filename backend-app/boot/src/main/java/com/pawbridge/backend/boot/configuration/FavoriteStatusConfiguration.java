package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery;
import com.pawbridge.backend.user.internal.favorite.infrastructure.JdbcFavoriteStatusQuery;
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusController;
import com.pawbridge.backend.user.internal.favorite.web.FavoriteStatusExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class FavoriteStatusConfiguration {
    @Bean
    FavoriteStatusQuery favoriteStatusQuery(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                          @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcFavoriteStatusQuery(jdbc, transaction);
    }

    @Bean
    FavoriteStatusController favoriteStatusController(FavoriteStatusQuery favorites) {
        return new FavoriteStatusController(favorites);
    }

    @Bean
    FavoriteStatusExceptionHandler favoriteStatusExceptionHandler() { return new FavoriteStatusExceptionHandler(); }
}
