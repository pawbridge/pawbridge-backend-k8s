package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.NoteChangeHints;
import com.pawbridge.backend.community.internal.note.application.NoteFavoriteMutation;
import com.pawbridge.backend.community.internal.note.application.SetPrivateNoteFavorite;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteFavoriteMutation;
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteController;
import com.pawbridge.backend.community.internal.note.web.NoteFavoriteExceptionHandler;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class NoteFavoriteConfiguration {
    @Bean
    NoteFavoriteMutation noteFavoriteMutation(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, NoteChangeHints hints) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcNoteFavoriteMutation(jdbc, transaction, hints);
    }

    @Bean
    SetPrivateNoteFavorite setPrivateNoteFavorite(ContactMemberQuery members, NoteFavoriteMutation mutation,
                                                 @Qualifier("readClock") Clock clock) {
        return new SetPrivateNoteFavorite(members, mutation, clock);
    }

    @Bean
    NoteFavoriteController noteFavoriteController(SetPrivateNoteFavorite favorite) {
        return new NoteFavoriteController(favorite);
    }

    @Bean
    NoteFavoriteExceptionHandler noteFavoriteExceptionHandler() {
        return new NoteFavoriteExceptionHandler();
    }
}
