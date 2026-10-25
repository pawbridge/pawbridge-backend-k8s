package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import java.util.function.Supplier

class MemberChatConfigurationTest {
    @Test
    fun given__cleanup_disabled_with_chat_enabled__when__assembling_retention__then__create_no_retention_and_make_no_expiry_call() {
        val chat = mock(MemberChat::class.java)
        ApplicationContextRunner().withUserConfiguration(MemberChatConfiguration.RetentionConfiguration::class.java)
            .withBean(MemberChat::class.java, Supplier { chat })
            .withPropertyValues("pawbridge.chat.enabled=true", "pawbridge.jobs.cleanup-enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                    .doesNotHaveBean(MemberChatConfiguration.MemberChatRetention::class.java)
                verifyNoInteractions(chat)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun given__chat_disabled_or_unspecified__when__assembling__then__create_no_chat_redis_socket_or_maintenance_resources(
        flag: String,
    ) {
        var runner = ApplicationContextRunner().withUserConfiguration(MemberChatConfiguration::class.java)
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.chat.enabled=false")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(MemberChat::class.java)
                .doesNotHaveBean(MemberChatHints::class.java)
                .doesNotHaveBean(RedisConnectionFactory::class.java)
                .doesNotHaveBean(TaskScheduler::class.java)
                .doesNotHaveBean(WebSocketMessageBrokerConfigurer::class.java)
            assertThat(context).doesNotHaveBean(MemberChatTransportConfiguration::class.java)
                .doesNotHaveBean(MemberChatSocketConfiguration::class.java)
        }
    }
}
