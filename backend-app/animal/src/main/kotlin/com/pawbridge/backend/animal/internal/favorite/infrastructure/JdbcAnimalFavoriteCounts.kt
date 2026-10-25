package com.pawbridge.backend.animal.internal.favorite.infrastructure

import com.pawbridge.backend.animal.api.AnimalFavoriteCounts
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.support.SqlValue
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.TimeZone
import java.util.function.Supplier

/** Joins the caller's transaction; it opens none of its own. */
class JdbcAnimalFavoriteCounts(
    private val jdbc: JdbcOperations,
    private val now: Supplier<LocalDateTime>,
) : AnimalFavoriteCounts {

    constructor(jdbc: JdbcOperations) : this(jdbc, Supplier { LocalDateTime.now() })

    override fun adjust(animalId: Long, delta: Int): Boolean {
        if (delta != 1 && delta != -1) {
            throw IllegalArgumentException("Favorite count delta must be +1 or -1")
        }
        val affected = jdbc.update(if (delta == 1) INCREMENT else DECREMENT, timestamp(now.get()), animalId)
        if (affected < 0 || affected > 1) {
            throw IllegalStateException("Favorite count update affected an unexpected row count")
        }
        return affected == 1
    }

    companion object {
        internal const val INCREMENT =
            "UPDATE pawbridge_animal.animals SET favorite_count=favorite_count+1,updated_at=? WHERE id=?"
        internal const val DECREMENT =
            "UPDATE pawbridge_animal.animals SET favorite_count=CASE WHEN favorite_count>0 THEN favorite_count-1 ELSE favorite_count END," +
                "updated_at=CASE WHEN favorite_count>0 THEN ? ELSE updated_at END WHERE id=?"

        private fun timestamp(value: LocalDateTime): SqlValue {
            return object : SqlValue {
                @Throws(SQLException::class)
                override fun setValue(statement: PreparedStatement, index: Int) {
                    statement.setTimestamp(index, Timestamp.valueOf(value), Calendar.getInstance(TimeZone.getTimeZone("UTC")))
                }

                override fun cleanup() {
                }
            }
        }
    }
}
