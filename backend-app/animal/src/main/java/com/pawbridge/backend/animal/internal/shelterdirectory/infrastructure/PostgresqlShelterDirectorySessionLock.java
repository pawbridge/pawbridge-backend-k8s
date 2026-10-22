package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Original collection PostgreSQL session lock, namespace PAWB/key1 shared with APMS animal collection. */
final class PostgresqlShelterDirectorySessionLock {
    private static final int NAMESPACE = 0x50415742;
    enum Operation { ACQUIRE, RELEASE }
    private PostgresqlShelterDirectorySessionLock() { }
    static Integer query(Connection connection, String name, Operation operation) throws SQLException {
        if (!"pawbridge_animal.apmsAnimalSyncJob".equals(name)) throw new IllegalArgumentException("Unknown collection lock");
        String sql = switch (operation) {
            case ACQUIRE -> "SELECT CASE WHEN pg_try_advisory_lock(" + NAMESPACE + ",?) THEN 1 ELSE 0 END";
            case RELEASE -> "SELECT CASE WHEN pg_advisory_unlock(" + NAMESPACE + ",?) THEN 1 ELSE 0 END";
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, 1);
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                Number value = (Number) result.getObject(1);
                return value == null ? null : value.intValue();
            }
        }
    }
}
