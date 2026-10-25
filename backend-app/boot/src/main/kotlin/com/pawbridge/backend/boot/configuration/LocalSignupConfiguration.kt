package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore
import com.pawbridge.backend.user.internal.oauth.infrastructure.SocialNicknameGenerator
import com.pawbridge.backend.user.internal.password.infrastructure.BcryptPasswordHashes
import com.pawbridge.backend.user.internal.signup.application.LocalSignup
import com.pawbridge.backend.user.internal.signup.infrastructure.JdbcSignupAccounts
import com.pawbridge.backend.user.internal.signup.web.LocalSignupController
import java.util.function.Supplier
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.EnableTransactionManagement

/** Dormant original LOCAL signup. It requires the e-mail verification feature and fails without it. */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@ConditionalOnProperty(
    prefix = "pawbridge.local-signup",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class LocalSignupConfiguration {
    @Bean
    fun localSignup(verifications: EmailVerifications, @Qualifier("writeJdbc") jdbc: JdbcTemplate): LocalSignup {
        val nicknames = SocialNicknameGenerator(JdbcUserNicknameStore(jdbc))
        // Kept local to this feature: no global PasswordEncoder bean is registered.
        return LocalSignup(
            verifications,
            JdbcSignupAccounts(jdbc, Supplier { nicknames.generateUniqueNickname() }),
            BcryptPasswordHashes(),
        )
    }

    @Bean
    fun localSignupController(signups: LocalSignup): LocalSignupController {
        return LocalSignupController(signups)
    }
}
