package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.MarkPrivateNoteRead
import com.pawbridge.backend.community.internal.note.application.NoteReadMutation
import com.pawbridge.backend.community.internal.note.application.RequireActiveNoteStream
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteReadMutation
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationController
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationExceptionHandler
import com.pawbridge.backend.community.internal.note.web.NoteStreamController
import com.pawbridge.backend.community.internal.note.web.NoteStreamExceptionHandler
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry
import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class NoteReadStreamConfiguration {
    @Bean
    fun privateNoteStreamRegistry(@Qualifier("readClock") clock: Clock): PrivateNoteStreamRegistry {
        return PrivateNoteStreamRegistry(clock)
    }

    @Bean
    fun requireActiveNoteStream(members: ContactMemberQuery): RequireActiveNoteStream {
        return RequireActiveNoteStream(members)
    }

    @Bean
    fun noteReadMutation(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        streams: PrivateNoteStreamRegistry,
    ): NoteReadMutation {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        return JdbcNoteReadMutation(jdbc, transaction, streams)
    }

    @Bean
    fun markPrivateNoteRead(
        members: ContactMemberQuery,
        mutation: NoteReadMutation,
        @Qualifier("readClock") clock: Clock,
    ): MarkPrivateNoteRead {
        return MarkPrivateNoteRead(members, mutation, clock)
    }

    @Bean
    fun noteReadMutationController(read: MarkPrivateNoteRead): NoteReadMutationController {
        return NoteReadMutationController(read)
    }

    @Bean
    fun noteReadMutationExceptionHandler(): NoteReadMutationExceptionHandler {
        return NoteReadMutationExceptionHandler()
    }

    @Bean
    fun noteStreamController(
        active: RequireActiveNoteStream,
        streams: PrivateNoteStreamRegistry,
    ): NoteStreamController {
        return NoteStreamController(active, streams)
    }

    @Bean
    fun noteStreamExceptionHandler(): NoteStreamExceptionHandler {
        return NoteStreamExceptionHandler()
    }
}
