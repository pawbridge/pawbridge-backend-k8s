package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.CreateNoteBlock
import com.pawbridge.backend.community.internal.note.application.NoteBlockCreation
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockCreation
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateController
import com.pawbridge.backend.community.internal.note.web.NoteBlockCreateExceptionHandler
import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class NoteBlockCreateConfiguration {
    @Bean
    fun noteBlockCreation(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
    ): NoteBlockCreation {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        return JdbcNoteBlockCreation(jdbc, transaction)
    }

    @Bean
    fun createNoteBlock(
        members: ContactMemberQuery,
        creation: NoteBlockCreation,
        @Qualifier("readClock") clock: Clock,
    ): CreateNoteBlock {
        return CreateNoteBlock(members, creation, clock)
    }

    @Bean
    fun noteBlockCreateController(creation: CreateNoteBlock): NoteBlockCreateController {
        return NoteBlockCreateController(creation)
    }

    @Bean
    fun noteBlockCreateExceptionHandler(): NoteBlockCreateExceptionHandler {
        return NoteBlockCreateExceptionHandler()
    }
}
