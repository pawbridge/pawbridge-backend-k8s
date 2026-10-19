package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.ListNoteBlocks;
import com.pawbridge.backend.community.internal.note.application.NoteBlockListReader;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteBlockListReader;
import com.pawbridge.backend.community.internal.note.web.NoteBlockListController;
import com.pawbridge.backend.community.internal.note.web.NoteBlockListExceptionHandler;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.api.ContactMembersQuery;
import com.pawbridge.backend.user.internal.contact.infrastructure.JdbcContactMembersQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class NoteBlockListReadConfiguration {
    @Bean
    ContactMembersQuery contactMembersQuery(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                          @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcContactMembersQuery(jdbc, transaction);
    }

    @Bean
    NoteBlockListReader noteBlockListReader(@Qualifier("readJdbc") JdbcTemplate jdbc,
                                          @Qualifier("readOnlyTransaction") TransactionTemplate transaction) {
        return new JdbcNoteBlockListReader(jdbc, transaction);
    }

    @Bean
    ListNoteBlocks listNoteBlocks(ContactMemberQuery member, NoteBlockListReader blocks, ContactMembersQuery members) {
        return new ListNoteBlocks(member, blocks, members);
    }

    @Bean
    NoteBlockListController noteBlockListController(ListNoteBlocks blocks) { return new NoteBlockListController(blocks); }

    @Bean
    NoteBlockListExceptionHandler noteBlockListExceptionHandler() { return new NoteBlockListExceptionHandler(); }
}
