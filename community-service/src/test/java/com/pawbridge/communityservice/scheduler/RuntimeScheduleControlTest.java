package com.pawbridge.communityservice.scheduler;

import com.pawbridge.communityservice.chat.MemberChatDataLifecycle;
import com.pawbridge.communityservice.chat.MemberChatModels.Withdrawn;
import com.pawbridge.communityservice.chat.MemberChatRepository;
import com.pawbridge.communityservice.contact.PrivateNoteConfiguration;
import com.pawbridge.communityservice.contact.PrivateNoteService;
import com.pawbridge.communityservice.curation.HomeVideoRefresh;
import com.pawbridge.communityservice.curation.HomeVideoService;
import com.pawbridge.communityservice.domain.repository.OutboxEventRepository;
import com.pawbridge.communityservice.domain.repository.ProcessedEventRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeScheduleControlTest {
    private final MemberChatRepository chats = mock(MemberChatRepository.class);
    private final PrivateNoteService notes = mock(PrivateNoteService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CleanupScheduler.class, HomeVideoRefresh.class,
                    MemberChatDataLifecycle.class, PrivateNoteConfiguration.class)
            .withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class))
            .withBean(ProcessedEventRepository.class, () -> mock(ProcessedEventRepository.class))
            .withBean(HomeVideoService.class, () -> mock(HomeVideoService.class))
            .withBean(MemberChatRepository.class, () -> chats)
            .withBean(PrivateNoteService.class, () -> notes)
            .withBean(PlatformTransactionManager.class, () -> transactions);

    @Test void disabledJobsKeepClockAndLifecycleWithoutExpiringData() {
        context.withPropertyValues("pawbridge.jobs.cleanup-enabled=false", "pawbridge.jobs.refresh-enabled=false")
                .run(c -> {
                    assertThat(c).doesNotHaveBean(CleanupScheduler.class).doesNotHaveBean(HomeVideoRefresh.class);
                    assertThat(c).hasSingleBean(Clock.class).hasSingleBean(MemberChatDataLifecycle.class);
                    c.getBean(MemberChatDataLifecycle.class).expire();
                    c.getBean(PrivateNoteConfiguration.class).expireNotes();
                    verifyNoInteractions(chats, notes, transactions);
                });
    }

    @Test void disablingCleanupDoesNotDisableMemberWithdrawal() {
        when(chats.installed()).thenReturn(true);
        context.withPropertyValues("pawbridge.jobs.cleanup-enabled=false")
                .run(c -> {
                    c.publishEvent(new Withdrawn(42L));
                    verify(chats).withdraw(42L);
                    verify(chats, never()).expire(any());
                });
    }

    @Test void defaultSettingsKeepExistingCleanupAndRefresh() {
        when(chats.installed()).thenReturn(true);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        context.run(c -> {
            assertThat(c).hasSingleBean(CleanupScheduler.class).hasSingleBean(HomeVideoRefresh.class);
            c.getBean(MemberChatDataLifecycle.class).expire();
            c.getBean(PrivateNoteConfiguration.class).expireNotes();
            verify(chats).expire(any());
            verify(notes).expire();
            verify(transactions).commit(any());
        });
    }
}
