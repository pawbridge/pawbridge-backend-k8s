package com.pawbridge.backend.user.internal.nickname.infrastructure;

import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.api.UserNicknameUnavailableException;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcUserNicknameQuery implements UserNicknameQuery {
    private static final String FIND_NICKNAME_SQL =
            "SELECT nickname FROM pawbridge_user.users WHERE user_id = ?";

    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcUserNicknameQuery(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public Optional<String> findNickname(long userId) {
        try {
            return readOnlyTransaction.execute(status -> jdbc.query(
                    FIND_NICKNAME_SQL,
                    rows -> {
                        if (!rows.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(rows.getString("nickname"));
                    },
                    userId));
        } catch (DataAccessException | TransactionException exception) {
            throw new UserNicknameUnavailableException(exception);
        }
    }
}
