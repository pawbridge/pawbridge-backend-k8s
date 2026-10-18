package com.pawbridge.animalservice.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Same-session locks, shared by APMS/shelter collection and independent of commit/rollback. */
public final class CollectionSessionLock {
    private static final int NAMESPACE = 0x50415742; // PAWB; PostgreSQL two-int advisory key space.
    public enum Operation { ACQUIRE, RELEASE, OWNED }
    private CollectionSessionLock() { }

    public static Integer query(Connection connection, String name, Operation operation) throws SQLException {
        AnimalSqlDialect dialect = AnimalSqlDialect.from(connection);
        String sql;
        if (dialect == AnimalSqlDialect.MYSQL) {
            sql = switch (operation) {
                case ACQUIRE -> "SELECT GET_LOCK(?, 0)";
                case RELEASE -> "SELECT RELEASE_LOCK(?)";
                case OWNED -> "SELECT IS_USED_LOCK(?)=CONNECTION_ID()";
            };
        } else {
            sql = switch (operation) {
                case ACQUIRE -> "SELECT CASE WHEN pg_try_advisory_lock(" + NAMESPACE + ",?) THEN 1 ELSE 0 END";
                case RELEASE -> "SELECT CASE WHEN pg_advisory_unlock(" + NAMESPACE + ",?) THEN 1 ELSE 0 END";
                case OWNED -> "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_locks WHERE locktype='advisory' "
                        + "AND pid=pg_backend_pid() AND database=(SELECT oid FROM pg_database WHERE datname=current_database()) "
                        + "AND classid=" + NAMESPACE + " AND objid=CAST(? AS oid) AND objsubid=2 AND granted) THEN 1 ELSE 0 END";
            };
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (dialect == AnimalSqlDialect.MYSQL) statement.setString(1, name);
            else statement.setInt(1, switch (name) {
                case "pawbridge_animal.apmsAnimalSyncJob" -> 1;
                case "pawbridge_animal.petTravelCollection" -> 2;
                default -> throw new IllegalArgumentException("Unknown collection lock");
            });
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                Number value = (Number) result.getObject(1);
                return value == null ? null : value.intValue();
            }
        }
    }
}
