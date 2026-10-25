package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader
import com.pawbridge.backend.community.internal.note.application.ReadNoteNotifications
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteNotificationReader
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsController
import com.pawbridge.backend.community.internal.note.web.NoteNotificationsExceptionHandler
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMembersQuery
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class NoteNotificationsReadConfiguration {
    @Bean
    fun noteNotificationReader(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): NoteNotificationReader {
        return JdbcNoteNotificationReader(jdbc, transaction)
    }

    @Bean
    fun readNoteNotifications(
        member: ContactMemberQuery,
        notes: NoteNotificationReader,
        members: ContactMembersQuery,
        @Qualifier("readClock") clock: Clock,
    ): ReadNoteNotifications {
        return ReadNoteNotifications(member, notes, members, clock)
    }

    @Bean
    fun noteNotificationsController(notes: ReadNoteNotifications): NoteNotificationsController {
        return NoteNotificationsController(notes)
    }

    @Bean
    fun noteNotificationsExceptionHandler(): NoteNotificationsExceptionHandler {
        return NoteNotificationsExceptionHandler()
    }
}
