package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.NoteBlockStatusReader
import com.pawbridge.backend.community.internal.note.application.ReadBlockedAuthors
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockStatusReader
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusController
import com.pawbridge.backend.community.internal.note.web.NoteBlockStatusExceptionHandler
import com.pawbridge.backend.user.api.ContactMemberQuery
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class NoteBlockStatusReadConfiguration {
    @Bean
    fun noteBlockStatusReader(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): NoteBlockStatusReader {
        return JdbcNoteBlockStatusReader(jdbc, transaction)
    }

    @Bean
    fun readBlockedAuthors(members: ContactMemberQuery, blocks: NoteBlockStatusReader): ReadBlockedAuthors {
        return ReadBlockedAuthors(members, blocks)
    }

    @Bean
    fun noteBlockStatusController(authors: ReadBlockedAuthors): NoteBlockStatusController {
        return NoteBlockStatusController(authors)
    }

    @Bean
    fun noteBlockStatusExceptionHandler(): NoteBlockStatusExceptionHandler {
        return NoteBlockStatusExceptionHandler()
    }
}
