package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockListReader
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler
import com.pawbridge.backend.user.api.ContactMemberQuery
import com.pawbridge.backend.user.api.ContactMembersQuery
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMembersQuery
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class NoteBlockListReadConfiguration {
    @Bean
    fun contactMembersQuery(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): ContactMembersQuery {
        return JdbcContactMembersQuery(jdbc, transaction)
    }

    @Bean
    fun noteBlockListReader(
        @Qualifier("readJdbc") jdbc: JdbcTemplate,
        @Qualifier("readOnlyTransaction") transaction: TransactionTemplate,
    ): NoteBlockListReader {
        return JdbcNoteBlockListReader(jdbc, transaction)
    }

    @Bean
    fun listNoteBlocks(
        member: ContactMemberQuery,
        blocks: NoteBlockListReader,
        members: ContactMembersQuery,
    ): ListNoteBlocks {
        return ListNoteBlocks(member, blocks, members)
    }

    @Bean
    fun noteBlockListController(blocks: ListNoteBlocks): NoteBlockListController {
        return NoteBlockListController(blocks)
    }

    @Bean
    fun noteBlockListExceptionHandler(): NoteBlockListExceptionHandler {
        return NoteBlockListExceptionHandler()
    }
}
