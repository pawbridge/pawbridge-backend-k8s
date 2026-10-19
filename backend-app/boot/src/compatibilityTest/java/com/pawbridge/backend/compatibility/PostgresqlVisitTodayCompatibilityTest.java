package com.pawbridge.backend.compatibility;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Only the JDBC DATE binding and two-field data contract run in both actual JVM zones. */
class PostgresqlVisitTodayCompatibilityTest extends F16VisitFixture {
    @ParameterizedTest
    @CsvSource({"2031-04-17T14:59:59.999999Z,2031-04-17,17", "2031-04-17T15:00:00Z,2031-04-18,18"})
    void givenAdjacentSeoulDaysWithDifferentCounts__whenReadingAcrossMidnight__thenTheSeoulDateAndBoundDateMatchOriginal(String instant, String date, long count) throws Exception {
        seed(LocalDate.parse("2031-04-17"), 17); seed(LocalDate.parse("2031-04-18"), 18);
        same(Instant.parse(instant), LocalDate.parse(date), count);
    }

    @Test void givenNoRowForTheSeoulDay__whenReading__thenMissingIsZeroWithoutInsertingAnAggregate() throws Exception {
        seed(LocalDate.parse("2031-04-16"), 91);
        same(Instant.parse("2031-04-17T10:00:00Z"), LocalDate.parse("2031-04-17"), 0);
    }

    @Test void givenTheMaximumBigintCount__whenReadingAndSerializing__thenTheRawLongAndExactTwoFieldsMatchOriginal() throws Exception {
        seed(LocalDate.parse("2031-04-17"), Long.MAX_VALUE);
        same(Instant.parse("2031-04-17T10:00:00.123456Z"), LocalDate.parse("2031-04-17"), Long.MAX_VALUE);
    }
}
