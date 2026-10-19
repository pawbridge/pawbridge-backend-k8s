package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.NoteBlockRemoval;
import com.pawbridge.backend.community.internal.note.application.RemoveNoteBlock;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockRemoval;
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveExceptionHandler;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class NoteBlockRemoveConfiguration {
    @Bean
    NoteBlockRemoval noteBlockRemoval(@Qualifier("writeJdbc") JdbcTemplate jdbc,
                                     @Qualifier("writeTransactionManager") JdbcTransactionManager manager) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcNoteBlockRemoval(jdbc, transaction);
    }

    @Bean
    RemoveNoteBlock removeNoteBlock(ContactMemberQuery members, NoteBlockRemoval removal) {
        return new RemoveNoteBlock(members, removal);
    }

    @Bean
    NoteBlockRemoveController noteBlockRemoveController(RemoveNoteBlock removal) {
        return new NoteBlockRemoveController(removal);
    }

    @Bean
    NoteBlockRemoveExceptionHandler noteBlockRemoveExceptionHandler() {
        return new NoteBlockRemoveExceptionHandler();
    }
}
