package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.NoteDeliveryHints;
import com.pawbridge.backend.community.internal.note.application.NoteSendStore;
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteSendStore;
import com.pawbridge.backend.community.internal.note.web.NoteSendController;
import com.pawbridge.backend.community.internal.note.web.NoteSendExceptionHandler;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class NoteSendConfiguration {
    @Bean
    NoteSendStore noteSendStore(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, NoteDeliveryHints hints) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcNoteSendStore(jdbc, transaction, hints);
    }
    @Bean
    SendPrivateNote sendPrivateNote(ContactMemberQuery members, NoteSendStore store,
            @Qualifier("readClock") Clock clock) {
        return new SendPrivateNote(members, store, clock, UUID::randomUUID);
    }
    @Bean
    NoteSendController noteSendController(SendPrivateNote sending) {
        return new NoteSendController(sending);
    }
    @Bean
    NoteSendExceptionHandler noteSendExceptionHandler() {
        return new NoteSendExceptionHandler();
    }
}
