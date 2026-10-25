package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.NoteDeliveryHints
import com.pawbridge.backend.community.internal.note.application.NoteSendStore
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteSendStore
import com.pawbridge.backend.community.internal.note.web.NoteSendController
import com.pawbridge.backend.community.internal.note.web.NoteSendExceptionHandler
import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import java.util.UUID
import java.util.function.Supplier
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class NoteSendConfiguration {
    @Bean
    fun noteSendStore(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        hints: NoteDeliveryHints,
    ): NoteSendStore {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        return JdbcNoteSendStore(jdbc, transaction, hints)
    }

    @Bean
    fun sendPrivateNote(
        members: ContactMemberQuery,
        store: NoteSendStore,
        @Qualifier("readClock") clock: Clock,
    ): SendPrivateNote {
        return SendPrivateNote(members, store, clock, Supplier { UUID.randomUUID() })
    }

    @Bean
    fun noteSendController(sending: SendPrivateNote): NoteSendController {
        return NoteSendController(sending)
    }

    @Bean
    fun noteSendExceptionHandler(): NoteSendExceptionHandler {
        return NoteSendExceptionHandler()
    }
}
