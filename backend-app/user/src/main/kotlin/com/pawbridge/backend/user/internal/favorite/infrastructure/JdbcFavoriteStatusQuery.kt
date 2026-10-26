package com.pawbridge.backend.user.internal.favorite.infrastructure

import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionOperations

class JdbcFavoriteStatusQuery(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : FavoriteStatusQuery {

    // A null query or transaction result fails with NullPointerException, as the Java unboxing did.
    override fun isFavorite(userId: Long, animalId: Long): Boolean =
        readOnlyTransaction.execute { _ ->
            jdbc.queryForObject(EXISTS_FAVORITE, Boolean::class.javaObjectType, userId, animalId)
        }!!

    private companion object {
        // The trailing newline matches the former Java text block; unit tests compare the SQL text exactly.
        private val EXISTS_FAVORITE = """
            SELECT EXISTS (
                SELECT 1 FROM pawbridge_user.favorites
                WHERE user_id = ? AND animal_id = ?
            )
        """.trimIndent() + "\n"
    }
}
