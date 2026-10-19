package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

/** Access and recovery run once in UTC, separately from the two-zone DATE checks. */
class PostgresqlVisitTodayAccessTest extends F16VisitFixture {
    private static final Instant NOW = Instant.parse("2031-04-17T10:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2031-04-17");

    @ParameterizedTest @ValueSource(strings = {"visit_date", "unique_visitors"})
    void givenARequiredColumnSelectRevoked__whenReadingThenRestoring__thenFailureDoesNotBecomeZeroAndTheSinglePoolRecovers(String column) throws Exception {
        assertThat(ZoneId.systemDefault().getId()).isEqualTo("UTC");
        seed(DAY, 42); String before = snapshot();
        execute("REVOKE SELECT(" + column + ") ON pawbridge_community.daily_visit_count FROM pawbridge_f2_reader");
        try {
            assertThatThrownBy(() -> candidate(NOW).read()).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> original(NOW).today()).isInstanceOf(DataAccessException.class);
            assertThat(snapshot()).isEqualTo(before); returned();
        } finally { execute("GRANT SELECT(" + column + ") ON pawbridge_community.daily_visit_count TO pawbridge_f2_reader"); }
        same(NOW, DAY, 42); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void givenTheWriterHasNoVisitPrivilege__whenSelecting__thenItIsDeniedAndTheReaderStillWorksWithoutSideEffects() throws Exception {
        assertThat(ZoneId.systemDefault().getId()).isEqualTo("UTC");
        seed(DAY, 0); String before = snapshot(); verifyOwnership();
        try (Connection c = DriverManager.getConnection(owner.reads().jdbcUrl(), F3PostgresqlFixture.WRITER, owner.writerPassword())) {
            assertThatThrownBy(() -> { try (var s = c.createStatement()) { s.executeQuery("SELECT visit_date,unique_visitors FROM pawbridge_community.daily_visit_count WHERE false"); } })
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
        same(NOW, DAY, 0); assertThat(snapshot()).isEqualTo(before);
    }
}
