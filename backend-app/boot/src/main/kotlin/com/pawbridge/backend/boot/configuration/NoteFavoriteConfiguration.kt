package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.note.application.NoteChangeHints
import com.pawbridge.backend.community.internal.note.application.NoteFavoriteMutation
import com.pawbridge.backend.community.internal.note.application.SetPrivateNoteFavorite
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteFavoriteMutation
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteController
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteExceptionHandler
import com.pawbridge.backend.user.api.ContactMemberQuery
import java.time.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class NoteFavoriteConfiguration {
    @Bean
    fun noteFavoriteMutation(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        hints: NoteChangeHints,
    ): NoteFavoriteMutation {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        return JdbcNoteFavoriteMutation(jdbc, transaction, hints)
    }

    @Bean
    fun setPrivateNoteFavorite(
        members: ContactMemberQuery,
        mutation: NoteFavoriteMutation,
        @Qualifier("readClock") clock: Clock,
    ): SetPrivateNoteFavorite {
        return SetPrivateNoteFavorite(members, mutation, clock)
    }

    @Bean
    fun noteFavoriteController(favorite: SetPrivateNoteFavorite): NoteFavoriteController {
        return NoteFavoriteController(favorite)
    }

    @Bean
    fun noteFavoriteExceptionHandler(): NoteFavoriteExceptionHandler {
        return NoteFavoriteExceptionHandler()
    }
}
