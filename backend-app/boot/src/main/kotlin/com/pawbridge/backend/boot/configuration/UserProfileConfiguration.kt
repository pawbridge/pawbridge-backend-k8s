package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import com.pawbridge.backend.user.internal.profile.infrastructure.JdbcUserProfileQuery
import com.pawbridge.backend.user.internal.profile.web.AdminUserDetailController
import com.pawbridge.backend.user.internal.profile.web.UserProfileController
import com.pawbridge.backend.user.internal.profile.web.UserProfileExceptionHandler
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class UserProfileConfiguration {
    @Bean
    fun userProfileQuery(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): UserProfileQuery {
        return JdbcUserProfileQuery(jdbc, transaction)
    }

    @Bean
    fun userProfileController(profiles: UserProfileQuery): UserProfileController {
        return UserProfileController(profiles)
    }

    @Bean
    fun adminUserDetailController(profiles: UserProfileQuery): AdminUserDetailController {
        return AdminUserDetailController(profiles)
    }

    @Bean
    fun userProfileExceptionHandler(): UserProfileExceptionHandler {
        return UserProfileExceptionHandler()
    }
}
