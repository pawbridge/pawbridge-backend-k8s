package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader;
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockStatusReader;
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class NoteBlockStatusReadConfiguration {
    @Bean
    NoteBlockStatusReader noteBlockStatusReader(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                               @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcNoteBlockStatusReader(jdbc, transaction);
    }

    @Bean
    ReadBlockedAuthors readBlockedAuthors(ContactMemberQuery members, NoteBlockStatusReader blocks) {
        return new ReadBlockedAuthors(members, blocks);
    }

    @Bean
    NoteBlockStatusController noteBlockStatusController(ReadBlockedAuthors authors) {
        return new NoteBlockStatusController(authors);
    }

    @Bean
    NoteBlockStatusExceptionHandler noteBlockStatusExceptionHandler() { return new NoteBlockStatusExceptionHandler(); }
}
