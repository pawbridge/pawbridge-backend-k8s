package com.pawbridge.backend.user.internal.directory.infrastructure

import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery
import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery.Order
import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery.Request
import com.pawbridge.backend.user.internal.directory.application.UserDirectoryQuery.Slice
import com.pawbridge.backend.user.internal.profile.application.UserProfile
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations
import java.beans.Introspector
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.Calendar
import java.util.TimeZone

class JdbcUserDirectoryQuery(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
) : UserDirectoryQuery {

    override fun page(keyword: String?, role: String?, request: Request): Slice {
        return readOnlyTransaction.execute {
            if (request.offset() > Integer.MAX_VALUE) {
                throw InvalidDataAccessApiUsageException("Page offset exceeds Integer.MAX_VALUE (2147483647)")
            }
            val order = request.orders().joinToString(", ") { sort(it) }
            val conditions = ArrayList<String>()
            val arguments = ArrayList<Any>()
            if (keyword != null && !keyword.isEmpty()) {
                // Preserve the declared JPQL wildcard semantics; neither strip nor escape user text.
                conditions.add(
                    "(lower(email) LIKE lower(?) ESCAPE '' OR lower(name) LIKE lower(?) ESCAPE '' " +
                        "OR lower(nickname) LIKE lower(?) ESCAPE '')",
                )
                val pattern = "%" + keyword + "%"
                arguments.add(pattern)
                arguments.add(pattern)
                arguments.add(pattern)
            }
            if (role != null) {
                conditions.add("role = ?")
                arguments.add(role)
            }
            val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
            val pageArguments = ArrayList<Any>(arguments)
            pageArguments.add(request.size())
            pageArguments.add(request.offset())
            val content = jdbc.query(
                SELECT + TABLE + where + (if (order.isEmpty()) "" else " ORDER BY $order") + " LIMIT ? OFFSET ?",
                RowMapper { row, _ -> profile(row) },
                *pageArguments.toTypedArray(),
            )
            // Match PageableExecutionUtils: empty later pages and full pages still execute the count.
            val total = if (content.size < request.size() && (request.number() == 0 || !content.isEmpty())) {
                request.offset() + content.size
            } else {
                jdbc.queryForObject(
                    "SELECT count(*)" + TABLE + where,
                    Long::class.javaObjectType,
                    *arguments.toTypedArray(),
                )!!
            }
            Slice(content, total)
        }!!
    }

    private companion object {
        private const val TABLE = " FROM pawbridge_user.users"
        private const val SELECT = "SELECT user_id, email, name, nickname, provider, role, created_at"

        // Map.of/Set.of keep the original null rejection on lookups.
        private val SORT_COLUMNS: Map<String, String> = java.util.Map.of(
            "userId", "user_id",
            "email", "email",
            "name", "name",
            "nickname", "nickname",
            "provider", "provider",
            "role", "role",
            "createdAt", "created_at",
        )
        private val STRING_SORTS: Set<String> = java.util.Set.of("email", "name", "nickname", "provider")

        private fun sort(order: Order): String {
            val property = Introspector.decapitalize(order.property)
            var column = SORT_COLUMNS[property]
            // Deliberately limited to the existing seven-column reader grant; do not select/order secrets.
            if (column == null) {
                throw IllegalArgumentException("Unsupported user directory sort property")
            }
            if (order.ignoreCase && STRING_SORTS.contains(property)) {
                column = "lower($column)"
            }
            return column + (if (order.ascending) " ASC" else " DESC")
        }

        private fun profile(row: ResultSet): UserProfile {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val created: Timestamp? = row.getTimestamp("created_at", utc)
            return UserProfile(
                row.getLong("user_id"),
                row.getString("email"),
                row.getString("name"),
                row.getString("nickname"),
                row.getString("provider"),
                row.getString("role"),
                created?.toLocalDateTime(),
            )
        }
    }
}
