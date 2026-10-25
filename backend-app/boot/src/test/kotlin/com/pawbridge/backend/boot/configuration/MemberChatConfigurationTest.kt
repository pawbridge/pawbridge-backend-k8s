package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.chat.application.MemberChat
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import java.util.function.Supplier

class MemberChatConfigurationTest {
    @Test
    fun `채팅은 켜졌지만 정리가 꺼졌을 때__보존 구성을 조립하면__보존을 만들지 않고 만료 호출도 하지 않는다`() {
        // given
        val chat = mockk<MemberChat>()

        excludeRecords { chat.hashCode() }

        val runner = ApplicationContextRunner()
            .withUserConfiguration(MemberChatConfiguration.RetentionConfiguration::class.java)
            .withBean(MemberChat::class.java, Supplier { chat })
            .withPropertyValues("pawbridge.chat.enabled=true", "pawbridge.jobs.cleanup-enabled=false")

        // when
        runner
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                    .doesNotHaveBean(MemberChatConfiguration.MemberChatRetention::class.java)
                confirmVerified(chat)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `채팅이 꺼졌거나 지정되지 않았을 때__조립하면__채팅 Redis 소켓 유지보수 자원을 만들지 않는다`(
        flag: String,
    ) {
        // given
        var runner = ApplicationContextRunner().withUserConfiguration(MemberChatConfiguration::class.java)
        if (flag == "false") {
            runner = runner.withPropertyValues("pawbridge.chat.enabled=false")
        }

        // when
        runner.run { context ->
            // then
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
