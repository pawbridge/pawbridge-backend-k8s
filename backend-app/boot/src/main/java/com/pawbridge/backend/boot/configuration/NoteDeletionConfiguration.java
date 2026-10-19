package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.NoteChangeHints;
import com.pawbridge.backend.community.internal.note.application.NoteDeletion;
import com.pawbridge.backend.community.internal.note.application.DeletePrivateNote;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteDeletion;
import com.pawbridge.backend.community.internal.note.web.NoteDeletionController;
import com.pawbridge.backend.community.internal.note.web.NoteDeletionExceptionHandler;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class NoteDeletionConfiguration {
    @Bean
    NoteDeletion noteDeletion(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, NoteChangeHints hints) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcNoteDeletion(jdbc, transaction, hints);
    }

    @Bean
    DeletePrivateNote deletePrivateNote(ContactMemberQuery members, NoteDeletion mutation,
            @Qualifier("readClock") Clock clock) {
        return new DeletePrivateNote(members, mutation, clock);
    }

    @Bean
    NoteDeletionController noteDeletionController(DeletePrivateNote deletion) {
        return new NoteDeletionController(deletion);
    }

    @Bean
    NoteDeletionExceptionHandler noteDeletionExceptionHandler() {
        return new NoteDeletionExceptionHandler();
    }
}
