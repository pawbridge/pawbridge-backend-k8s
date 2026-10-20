package com.pawbridge.backend.user.internal.session.infrastructure;

import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException;
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException.Reason;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcAuthSessions implements AuthSessions {
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final JwtSessionTokens tokens;
    private final Supplier<LocalDateTime> now;

    public JdbcAuthSessions(JdbcOperations jdbc, TransactionOperations transactions, JwtSessionTokens tokens) {
        this(jdbc,transactions,tokens,LocalDateTime::now);
    }
    public JdbcAuthSessions(JdbcOperations jdbc, TransactionOperations transactions,
            JwtSessionTokens tokens,Supplier<LocalDateTime> now) {
        this.jdbc=jdbc;this.transactions=transactions;this.tokens=tokens;this.now=now;
    }
    @Override
    public Tokens refresh(String value) {
        return transactions.execute(status -> {
            if(!tokens.valid(value)) throw new AuthSessionRejectedException(Reason.INVALID);
            var rows=jdbc.query("SELECT refresh_token_id, token, user_id, expires_at FROM pawbridge_user.refresh_tokens WHERE token = ?",
                    (row,n) -> new StoredRefresh(row.getLong("refresh_token_id"),row.getLong("user_id"),
                            row.getTimestamp("expires_at",utc()).toLocalDateTime()),value);
            if(rows.isEmpty()) throw new AuthSessionRejectedException(Reason.MISSING);
            var stored=rows.getFirst();
            if(now.get().isAfter(stored.expiresAt())) {
                // The source schedules managed remove then immediately throws: rollback occurs before flush.
                // Do not introduce an observable JDBC DELETE or its permission/lock failure on this path.
                throw new AuthSessionRejectedException(Reason.EXPIRED);
            }
            var users=jdbc.query("SELECT user_id, email, name, role, care_reg_no FROM pawbridge_user.users WHERE user_id = ?",
                    (row,n) -> new JwtSessionTokens.UserClaims(row.getLong("user_id"),row.getString("email"),
                            row.getString("name"),row.getString("role"),row.getString("care_reg_no")),stored.userId());
            if(users.isEmpty())throw new UserNotFoundException();
            String access=tokens.access(users.getFirst());
            String refresh=tokens.refresh();
            LocalDateTime expiresAt=now.get().plusSeconds(TimeUnit.MILLISECONDS.toSeconds(tokens.refreshExpiration()));
            int changed=jdbc.update(connection -> {
                var statement=connection.prepareStatement("UPDATE pawbridge_user.refresh_tokens SET token = ?, expires_at = ? WHERE refresh_token_id = ?");
                try {
                    statement.setString(1,refresh);statement.setTimestamp(2,Timestamp.valueOf(expiresAt),utc());
                    statement.setLong(3,stored.id());return statement;
                } catch(SQLException | RuntimeException | Error failure) {
                    try { statement.close(); } catch(SQLException closing) { failure.addSuppressed(closing); }
                    throw failure;
                }
            });
            if(changed!=1) throw new OptimisticLockingFailureException("Refresh token disappeared during renewal");
            return new Tokens(access,refresh);
        });
    }
    @Override
    public void logout(long userId) {
        transactions.executeWithoutResult(status -> {
            // Derived repository deletion selects existing entities first. A later inserted row is not included.
            var ids=jdbc.query("SELECT refresh_token_id FROM pawbridge_user.refresh_tokens WHERE user_id = ?",
                    (row,n) -> row.getLong(1),userId);
            for(long id:ids) {
                if(jdbc.update("DELETE FROM pawbridge_user.refresh_tokens WHERE refresh_token_id = ?",id)!=1)
                    throw new OptimisticLockingFailureException("Refresh token disappeared during logout");
            }
        });
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private record StoredRefresh(long id,long userId,LocalDateTime expiresAt) {}
}
