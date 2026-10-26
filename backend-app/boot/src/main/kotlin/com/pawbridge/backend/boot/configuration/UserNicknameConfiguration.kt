package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname
import com.pawbridge.backend.user.internal.nickname.application.UserNicknameStore
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameController
import com.pawbridge.backend.user.internal.nickname.web.UserNicknameExceptionHandler
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.EnableTransactionManagement

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
class UserNicknameConfiguration {
    @Bean
    fun userNicknameStore(@Qualifier("writeJdbc") jdbc: JdbcTemplate): UserNicknameStore {
        return JdbcUserNicknameStore(jdbc)
    }

    @Bean
    fun changeUserNickname(users: UserNicknameStore): ChangeUserNickname {
        return ChangeUserNickname(users)
    }

    @Bean
    fun userNicknameController(change: ChangeUserNickname): UserNicknameController {
        return UserNicknameController(change)
    }

    @Bean
    fun userNicknameExceptionHandler(): UserNicknameExceptionHandler {
        return UserNicknameExceptionHandler()
    }
}
