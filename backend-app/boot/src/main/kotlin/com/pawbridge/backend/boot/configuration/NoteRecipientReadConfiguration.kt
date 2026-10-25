package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.NoteBlockReader
import com.pawbridge.backend.community.internal.note.application.ReadNoteRecipient
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockReader
import com.pawbridge.backend.community.internal.note.web.NoteRecipientController
import com.pawbridge.backend.community.internal.note.web.NoteRecipientExceptionHandler
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMemberQuery
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class NoteRecipientReadConfiguration {
    @Bean
    fun contactMemberQuery(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): ContactMemberQuery {
        return JdbcContactMemberQuery(jdbc, transaction)
    }

    @Bean
    fun noteBlockReader(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): NoteBlockReader {
        return JdbcNoteBlockReader(jdbc, transaction)
    }

    @Bean
    fun readNoteRecipient(members: ContactMemberQuery, blocks: NoteBlockReader): ReadNoteRecipient {
        return ReadNoteRecipient(members, blocks)
    }

    @Bean
    fun noteRecipientController(recipients: ReadNoteRecipient): NoteRecipientController {
        return NoteRecipientController(recipients)
    }

    @Bean
    fun noteRecipientExceptionHandler(): NoteRecipientExceptionHandler {
        return NoteRecipientExceptionHandler()
    }
}
