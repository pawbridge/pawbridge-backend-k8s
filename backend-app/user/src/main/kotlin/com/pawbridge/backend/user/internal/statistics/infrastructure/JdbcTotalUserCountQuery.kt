package com.pawbridge.backend.user.internal.statistics.infrastructure

import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionOperations

class JdbcTotalUserCountQuery(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : TotalUserCountQuery {

    // A null aggregate or a null transaction result fails with NullPointerException, as the Java unboxing did.
    override fun count(): Long =
        readOnlyTransaction.execute { _ ->
            jdbc.queryForObject(COUNT_USERS_SQL, Long::class.javaObjectType)
        }!!

    private companion object {
        private const val COUNT_USERS_SQL = "SELECT count(user_id) FROM pawbridge_user.users"
    }
}
