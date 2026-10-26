package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.comment.application.CommentDeletion
import com.pawbridge.backend.community.internal.comment.application.CommentModification
import com.pawbridge.backend.community.internal.comment.application.CommentReader
import com.pawbridge.backend.community.internal.comment.application.CommentWriter
import com.pawbridge.backend.community.internal.comment.application.CreateComment
import com.pawbridge.backend.community.internal.comment.application.UpdateComment
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentDeletion
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentWriter
import com.pawbridge.backend.community.internal.comment.web.CommentCreateController
import com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteController
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteExceptionHandler
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateController
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateExceptionHandler
import com.pawbridge.backend.community.internal.post.application.PostReader
import com.pawbridge.backend.user.api.UserNicknameQuery
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@Configuration(proxyBeanMethods = false)
class CommentWriteConfiguration {
    @Bean
    fun commentWriter(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransaction") transaction: TransactionTemplate,
    ): JdbcCommentWriter {
        return JdbcCommentWriter(jdbc, transaction)
    }

    @Bean
    fun createComment(posts: PostReader, users: UserNicknameQuery, comments: CommentWriter): CreateComment {
        return CreateComment(posts, users, comments)
    }

    @Bean
    fun commentCreateController(createComment: CreateComment): CommentCreateController {
        return CommentCreateController(createComment)
    }

    @Bean
    fun commentCreateExceptionHandler(): CommentCreateExceptionHandler {
        return CommentCreateExceptionHandler()
    }

    @Bean
    fun commentDeletion(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransaction") transaction: TransactionTemplate,
    ): CommentDeletion {
        return JdbcCommentDeletion(jdbc, transaction)
    }

    @Bean
    fun commentDeleteController(deletion: CommentDeletion): CommentDeleteController {
        return CommentDeleteController(deletion)
    }

    @Bean
    fun commentDeleteExceptionHandler(): CommentDeleteExceptionHandler {
        return CommentDeleteExceptionHandler()
    }

    @Bean
    fun updateComment(
        comments: CommentReader,
        users: UserNicknameQuery,
        modification: CommentModification,
    ): UpdateComment {
        return UpdateComment(comments, users, modification)
    }

    @Bean
    fun commentUpdateController(updateComment: UpdateComment): CommentUpdateController {
        return CommentUpdateController(updateComment)
    }

    @Bean
    fun commentUpdateExceptionHandler(): CommentUpdateExceptionHandler {
        return CommentUpdateExceptionHandler()
    }
}
