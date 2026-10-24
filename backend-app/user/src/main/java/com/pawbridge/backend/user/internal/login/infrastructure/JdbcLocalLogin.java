package com.pawbridge.backend.user.internal.login.infrastructure;

import com.pawbridge.backend.user.internal.login.application.LocalLogin;
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import java.util.Optional;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/** Authentication lookup precedes the session transaction; a new session is created for every login. */
public final class JdbcLocalLogin implements LocalLogin {
    private final JdbcOperations jdbc;
    private final TransactionOperations saves;
    private final JwtSessionTokens tokens;
    private final JdbcAuthSessionStore sessions;

    public JdbcLocalLogin(JdbcOperations jdbc, TransactionOperations saves, JwtSessionTokens tokens,
            JdbcAuthSessionStore sessions) {
        this.jdbc = jdbc;
        this.saves = saves;
        this.tokens = tokens;
        this.sessions = sessions;
    }
    @Override public Optional<Account> findByEmail(String email) {
        var found=jdbc.query("SELECT user_id, email, name, password, role, care_reg_no FROM pawbridge_user.users WHERE lower(email) = lower(?)",
                (row,n)->new Account(row.getLong("user_id"),row.getString("email"),row.getString("name"),
                        row.getString("password"),row.getString("role"),row.getString("care_reg_no")),email);
        if(found.size()>1)throw new IncorrectResultSizeDataAccessException(1,found.size());
        return found.stream().findFirst();
    }

    @Override
    public CompletedLogin complete(Account account) {
        var claims = new JwtSessionTokens.UserClaims(account.userId(), account.email(), account.name(),
                account.role(), account.careRegNo());
        String accessToken = tokens.access(claims);
        IssuedRefreshToken refreshToken = saves.execute(status -> sessions.create(account.userId()));
        return new CompletedLogin(accessToken, refreshToken);
    }
}
