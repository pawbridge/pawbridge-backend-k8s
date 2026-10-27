package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.UserPasswordConfiguration
import com.pawbridge.backend.user.internal.password.application.ChangeUserPassword
import com.pawbridge.backend.user.internal.password.application.UserPasswordStore
import com.pawbridge.backend.user.internal.password.web.UserPasswordController
import com.pawbridge.backend.user.internal.password.web.UserPasswordExceptionHandler
import io.mockk.justRun
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.function.Supplier

class UserPasswordConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "on", "yes"])
    fun `플래그가 정확히 true가 아니고 입력이 없을 때__조립하면__비밀번호 기능을 등록하지 않는다`(flag: String) {
        // given
        var runner = ApplicationContextRunner().withUserConfiguration(UserPasswordConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.user-password.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(ChangeUserPassword::class.java)
                .doesNotHaveBean(UserPasswordStore::class.java)
                .doesNotHaveBean(UserPasswordController::class.java)
                .doesNotHaveBean(UserPasswordExceptionHandler::class.java)
        }
    }

    @Test
    fun `쓰기 자원 없이 켜졌을 때__조립하면__대체물을 만들지 않고 실패한다`() {
        // given
        val runner = ApplicationContextRunner().withUserConfiguration(UserPasswordConfiguration::class.java)
            .withPropertyValues("pawbridge.user-password.enabled=true")

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun `쓰기 자원과 함께 켜졌을 때__조립하면__전역 인코더를 등록하지 않는다`() {
        // given
        val writeJdbc = mockk<JdbcTemplate>()
        val writeManager = mockk<JdbcTransactionManager>()

        justRun { writeJdbc.afterPropertiesSet() }
        justRun { writeManager.afterPropertiesSet() }

        // when
        ApplicationContextRunner().withUserConfiguration(UserPasswordConfiguration::class.java)
            .withPropertyValues("pawbridge.user-password.enabled=TRUE")
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { writeJdbc })
            .withBean("writeTransactionManager", JdbcTransactionManager::class.java, Supplier { writeManager })
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                    .hasSingleBean(UserPasswordController::class.java)
                    .hasSingleBean(UserPasswordExceptionHandler::class.java)
                    .doesNotHaveBean(PasswordEncoder::class.java)
            }
    }

}
