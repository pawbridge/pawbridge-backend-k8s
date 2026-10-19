package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader;
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteNotificationReader;
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsController;
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsExceptionHandler;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class NoteNotificationsReadConfiguration {
    @Bean
    NoteNotificationReader noteNotificationReader(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                                @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcNoteNotificationReader(jdbc, transaction);
    }

    @Bean
    ReadNoteNotifications readNoteNotifications(ContactMemberQuery member, NoteNotificationReader notes,
                                              ContactMembersQuery members, @Qualifier("readClock") Clock clock) {
        return new ReadNoteNotifications(member, notes, members, clock);
    }

    @Bean
    NoteNotificationsController noteNotificationsController(ReadNoteNotifications notes) {
        return new NoteNotificationsController(notes);
    }

    @Bean
    NoteNotificationsExceptionHandler noteNotificationsExceptionHandler() {
        return new NoteNotificationsExceptionHandler();
    }
}
