package com.pawbridge.userservice.contact;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pawbridge.userservice.repository.UserRepository;
import com.pawbridge.userservice.exception.UserNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class ContactMemberDeletionTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    UserRepository users=mock(UserRepository.class);
    CommunityContactClient community=mock(CommunityContactClient.class);
    ContactMemberDeletion deletion=new ContactMemberDeletion(jdbc,community,users,new Transactions());

    @Test void givenUnknownMember_whenDelete_thenNoCommunityChange() {
        when(jdbc.query(anyString(),any(RowMapper.class),eq(7L))).thenReturn(List.of());
        assertThatThrownBy(() -> deletion.delete(7)).isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(community,users);
    }
    @Test void givenMailboxCleanupFails_whenDelete_thenDoNotDeleteMemberOrReactivate() {
        when(jdbc.query(anyString(),any(RowMapper.class),eq(7L))).thenReturn(List.of(7L));
        doThrow(new IllegalStateException("synthetic outage")).when(community).removeMailboxes(7);
        assertThatThrownBy(() -> deletion.delete(7)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(503));
        verify(jdbc).update("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?) ON CONFLICT DO NOTHING",7L);
        verifyNoInteractions(users);
        verify(jdbc,never()).update(contains("DELETE"),anyLong());
    }
    @Test void givenMember_whenDelete_thenCoordinatePurgeBeforeFinalAccountDeletion() {
        when(jdbc.query(anyString(),any(RowMapper.class),eq(7L))).thenReturn(List.of(7L));
        deletion.delete(7);
        var sequence=inOrder(jdbc,community,users);
        sequence.verify(jdbc).update("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?) ON CONFLICT DO NOTHING",7L);
        sequence.verify(community).removeMailboxes(7);
        sequence.verify(jdbc).update("DELETE FROM pawbridge_user.favorites WHERE user_id=?",7L);
        sequence.verify(jdbc).update("DELETE FROM pawbridge_user.refresh_tokens WHERE user_id=?",7L);
        sequence.verify(jdbc).update("DELETE FROM pawbridge_user.shelter_applications WHERE user_id=?",7L);
        sequence.verify(users).deleteById(7L);sequence.verify(users).flush();
    }
    private static class Transactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() {return new Object();}
        @Override protected void doBegin(Object tx,TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
