package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pawbridge.backend.community.internal.chat.application.MemberChat;
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

class MemberChatConfigurationTest {
    @org.junit.jupiter.api.Test
    void given__cleanup_disabled_with_chat_enabled__when__assembling_retention__then__create_no_retention_and_make_no_expiry_call() {
        var chat=org.mockito.Mockito.mock(MemberChat.class);
        new ApplicationContextRunner().withUserConfiguration(MemberChatConfiguration.RetentionConfiguration.class)
                .withBean(MemberChat.class,()->chat)
                .withPropertyValues("pawbridge.chat.enabled=true","pawbridge.jobs.cleanup-enabled=false")
                .run(context->{
                    assertThat(context).hasNotFailed().doesNotHaveBean(MemberChatConfiguration.MemberChatRetention.class);
                    org.mockito.Mockito.verifyNoInteractions(chat);
                });
    }

    @ParameterizedTest @ValueSource(strings={"absent","false"})
    void given__chat_disabled_or_unspecified__when__assembling__then__create_no_chat_redis_socket_or_maintenance_resources(String flag) {
        var runner=new ApplicationContextRunner().withUserConfiguration(MemberChatConfiguration.class);
        if(flag.equals("false"))runner=runner.withPropertyValues("pawbridge.chat.enabled=false");
        runner.run(context->{
            assertThat(context).hasNotFailed().doesNotHaveBean(MemberChat.class).doesNotHaveBean(MemberChatHints.class)
                    .doesNotHaveBean(RedisConnectionFactory.class).doesNotHaveBean(TaskScheduler.class)
                    .doesNotHaveBean(WebSocketMessageBrokerConfigurer.class);
            assertThat(context).doesNotHaveBean(MemberChatTransportConfiguration.class)
                    .doesNotHaveBean(MemberChatSocketConfiguration.class);
        });
    }
}
