package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.api.ContactMemberCleanup;
import com.pawbridge.backend.community.internal.chat.application.MemberChatHints;
import com.pawbridge.backend.community.internal.chat.application.MemberChatModels.Signal;
import org.springframework.beans.factory.ObjectProvider;
import com.pawbridge.backend.community.internal.note.application.CleanupContactMember;
import com.pawbridge.backend.community.internal.note.application.ContactMemberCleanupStore;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcContactMemberCleanupStore;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion;
import com.pawbridge.backend.user.internal.deletion.infrastructure.JdbcUserDeletion;
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionController;
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionExceptionHandler;
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Synchronous SQL cleanup remains single-owned; chat and note hints follow its commit. */
@Configuration(proxyBeanMethods = false)
public class MemberCleanupConfiguration {
    private final boolean chatEnabled;

    public MemberCleanupConfiguration(@Value("${pawbridge.chat.enabled:${MEMBER_CHAT_ENABLED:false}}") boolean chatEnabled) {
        this.chatEnabled = chatEnabled;
    }

    @Bean
    ContactMemberCleanupStore contactMemberCleanupStore(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, PrivateNoteStreamRegistry streams, ObjectProvider<MemberChatHints> chatHints) {
        MemberChatHints chat = chatEnabled ? chatHints.getIfAvailable() : null;
        if (chatEnabled && chat == null) {
            throw new IllegalStateException("Member cleanup with chat enabled requires verified Redis withdrawal wiring");
        }
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcContactMemberCleanupStore(jdbc, transaction, memberId -> {
            if (chat != null) {
                try { chat.publish(new Signal(memberId,"WITHDRAWN",null,0)); }
                catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(MemberCleanupConfiguration.class)
                            .warn("Chat withdrawal hint unavailable ({})",failure.getClass().getSimpleName());
                }
            }
            streams.closeMember(memberId);
            streams.resyncAll();
        });
    }

    @Bean
    ContactMemberCleanup contactMemberCleanup(ContactMemberQuery members, ContactMemberCleanupStore store) {
        return new CleanupContactMember(members, store);
    }

    /** Starting a deletion revokes the member's refresh sessions together with the pending marker. */
    @Bean
    UserDeletion userDeletion(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, ContactMemberCleanup cleanup,
            ObjectProvider<UserSessionRevoker> sessions) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        UserSessionRevoker revoker = sessions.getIfAvailable(() -> UserSessionRevoker.NONE);
        return new JdbcUserDeletion(jdbc, transaction, cleanup::remove, revoker);
    }

    @Bean
    AdminUserDeletionController adminUserDeletionController(UserDeletion deletion) {
        return new AdminUserDeletionController(deletion);
    }

    @Bean
    AdminUserDeletionExceptionHandler adminUserDeletionExceptionHandler() {
        return new AdminUserDeletionExceptionHandler();
    }
}
