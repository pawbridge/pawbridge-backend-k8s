package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.api.ContactMemberCleanup;
import com.pawbridge.backend.community.internal.note.application.CleanupContactMember;
import com.pawbridge.backend.community.internal.note.application.ContactMemberCleanupStore;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcContactMemberCleanupStore;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion;
import com.pawbridge.backend.user.internal.deletion.infrastructure.JdbcUserDeletion;
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionController;
import com.pawbridge.backend.user.internal.deletion.web.AdminUserDeletionExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Partial transport mode: SQL cleanup remains mandatory while Redis chat is not wired. */
@Configuration(proxyBeanMethods = false)
public class MemberCleanupConfiguration {
    public MemberCleanupConfiguration(@Value("${pawbridge.chat.enabled:${MEMBER_CHAT_ENABLED:false}}") boolean chatEnabled) {
        if (chatEnabled) {
            throw new IllegalStateException("Member cleanup with chat enabled requires verified Redis withdrawal wiring");
        }
    }

    @Bean
    ContactMemberCleanupStore contactMemberCleanupStore(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, PrivateNoteStreamRegistry streams) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcContactMemberCleanupStore(jdbc, transaction, memberId -> {
            streams.closeMember(memberId);
            streams.resyncAll();
        });
    }

    @Bean
    ContactMemberCleanup contactMemberCleanup(ContactMemberQuery members, ContactMemberCleanupStore store) {
        return new CleanupContactMember(members, store);
    }

    @Bean
    UserDeletion userDeletion(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, ContactMemberCleanup cleanup) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcUserDeletion(jdbc, transaction, cleanup::remove);
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
