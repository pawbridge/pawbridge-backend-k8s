package com.pawbridge.backend.user.internal.favorite.infrastructure;

import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcFavoriteStatusQuery implements FavoriteStatusQuery {
    private static final String EXISTS_FAVORITE = """
            SELECT EXISTS (
                SELECT 1 FROM pawbridge_user.favorites
                WHERE user_id = ? AND animal_id = ?
            )
            """;

    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;

    public JdbcFavoriteStatusQuery(JdbcOperations jdbc, TransactionOperations readOnlyTransaction) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
    }

    @Override
    public boolean isFavorite(long userId, long animalId) {
        return readOnlyTransaction.execute(status -> jdbc.queryForObject(EXISTS_FAVORITE, Boolean.class, userId, animalId));
    }
}
