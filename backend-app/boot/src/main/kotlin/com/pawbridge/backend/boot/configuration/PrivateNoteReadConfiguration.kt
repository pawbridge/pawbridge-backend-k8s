package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.NoteBlockReader
import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader
import com.pawbridge.backend.community.internal.note.application.ReadPrivateNote
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcPrivateNoteReader
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadController
import com.pawbridge.backend.community.internal.note.web.PrivateNoteReadExceptionHandler
import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class PrivateNoteReadConfiguration {
    @Bean
    fun privateNoteReader(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): PrivateNoteReader {
        return JdbcPrivateNoteReader(jdbc, transaction)
    }

    @Bean
    fun readPrivateNote(
        members: ContactMemberQuery,
        notes: PrivateNoteReader,
        blocks: NoteBlockReader,
        @Qualifier("readClock") clock: Clock,
    ): ReadPrivateNote {
        return ReadPrivateNote(members, notes, blocks, clock)
    }

    @Bean
    fun privateNoteReadController(notes: ReadPrivateNote): PrivateNoteReadController {
        return PrivateNoteReadController(notes)
    }

    @Bean
    fun privateNoteReadExceptionHandler(): PrivateNoteReadExceptionHandler {
        return PrivateNoteReadExceptionHandler()
    }
}
