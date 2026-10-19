package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.comment.application.CommentWriter;
import com.pawbridge.backend.community.internal.comment.application.CommentDeletion;
import com.pawbridge.backend.community.internal.comment.application.CreateComment;
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentWriter;
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentDeletion;
import com.pawbridge.backend.community.internal.comment.web.CommentCreateController;
import com.pawbridge.backend.community.internal.comment.web.CommentCreateExceptionHandler;
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteController;
import com.pawbridge.backend.community.internal.comment.web.CommentDeleteExceptionHandler;
import com.pawbridge.backend.community.internal.post.application.PostReader;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class CommentWriteConfiguration {
    @Bean
    JdbcCommentWriter commentWriter(@Qualifier("writeJdbc") JdbcTemplate jdbc,
                                    @Qualifier("writeTransaction") TransactionTemplate transaction) {
        return new JdbcCommentWriter(jdbc, transaction);
    }

    @Bean
    CreateComment createComment(PostReader posts, UserNicknameQuery users, CommentWriter comments) {
        return new CreateComment(posts, users, comments);
    }

    @Bean
    CommentCreateController commentCreateController(CreateComment createComment) {
        return new CommentCreateController(createComment);
    }

    @Bean
    CommentCreateExceptionHandler commentCreateExceptionHandler() { return new CommentCreateExceptionHandler(); }

    @Bean
    CommentDeletion commentDeletion(@Qualifier("writeJdbc") JdbcTemplate jdbc,
                                    @Qualifier("writeTransaction") TransactionTemplate transaction) {
        return new JdbcCommentDeletion(jdbc, transaction);
    }

    @Bean
    CommentDeleteController commentDeleteController(CommentDeletion deletion) { return new CommentDeleteController(deletion); }

    @Bean
    CommentDeleteExceptionHandler commentDeleteExceptionHandler() { return new CommentDeleteExceptionHandler(); }
}
