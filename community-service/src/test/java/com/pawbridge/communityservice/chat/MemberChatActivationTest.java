package com.pawbridge.communityservice.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MemberChatActivationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(MemberChatController.class)
            .withBean(MemberChatService.class, () -> mock(MemberChatService.class))
            .withBean(MemberChatTickets.class, () -> mock(MemberChatTickets.class));

    @Test
    void givenChatEnabledWithoutDatabaseProfile_whenContextCreated_thenControllerIsRegistered() {
        context.withPropertyValues("pawbridge.chat.enabled=true").run(application -> {
            assertThat(application).hasSingleBean(MemberChatController.class);
            assertThat(application.getEnvironment().getActiveProfiles()).doesNotContain("postgresql");
        });
    }

    @Test
    void givenChatNotEnabled_whenContextCreated_thenControllerIsNotRegistered() {
        context.run(application -> assertThat(application).doesNotHaveBean(MemberChatController.class));
    }
}
