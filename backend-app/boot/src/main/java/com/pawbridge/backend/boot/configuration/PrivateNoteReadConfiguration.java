package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader;
import com.pawbridge.backend.community.internal.note.application.ReadPrivateNote;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcPrivateNoteReader;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadController;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadExceptionHandler;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class PrivateNoteReadConfiguration {
    @Bean
    PrivateNoteReader privateNoteReader(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                      @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcPrivateNoteReader(jdbc, transaction);
    }

    @Bean
    ReadPrivateNote readPrivateNote(ContactMemberQuery members, PrivateNoteReader notes,
                                  NoteBlockReader blocks, @Qualifier("readClock") Clock clock) {
        return new ReadPrivateNote(members, notes, blocks, clock);
    }

    @Bean
    PrivateNoteReadController privateNoteReadController(ReadPrivateNote notes) {
        return new PrivateNoteReadController(notes);
    }

    @Bean
    PrivateNoteReadExceptionHandler privateNoteReadExceptionHandler() {
        return new PrivateNoteReadExceptionHandler();
    }
}
