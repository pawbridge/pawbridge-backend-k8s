package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.password.application.ChangeUserPassword
import com.pawbridge.backend.user.internal.password.application.PasswordHashes
import com.pawbridge.backend.user.internal.password.application.UserPasswordStore
import com.pawbridge.backend.user.internal.password.infrastructure.BcryptPasswordHashes
import com.pawbridge.backend.user.internal.password.infrastructure.JdbcUserPasswordStore
import com.pawbridge.backend.user.internal.password.web.UserPasswordController
import com.pawbridge.backend.user.internal.password.web.UserPasswordExceptionHandler
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import java.time.LocalDateTime
import java.util.function.Supplier
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.EnableTransactionManagement

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@ConditionalOnProperty(prefix = "pawbridge.user-password", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class UserPasswordConfiguration {
    /** A password change revokes every refresh session of the member in the same transaction. */
    @Bean
    fun userPasswordStore(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        sessions: ObjectProvider<UserSessionRevoker>,
    ): UserPasswordStore {
        val revoker: UserSessionRevoker = sessions.getIfAvailable { UserSessionRevoker.NONE }
        return JdbcUserPasswordStore(jdbc, Supplier { LocalDateTime.now() }, revoker)
    }

    // Kept local to this feature: no global PasswordEncoder bean is registered.
    @Bean
    fun changeUserPassword(users: UserPasswordStore): ChangeUserPassword {
        val hashes: PasswordHashes = BcryptPasswordHashes()
        return ChangeUserPassword(users, hashes)
    }

    @Bean
    fun userPasswordController(change: ChangeUserPassword): UserPasswordController {
        return UserPasswordController(change)
    }

    @Bean
    fun userPasswordExceptionHandler(): UserPasswordExceptionHandler {
        return UserPasswordExceptionHandler()
    }
}
