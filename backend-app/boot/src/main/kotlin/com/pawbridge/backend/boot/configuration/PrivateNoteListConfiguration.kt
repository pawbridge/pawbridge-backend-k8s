package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.ListPrivateNotes
import com.pawbridge.backend.community.internal.note.application.NoteBlockReader
import com.pawbridge.backend.community.internal.note.application.PrivateNoteListReader
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcPrivateNoteListReader
import com.pawbridge.backend.community.internal.note.web.PrivateNoteListController
import com.pawbridge.backend.community.internal.note.web.PrivateNoteListExceptionHandler
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMembersQuery
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class PrivateNoteListConfiguration {
    @Bean
    fun privateNoteListReader(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): PrivateNoteListReader {
        return JdbcPrivateNoteListReader(jdbc, transaction)
    }

    @Bean
    fun listPrivateNotes(
        member: ContactMemberQuery,
        notes: PrivateNoteListReader,
        members: ContactMembersQuery,
        blocks: NoteBlockReader,
        @Qualifier("readClock") clock: Clock,
    ): ListPrivateNotes {
        return ListPrivateNotes(member, notes, members, blocks, clock)
    }

    @Bean
    fun privateNoteListController(notes: ListPrivateNotes): PrivateNoteListController {
        return PrivateNoteListController(notes)
    }

    @Bean
    fun privateNoteListExceptionHandler(): PrivateNoteListExceptionHandler {
        return PrivateNoteListExceptionHandler()
    }
}
