package com.pawbridge.backend.animal.internal.favorite.infrastructure;

import com.pawbridge.backend.animal.api.AnimalFavoriteCounts;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.SqlValue;

/** Joins the caller's transaction; it opens none of its own. */
public final class JdbcAnimalFavoriteCounts implements AnimalFavoriteCounts {
    static final String INCREMENT = "UPDATE pawbridge_animal.animals SET favorite_count=favorite_count+1,updated_at=? WHERE id=?";
    static final String DECREMENT = "UPDATE pawbridge_animal.animals SET favorite_count=CASE WHEN favorite_count>0 THEN favorite_count-1 ELSE favorite_count END,updated_at=CASE WHEN favorite_count>0 THEN ? ELSE updated_at END WHERE id=?";

    private final JdbcOperations jdbc;
    private final Supplier<LocalDateTime> now;

    public JdbcAnimalFavoriteCounts(JdbcOperations jdbc) { this(jdbc, LocalDateTime::now); }

    public JdbcAnimalFavoriteCounts(JdbcOperations jdbc, Supplier<LocalDateTime> now) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.now = Objects.requireNonNull(now);
    }

    @Override
    public boolean adjust(long animalId, int delta) {
        if (delta != 1 && delta != -1) throw new IllegalArgumentException("Favorite count delta must be +1 or -1");
        int affected = jdbc.update(delta == 1 ? INCREMENT : DECREMENT, timestamp(now.get()), animalId);
        if (affected < 0 || affected > 1) throw new IllegalStateException("Favorite count update affected an unexpected row count");
        return affected == 1;
    }

    private static SqlValue timestamp(LocalDateTime value) {
        return new SqlValue() {
            public void setValue(PreparedStatement statement, int index) throws SQLException {
                statement.setTimestamp(index, Timestamp.valueOf(value), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            }
            public void cleanup() { }
        };
    }
}
