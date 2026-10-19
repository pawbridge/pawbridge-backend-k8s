package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.MarkPrivateNoteRead;
import com.pawbridge.backend.community.internal.note.application.NoteReadMutation;
import com.pawbridge.backend.community.internal.note.application.RequireActiveNoteStream;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteReadMutation;
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationController;
import com.pawbridge.backend.community.internal.note.web.NoteReadMutationExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.NoteStreamController;
import com.pawbridge.backend.community.internal.note.web.NoteStreamExceptionHandler;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class NoteReadStreamConfiguration {
    @Bean
    PrivateNoteStreamRegistry privateNoteStreamRegistry(@Qualifier("readClock") Clock clock) {
        return new PrivateNoteStreamRegistry(clock);
    }

    @Bean
    RequireActiveNoteStream requireActiveNoteStream(ContactMemberQuery members) {
        return new RequireActiveNoteStream(members);
    }

    @Bean
    NoteReadMutation noteReadMutation(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, PrivateNoteStreamRegistry streams) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcNoteReadMutation(jdbc, transaction, streams);
    }

    @Bean
    MarkPrivateNoteRead markPrivateNoteRead(ContactMemberQuery members, NoteReadMutation mutation,
                                           @Qualifier("readClock") Clock clock) {
        return new MarkPrivateNoteRead(members, mutation, clock);
    }

    @Bean NoteReadMutationController noteReadMutationController(MarkPrivateNoteRead read) {
        return new NoteReadMutationController(read);
    }
    @Bean NoteReadMutationExceptionHandler noteReadMutationExceptionHandler() { return new NoteReadMutationExceptionHandler(); }
    @Bean NoteStreamController noteStreamController(RequireActiveNoteStream active, PrivateNoteStreamRegistry streams) {
        return new NoteStreamController(active, streams);
    }
    @Bean NoteStreamExceptionHandler noteStreamExceptionHandler() { return new NoteStreamExceptionHandler(); }
}
