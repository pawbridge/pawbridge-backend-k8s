package com.pawbridge.backend.user.internal.session.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException;
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException.Reason;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore.RetiredToken;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore.Rotation;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcAuthSessionsTest {
    private static final String PRESENTED = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String PRESENTED_HASH = RefreshTokens.hash(PRESENTED);

    @ParameterizedTest
    @NullAndEmptySource
    void given__no_refresh_cookie__when__refreshing__then__reject_as_missing_without_a_transaction(String token) {
        var f = new Fixture();

        var failure = assertThrows(AuthSessionRejectedException.class, () -> f.sessions.refresh(token));

        assertEquals(Reason.MISSING, failure.reason());
        assertEquals(List.of(), f.tx.events);
        verifyNoInteractions(f.store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "eyJhbGciOiJIUzI1NiJ9.e30.signature-of-an-old-jwt-refresh-token",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+"})
    void given__a_token_this_server_cannot_have_issued__when__refreshing__then__reject_without_touching_the_database(String token) {
        var f = new Fixture();

        var failure = assertThrows(AuthSessionRejectedException.class, () -> f.sessions.refresh(token));

        assertEquals(Reason.INVALID, failure.reason());
        verifyNoInteractions(f.store);
        assertEquals(List.of(), f.tx.events);
    }

    @Test
    void given__an_active_session__when__refreshing__then__rotate_once_by_hash_and_return_current_claims_with_a_new_token() {
        var f = new Fixture();
        when(f.store.rotate(anyString(), anyString())).thenReturn(Optional.of(new Rotation(31, 7, 1_209_600)));

        var result = assertInstanceOf(AuthSessions.Rotated.class, f.sessions.refresh(PRESENTED));

        String next = result.refreshToken().value();
        assertNotEquals(PRESENTED, next);
        assertTrue(RefreshTokens.wellFormed(next));
        assertEquals(1_209_600, result.refreshToken().maxAgeSeconds());
        assertEquals("synthetic-access", result.accessToken());
        verify(f.store).rotate(PRESENTED_HASH, RefreshTokens.hash(next));
        verify(f.store, never()).findRetired(anyString());
        verify(f.tokens).access(new JwtSessionTokens.UserClaims(7, "synthetic@fixture.invalid", "Synthetic", "ROLE_USER", "CARE"));
        assertEquals(List.of("begin", "commit"), f.tx.events);
        assertArrayEquals(new Object[] {7L}, f.jdbc.userArgs);
    }

    @Test
    void given__the_latest_retired_token_within_grace_whose_successor_was_never_used__when__refreshing__then__rotate_again_and_set_a_cookie() {
        var f = new Fixture();
        when(f.store.rotate(anyString(), anyString())).thenReturn(Optional.empty());
        when(f.store.findRetired(PRESENTED_HASH)).thenReturn(Optional.of(new RetiredToken(31, 7, true, true, true)));
        when(f.store.rotateUnusedSuccessor(eq(31L), eq(PRESENTED_HASH), anyString()))
                .thenReturn(Optional.of(new Rotation(31, 7, 1_209_000)));

        var result = assertInstanceOf(AuthSessions.Rotated.class, f.sessions.refresh(PRESENTED));

        assertEquals(1_209_000, result.refreshToken().maxAgeSeconds());
        verify(f.store).rotateUnusedSuccessor(31, PRESENTED_HASH, RefreshTokens.hash(result.refreshToken().value()));
        verify(f.store, never()).revoke(anyLong());
        assertEquals(List.of("begin", "commit"), f.tx.events);
    }

    @Test
    void given__a_concurrent_request_already_moved_the_session_on__when__refreshing_within_grace__then__return_access_only_without_a_new_token() {
        var f = new Fixture();
        when(f.store.rotate(anyString(), anyString())).thenReturn(Optional.empty());
        when(f.store.findRetired(PRESENTED_HASH)).thenReturn(Optional.of(new RetiredToken(31, 7, true, true, true)));
        when(f.store.rotateUnusedSuccessor(anyLong(), anyString(), anyString())).thenReturn(Optional.empty());

        var result = assertInstanceOf(AuthSessions.AccessOnly.class, f.sessions.refresh(PRESENTED));

        assertEquals("synthetic-access", result.accessToken());
        verify(f.store, never()).revoke(anyLong());
    }

    @Test
    void given__an_older_generation_retired_within_grace__when__refreshing__then__return_access_only_without_rotating_again() {
        var f = new Fixture();
        when(f.store.rotate(anyString(), anyString())).thenReturn(Optional.empty());
        when(f.store.findRetired(PRESENTED_HASH)).thenReturn(Optional.of(new RetiredToken(31, 7, true, false, true)));

        assertInstanceOf(AuthSessions.AccessOnly.class, f.sessions.refresh(PRESENTED));

        verify(f.store, never()).rotateUnusedSuccessor(anyLong(), anyString(), anyString());
        verify(f.store, never()).revoke(anyLong());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void given__a_token_of_any_generation_retired_before_the_grace_window__when__refreshing__then__revoke_only_that_session_and_reject_after_commit(
            boolean latestRetirement) {
        var f = new Fixture();
        when(f.store.rotate(anyString(), anyString())).thenReturn(Optional.empty());
        when(f.store.findRetired(PRESENTED_HASH))
                .thenReturn(Optional.of(new RetiredToken(31, 7, false, latestRetirement, true)));

        var failure = assertThrows(AuthSessionRejectedException.class, () -> f.sessions.refresh(PRESENTED));

        assertEquals(Reason.REUSED, failure.reason());
        assertEquals("유효하지 않은 토큰입니다.", failure.getMessage());
        verify(f.store).revoke(31);
        verify(f.store, never()).revokeAll(anyLong());
        verify(f.store, never()).rotateUnusedSuccessor(anyLong(), anyString(), anyString());
        assertEquals(List.of("begin", "commit"), f.tx.events);
        verifyNoInteractions(f.tokens);
    }

    @Test
    void given__a_token_retired_moments_ago_on_a_session_that_is_no_longer_active__when__refreshing__then__reject_without_revoking() {
        var f = new Fixture();
        when(f.store.rotate(anyString(), anyString())).thenReturn(Optional.empty());
        when(f.store.findRetired(PRESENTED_HASH)).thenReturn(Optional.of(new RetiredToken(31, 7, true, true, false)));

        var failure = assertThrows(AuthSessionRejectedException.class, () -> f.sessions.refresh(PRESENTED));

        assertEquals(Reason.INVALID, failure.reason());
        verify(f.store, never()).revoke(anyLong());
        verify(f.store, never()).rotateUnusedSuccessor(anyLong(), anyString(), anyString());
        verifyNoInteractions(f.tokens);
    }

    /** Idle-expired, absolutely expired, revoked and unknown tokens all fail the rotation WHERE clause. */
    @Test
    void given__an_expired_revoked_or_unknown_token__when__refreshing__then__reject_as_invalid_without_writes() {
        var f = new Fixture();
        when(f.store.rotate(anyString(), anyString())).thenReturn(Optional.empty());
        when(f.store.findRetired(PRESENTED_HASH)).thenReturn(Optional.empty());

        var failure = assertThrows(AuthSessionRejectedException.class, () -> f.sessions.refresh(PRESENTED));

        assertEquals(Reason.INVALID, failure.reason());
        verify(f.store, never()).revoke(anyLong());
        verifyNoInteractions(f.tokens);
        assertEquals(List.of("begin", "commit"), f.tx.events);
    }

    @Test
    void given__the_user_disappears_after_rotation__when__refreshing__then__roll_back_the_rotation_and_reject() {
        var f = new Fixture();
        f.jdbc.userPresent = false;
        when(f.store.rotate(anyString(), anyString())).thenReturn(Optional.of(new Rotation(31, 7, 10)));

        var failure = assertThrows(AuthSessionRejectedException.class, () -> f.sessions.refresh(PRESENTED));

        assertEquals(Reason.INVALID, failure.reason());
        assertEquals(List.of("begin", "rollback"), f.tx.events);
    }

    @Test
    void given__the_same_cookie_twice__when__logging_out__then__revoke_by_hash_each_time_without_failing() {
        var f = new Fixture();

        f.sessions.logout(PRESENTED);
        f.sessions.logout(PRESENTED);

        verify(f.store, times(2)).revokeByTokenHash(PRESENTED_HASH);
        assertEquals(List.of("begin", "commit", "begin", "commit"), f.tx.events);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-refresh-token"})
    void given__no_or_malformed_cookie__when__logging_out__then__do_nothing(String token) {
        var f = new Fixture();

        f.sessions.logout(token);

        verifyNoInteractions(f.store);
        assertEquals(List.of(), f.tx.events);
    }

    private static final class Fixture {
        final Tx tx = new Tx();
        final UserJdbc jdbc = new UserJdbc(tx);
        final JdbcAuthSessionStore store = mock(JdbcAuthSessionStore.class);
        final JwtSessionTokens tokens = mock(JwtSessionTokens.class);
        final JdbcAuthSessions sessions;

        Fixture() {
            when(tokens.access(any())).thenReturn("synthetic-access");
            var refreshTokens = new RefreshTokens(new CountingRandom());
            sessions = new JdbcAuthSessions(jdbc, new TransactionTemplate(tx), tokens, store, refreshTokens);
        }
    }

    /** Deterministic, distinct bytes so generated tokens differ from the fixed presented token. */
    private static final class CountingRandom extends SecureRandom {
        private byte next = 1;

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = next++;
            }
        }
    }

    private static final class UserJdbc extends JdbcTemplate {
        private final Tx tx;
        boolean userPresent = true;
        Object[] userArgs;

        UserJdbc(Tx tx) {
            this.tx = tx;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            assertTrue(tx.active);
            assertEquals("SELECT user_id, email, name, role, care_reg_no FROM pawbridge_user.users WHERE user_id = ?", sql);
            userArgs = args;
            List<T> rows = new ArrayList<>();
            if (!userPresent) {
                return rows;
            }
            try {
                var row = mock(ResultSet.class);
                when(row.getLong("user_id")).thenReturn(7L);
                when(row.getString("email")).thenReturn("synthetic@fixture.invalid");
                when(row.getString("name")).thenReturn("Synthetic");
                when(row.getString("role")).thenReturn("ROLE_USER");
                when(row.getString("care_reg_no")).thenReturn("CARE");
                rows.add(mapper.mapRow(row, 0));
                return rows;
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class Tx implements PlatformTransactionManager {
        final List<String> events = new ArrayList<>();
        boolean active;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertFalse(active);
            active = true;
            events.add("begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            active = false;
            events.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            active = false;
            events.add("rollback");
        }
    }
}
