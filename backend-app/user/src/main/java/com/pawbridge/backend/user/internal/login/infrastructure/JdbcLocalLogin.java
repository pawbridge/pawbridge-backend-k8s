package com.pawbridge.backend.user.internal.login.infrastructure;

import com.pawbridge.backend.user.internal.login.application.LocalLogin;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/** Authentication lookup and refresh lookup precede the original individual save transaction. */
public final class JdbcLocalLogin implements LocalLogin {
    private final JdbcOperations jdbc;
    private final TransactionOperations saves;
    private final JwtSessionTokens tokens;
    private final Supplier<LocalDateTime> now;

    public JdbcLocalLogin(JdbcOperations jdbc, TransactionOperations saves, JwtSessionTokens tokens) {
        this(jdbc,saves,tokens,LocalDateTime::now);
    }
    public JdbcLocalLogin(JdbcOperations jdbc, TransactionOperations saves, JwtSessionTokens tokens,
            Supplier<LocalDateTime> now) {
        this.jdbc=jdbc; this.saves=saves; this.tokens=tokens; this.now=now;
    }
    @Override public Optional<Account> findByEmail(String email) {
        var found=jdbc.query("SELECT user_id, email, name, password, role, care_reg_no FROM pawbridge_user.users WHERE lower(email) = lower(?)",
                (row,n)->new Account(row.getLong("user_id"),row.getString("email"),row.getString("name"),
                        row.getString("password"),row.getString("role"),row.getString("care_reg_no")),email);
        if(found.size()>1)throw new IncorrectResultSizeDataAccessException(1,found.size());
        return found.stream().findFirst();
    }
    @Override public AuthSessions.Tokens complete(Account account) {
        String access=tokens.access(new JwtSessionTokens.UserClaims(account.userId(),account.email(),account.name(),account.role(),account.careRegNo()));
        String refresh=tokens.refresh();
        LocalDateTime expires=now.get().plusSeconds(TimeUnit.MILLISECONDS.toSeconds(tokens.refreshExpiration()));
        var ids=jdbc.query("SELECT refresh_token_id FROM pawbridge_user.refresh_tokens WHERE user_id = ?",
                (row,n)->row.getLong("refresh_token_id"),account.userId());
        if(ids.size()>1)throw new IncorrectResultSizeDataAccessException(1,ids.size());
        saves.executeWithoutResult(status->{
            if(ids.isEmpty())insert(account.userId(),refresh,expires);
            else update(ids.getFirst(),refresh,expires);
        });
        return new AuthSessions.Tokens(access,refresh);
    }
    private void update(long id,String token,LocalDateTime expires) {
        int changed=jdbc.update(connection->{
            PreparedStatement statement=connection.prepareStatement("UPDATE pawbridge_user.refresh_tokens SET token = ?, expires_at = ? WHERE refresh_token_id = ?");
            try {
                statement.setString(1,token);statement.setTimestamp(2,Timestamp.valueOf(expires),utc());
                statement.setLong(3,id);return statement;
            } catch(SQLException | RuntimeException | Error failure){closeFailed(statement,failure);throw failure;}
        });
        if(changed!=1)throw new OptimisticLockingFailureException("Refresh token disappeared during login");
    }
    private void insert(long userId,String token,LocalDateTime expires) {
        LocalDateTime created=now.get();
        jdbc.update(connection->{
            PreparedStatement statement=connection.prepareStatement("INSERT INTO pawbridge_user.refresh_tokens(token,user_id,expires_at,created_at) VALUES (?,?,?,?)");
            try {
                statement.setString(1,token);statement.setLong(2,userId);
                statement.setTimestamp(3,Timestamp.valueOf(expires),utc());
                statement.setTimestamp(4,Timestamp.valueOf(created),utc());return statement;
            } catch(SQLException | RuntimeException | Error failure){closeFailed(statement,failure);throw failure;}
        });
    }
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static void closeFailed(PreparedStatement statement,Throwable failure){
        try{statement.close();}catch(SQLException closing){failure.addSuppressed(closing);}
    }
}
