package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.ListPrivateNotes;
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader;
import com.pawbridge.backend.community.internal.note.application.PrivateNoteListReader;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcPrivateNoteListReader;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteListController;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteListExceptionHandler;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class PrivateNoteListConfiguration {
    @Bean
    PrivateNoteListReader privateNoteListReader(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                              @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcPrivateNoteListReader(jdbc, transaction);
    }

    @Bean
    ListPrivateNotes listPrivateNotes(ContactMemberQuery member, PrivateNoteListReader notes,
                                     ContactMembersQuery members, NoteBlockReader blocks,
                                     @Qualifier("readClock") Clock clock) {
        return new ListPrivateNotes(member, notes, members, blocks, clock);
    }

    @Bean
    PrivateNoteListController privateNoteListController(ListPrivateNotes notes) {
        return new PrivateNoteListController(notes);
    }

    @Bean
    PrivateNoteListExceptionHandler privateNoteListExceptionHandler() {
        return new PrivateNoteListExceptionHandler();
    }
}
