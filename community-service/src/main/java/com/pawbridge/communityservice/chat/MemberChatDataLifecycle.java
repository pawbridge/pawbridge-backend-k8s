package com.pawbridge.communityservice.chat;

import com.pawbridge.communityservice.chat.MemberChatModels.Withdrawn;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Retention and withdrawal do not stop when the chat UI/transport feature flag is disabled. */
@Component
public class MemberChatDataLifecycle {
    private final MemberChatRepository repository;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public MemberChatDataLifecycle(MemberChatRepository repository, Clock clock, PlatformTransactionManager manager) {
        this.repository = repository;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(15);
    }

    @EventListener
    public void withdraw(Withdrawn event) {
        if (repository.installed()) repository.withdraw(event.memberId());
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void expire() {
        if (repository.installed()) transactions.executeWithoutResult(status -> repository.expire(clock.instant()));
    }
}
