package com.pawbridge.backend.animal.internal.travel.infrastructure.collection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Original travel-only PostgreSQL same-session advisory lock; independent of commit/rollback. */
final class PostgresqlTravelSessionLock {
    private static final int NAMESPACE = 0x50415742;
    enum Operation { ACQUIRE, RELEASE, OWNED }
    private PostgresqlTravelSessionLock() { }

    static Integer query(Connection connection, String name, Operation operation) throws SQLException {
        if (!"pawbridge_animal.petTravelCollection".equals(name))
            throw new IllegalArgumentException("Unknown collection lock");
        String sql = switch (operation) {
            case ACQUIRE -> "SELECT CASE WHEN pg_try_advisory_lock(" + NAMESPACE + ",?) THEN 1 ELSE 0 END";
            case RELEASE -> "SELECT CASE WHEN pg_advisory_unlock(" + NAMESPACE + ",?) THEN 1 ELSE 0 END";
            case OWNED -> "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_locks WHERE locktype='advisory' "
                    + "AND pid=pg_backend_pid() AND database=(SELECT oid FROM pg_database WHERE datname=current_database()) "
                    + "AND classid=" + NAMESPACE + " AND objid=CAST(? AS oid) AND objsubid=2 AND granted) THEN 1 ELSE 0 END";
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, 2);
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                Number value = (Number) result.getObject(1);
                return value == null ? null : value.intValue();
            }
        }
    }
}
