package com.pawbridge.backend.store.internal.wishlist.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcWishlistIdentityTransactionTest {
    private static final String DELETE = "DELETE FROM pawbridge_store.wishlists WHERE id=:id AND user_id=:member";
    private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final Clock clock = mock(Clock.class);
    private final SimpleTransactionStatus status = new SimpleTransactionStatus();
    private final JdbcWishlistCommands commands = new JdbcWishlistCommands(jdbc, new TransactionTemplate(manager), clock);

    @BeforeEach void prepare() {
        when(manager.getTransaction(any())).thenReturn(status);
        when(jdbc.update(DELETE, Map.of("member",7L,"id",33L))).thenReturn(1);
    }

    @Test void given__identity_transaction_begin_failure__when__removing__then__propagate_without_sql() {
        var failure = new IllegalStateException("synthetic begin");
        doThrow(failure).when(manager).getTransaction(any());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> commands.removeById(7,33)));
        verifyNoInteractions(jdbc, clock); verify(manager,never()).commit(any()); verify(manager,never()).rollback(any());
    }

    @Test void given__identity_delete_failure__when__removing__then__rollback_and_propagate_without_retry() {
        var failure = new DataAccessResourceFailureException("synthetic delete");
        doThrow(failure).when(jdbc).update(DELETE, Map.of("member",7L,"id",33L));
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () -> commands.removeById(7,33)));
        verify(manager).rollback(status); verify(manager,never()).commit(any());
        verify(jdbc).update(DELETE, Map.of("member",7L,"id",33L)); verifyNoMoreInteractions(jdbc); verifyNoInteractions(clock);
    }

    @Test void given__identity_commit_failure__when__removing__then__propagate_instead_of_claiming_success() {
        var failure = new IllegalStateException("synthetic commit"); doThrow(failure).when(manager).commit(status);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> commands.removeById(7,33)));
        verify(manager).commit(status); verify(jdbc).update(DELETE, Map.of("member",7L,"id",33L));
        verifyNoMoreInteractions(jdbc); verifyNoInteractions(clock);
    }

    @Test void given__identity_rollback_failure__when__delete_fails__then__propagate_manager_failure() {
        var deleteFailure = new DataAccessResourceFailureException("synthetic delete");
        var rollbackFailure = new IllegalStateException("synthetic rollback");
        doThrow(deleteFailure).when(jdbc).update(DELETE, Map.of("member",7L,"id",33L));
        doThrow(rollbackFailure).when(manager).rollback(status);
        assertSame(rollbackFailure, assertThrows(IllegalStateException.class, () -> commands.removeById(7,33)));
        verify(manager).rollback(status); verify(manager,never()).commit(any());
        verify(jdbc).update(DELETE, Map.of("member",7L,"id",33L)); verifyNoMoreInteractions(jdbc); verifyNoInteractions(clock);
    }
}
