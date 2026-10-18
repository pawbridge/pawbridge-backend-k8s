package com.pawbridge.animalservice.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;

/** Database-specific SQL fragments; identifiers come only from application constants. */
public enum AnimalSqlDialect {
    MYSQL, POSTGRESQL;

    public static AnimalSqlDialect from(Connection connection) throws SQLException {
        return product(connection.getMetaData().getDatabaseProductName());
    }

    public static AnimalSqlDialect from(JdbcTemplate jdbc) {
        try {
            String name = JdbcUtils.extractDatabaseMetaData(jdbc.getDataSource(),
                    metadata -> metadata.getDatabaseProductName());
            return product(name);
        } catch (org.springframework.jdbc.support.MetaDataAccessException exception) {
            throw new IllegalStateException("Cannot determine animal database dialect", exception);
        }
    }

    private static AnimalSqlDialect product(String name) {
        return switch (name) {
            case "MySQL" -> MYSQL;
            case "PostgreSQL" -> POSTGRESQL;
            default -> throw new IllegalStateException("Unsupported animal database dialect");
        };
    }

    public String sql(String mysql, String postgresql) { return this == POSTGRESQL ? postgresql : mysql; }
    public String jsonParameter() { return sql("?", "CAST(? AS jsonb)"); }
    public String jsonText(String column, String key) {
        return sql("JSON_UNQUOTE(JSON_EXTRACT(" + column + ",'$." + key + "'))",
                "(" + column + "->>'" + key + "')");
    }
    public String jsonValue(String column, String key) {
        return sql("JSON_EXTRACT(" + column + ",'$." + key + "')", "(" + column + "->'" + key + "')");
    }
    public String secondsFromNow() {
        return sql("TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))",
                "(clock_timestamp() + CAST(? AS integer) * INTERVAL '1 second')");
    }
    public int streamingFetchSize() { return this == POSTGRESQL ? 200 : Integer.MIN_VALUE; }
}
