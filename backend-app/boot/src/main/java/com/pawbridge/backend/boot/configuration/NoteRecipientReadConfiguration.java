package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockReader;
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController;
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMemberQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class NoteRecipientReadConfiguration {
    @Bean
    ContactMemberQuery contactMemberQuery(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                        @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcContactMemberQuery(jdbc, transaction);
    }

    @Bean
    NoteBlockReader noteBlockReader(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                   @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcNoteBlockReader(jdbc, transaction);
    }

    @Bean
    ReadNoteRecipient readNoteRecipient(ContactMemberQuery members, NoteBlockReader blocks) {
        return new ReadNoteRecipient(members, blocks);
    }

    @Bean
    NoteRecipientController noteRecipientController(ReadNoteRecipient recipients) {
        return new NoteRecipientController(recipients);
    }

    @Bean
    NoteRecipientExceptionHandler noteRecipientExceptionHandler() { return new NoteRecipientExceptionHandler(); }
}
