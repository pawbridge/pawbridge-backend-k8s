package com.pawbridge.backend.boot.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.community.api.ContactMemberCleanup;
import com.pawbridge.backend.community.internal.note.application.ContactMemberCleanupStore;
import com.pawbridge.backend.community.internal.note.web.PrivateNoteStreamRegistry;
import com.pawbridge.backend.user.api.ContactMemberQuery;
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class MemberCleanupConfigurationTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final JdbcTransactionManager manager=mock(JdbcTransactionManager.class);
    private final ContactMemberQuery members=mock(ContactMemberQuery.class);
    private final PrivateNoteStreamRegistry streams=mock(PrivateNoteStreamRegistry.class);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(MemberCleanupConfiguration.class)
                .withBean("writeJdbc",JdbcTemplate.class,()->jdbc)
                .withBean("writeTransactionManager",JdbcTransactionManager.class,()->manager)
                .withBean(ContactMemberQuery.class,()->members)
                .withBean(PrivateNoteStreamRegistry.class,()->streams);
    }

    @ParameterizedTest @ValueSource(strings={"absent","false","canonical-false-over-alias-true"})
    void given__disabled_or_unspecified_chat__when__assembling__then__reuse_writer_and_registry_for_both_transactions_and_ordered_callbacks(String flag) {
        var context=runner();
        if (flag.equals("false")) context=context.withPropertyValues("pawbridge.chat.enabled=false");
        if (flag.equals("canonical-false-over-alias-true")) context=context.withPropertyValues("pawbridge.chat.enabled=false","MEMBER_CHAT_ENABLED=true");
        context.run(application->{
            assertThat(application).hasNotFailed().hasSingleBean(ContactMemberCleanup.class).hasSingleBean(ContactMemberCleanupStore.class).hasSingleBean(UserDeletion.class);
            var cleanup=application.getBean(ContactMemberCleanup.class);
            var store=application.getBean(ContactMemberCleanupStore.class);
            var deletion=application.getBean(UserDeletion.class);
            assertThat(ReflectionTestUtils.getField(cleanup,"members")).isSameAs(members);
            assertThat(ReflectionTestUtils.getField(cleanup,"store")).isSameAs(store);
            assertThat(ReflectionTestUtils.getField(store,"jdbc")).isSameAs(jdbc);
            assertThat(ReflectionTestUtils.getField(deletion,"jdbc")).isSameAs(jdbc);
            var communityTx=(TransactionTemplate)ReflectionTestUtils.getField(store,"transaction");
            var userTx=(TransactionTemplate)ReflectionTestUtils.getField(deletion,"transactions");
            assertThat(communityTx).isNotSameAs(userTx);
            for(var transaction:java.util.List.of(communityTx,userTx)) {
                assertThat(transaction.getTransactionManager()).isSameAs(manager);
                assertThat(transaction.getTimeout()).isEqualTo(15);
                assertThat(transaction.isReadOnly()).isFalse();
            }
            // Only the injected callback is exercised here; this is not an actual commit or SSE test.
            clearInvocations(streams);
            ((LongConsumer)ReflectionTestUtils.getField(store,"committedCleanup")).accept(8);
            var sequence=inOrder(streams);sequence.verify(streams).closeMember(8);sequence.verify(streams).resyncAll();
            verifyNoMoreInteractions(streams);verifyNoInteractions(members);
        });
    }

    @ParameterizedTest @ValueSource(strings={"pawbridge.chat.enabled=true","MEMBER_CHAT_ENABLED=true"})
    void given__enabled_chat_without_redis_withdrawal_wiring__when__assembling__then__fail_before_any_cleanup_transaction(String property) {
        runner().withPropertyValues(property).run(context->{
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("Member cleanup with chat enabled requires verified Redis withdrawal wiring");
            verify(manager,never()).getTransaction(any());verifyNoInteractions(members);
        });
    }

    @Test
    void given__public_community_cleanup__when__wiring_user_deletion__then__consume_only_that_api_without_a_module_cycle_or_outer_transaction() {
        var cleanup=mock(ContactMemberCleanup.class);
        var deletion=new MemberCleanupConfiguration(false).userDeletion(jdbc,manager,cleanup);
        ((LongConsumer)ReflectionTestUtils.getField(deletion,"communityCleanup")).accept(Long.MAX_VALUE);
        verify(cleanup).remove(Long.MAX_VALUE);verifyNoMoreInteractions(cleanup);verifyNoInteractions(jdbc,manager);
    }
}
