package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.NoteBlockRemoval
import com.pawbridge.backend.community.internal.note.application.RemoveNoteBlock
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockRemoval
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveController
import com.pawbridge.backend.community.internal.note.web.NoteBlockRemoveExceptionHandler
import com.pawbridge.backend.user.api.ContactMemberQuery
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class NoteBlockRemoveConfiguration {
    @Bean
    fun noteBlockRemoval(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
    ): NoteBlockRemoval {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        return JdbcNoteBlockRemoval(jdbc, transaction)
    }

    @Bean
    fun removeNoteBlock(members: ContactMemberQuery, removal: NoteBlockRemoval): RemoveNoteBlock {
        return RemoveNoteBlock(members, removal)
    }

    @Bean
    fun noteBlockRemoveController(removal: RemoveNoteBlock): NoteBlockRemoveController {
        return NoteBlockRemoveController(removal)
    }

    @Bean
    fun noteBlockRemoveExceptionHandler(): NoteBlockRemoveExceptionHandler {
        return NoteBlockRemoveExceptionHandler()
    }
}
