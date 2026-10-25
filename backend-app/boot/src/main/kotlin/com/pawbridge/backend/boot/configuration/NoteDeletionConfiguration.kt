package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.DeletePrivateNote
import com.pawbridge.backend.community.internal.note.application.NoteChangeHints
import com.pawbridge.backend.community.internal.note.application.NoteDeletion
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteDeletion
import com.pawbridge.backend.community.internal.note.web.NoteDeletionController
import com.pawbridge.backend.community.internal.note.web.NoteDeletionExceptionHandler
import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class NoteDeletionConfiguration {
    @Bean
    fun noteDeletion(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        hints: NoteChangeHints,
    ): NoteDeletion {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        return JdbcNoteDeletion(jdbc, transaction, hints)
    }

    @Bean
    fun deletePrivateNote(
        members: ContactMemberQuery,
        mutation: NoteDeletion,
        @Qualifier("readClock") clock: Clock,
    ): DeletePrivateNote {
        return DeletePrivateNote(members, mutation, clock)
    }

    @Bean
    fun noteDeletionController(deletion: DeletePrivateNote): NoteDeletionController {
        return NoteDeletionController(deletion)
    }

    @Bean
    fun noteDeletionExceptionHandler(): NoteDeletionExceptionHandler {
        return NoteDeletionExceptionHandler()
    }
}
