package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.CreateNoteBlock;
import com.pawbridge.backend.community.internal.note.application.NoteBlockCreation;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockCreation;
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateExceptionHandler;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class NoteBlockCreateConfiguration {
    @Bean
    NoteBlockCreation noteBlockCreation(@Qualifier("writeJdbc") JdbcTemplate jdbc,
                                       @Qualifier("writeTransactionManager") JdbcTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcNoteBlockCreation(jdbc, transaction);
    }

    @Bean
    CreateNoteBlock createNoteBlock(ContactMemberQuery members, NoteBlockCreation creation,
                                   @Qualifier("readClock") Clock clock) {
        return new CreateNoteBlock(members, creation, clock);
    }

    @Bean
    NoteBlockCreateController noteBlockCreateController(CreateNoteBlock creation) {
        return new NoteBlockCreateController(creation);
    }

    @Bean
    NoteBlockCreateExceptionHandler noteBlockCreateExceptionHandler() {
        return new NoteBlockCreateExceptionHandler();
    }
}
