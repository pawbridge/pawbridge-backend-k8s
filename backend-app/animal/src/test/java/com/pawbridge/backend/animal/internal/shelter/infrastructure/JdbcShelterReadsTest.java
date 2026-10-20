package com.pawbridge.backend.animal.internal.shelter.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.*;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcShelterReadsTest {
    private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    private final NamedParameterJdbcOperations discoveryJdbc = mock(NamedParameterJdbcOperations.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final JdbcShelterReads shelters = new JdbcShelterReads(jdbc, discoveryJdbc,
            TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction(), mapper);
    private static final PageRequest FIRST = new PageRequest(0, 20, List.of(new Order("name", true, false)));

    @ParameterizedTest
    @MethodSource("filters")
    void givenCompetingSearchParameters__whenListing__thenPreserveSourcePrecedenceAndWildcardSemantics(
            Search request, String predicate, String parameter, String value) {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any())).thenReturn(List.of());
        assertThat(shelters.search(request, FIRST).content()).isEmpty();
        var sql = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any());
        assertThat(sql.getValue()).contains("FROM pawbridge_animal.shelters s", predicate, " ORDER BY s.name ASC LIMIT :limit OFFSET :offset");
        if (parameter != null) assertThat(parameters.getValue().getValue(parameter)).isEqualTo(value);
        if (request.keyword() != null && !request.keyword().isBlank() && request.address() != null && !request.address().isBlank()) {
            assertThat(parameters.getValue().getValue("address")).isEqualTo("%서울_%");
        }
        verify(jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class));
        verifyNoInteractions(discoveryJdbc);
    }

    static Stream<Arguments> filters() {
        return Stream.of(
                Arguments.of(new Search("ignored", " 서울_ ", "ignored", " 센터% "), "WHERE s.address LIKE :address ESCAPE '' AND (s.name LIKE :keyword ESCAPE '' OR s.address LIKE :keyword ESCAPE '')", "keyword", "%센터%%"),
                Arguments.of(new Search("ignored", "   ", "ignored", "센터%"), "WHERE s.name LIKE :keyword ESCAPE '' OR s.address LIKE :keyword ESCAPE ''", "keyword", "%센터%%"),
                Arguments.of(new Search("ignored", "서울", "ignored", ""), "WHERE s.name LIKE :keyword ESCAPE '' OR s.address LIKE :keyword ESCAPE ''", "keyword", "%%"),
                Arguments.of(new Search(null, null, null, "a\\_%"), "WHERE s.name LIKE :keyword ESCAPE '' OR s.address LIKE :keyword ESCAPE ''", "keyword", "%a\\_%%"),
                Arguments.of(new Search(" A%_\\ ", "ignored", "ignored", null), "WHERE s.name LIKE :term ESCAPE '\\'", "term", "% A\\%\\_\\\\ %"),
                Arguments.of(new Search(null, " 서울% ", "ignored", null), "WHERE s.address LIKE :term ESCAPE '\\'", "term", "% 서울\\% %"),
                Arguments.of(new Search(null, null, "시_청", null), "WHERE s.organization_name LIKE :term ESCAPE '\\'", "term", "%시\\_청%"),
                Arguments.of(new Search(null, null, null, null), "SELECT s.id,s.care_reg_no", null, null));
    }

    @Test
    void givenPartialAndFullPages__whenListing__thenPreserveSourcePageableExecutionUtilsCountDecisions() {
        Summary row = new Summary(4L, "r", "센터", null, null, null, null);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any())).thenReturn(List.of(row));
        assertThat(shelters.search(new Search(null, null, null, null), new PageRequest(2, 20, List.of())).totalElements()).isEqualTo(41);
        verify(jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class));
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(99L);
        assertThat(shelters.search(new Search(null, null, null, null), new PageRequest(0, 1, List.of())).totalElements()).isEqualTo(99);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any())).thenReturn(List.of());
        assertThat(shelters.search(new Search(null, null, null, null), new PageRequest(7, 20, List.of())).totalElements()).isEqualTo(99);
        verify(jdbc, times(2)).queryForObject(contains("count(*)"), any(MapSqlParameterSource.class), eq(Long.class));
    }

    @Test
    void givenMappedScalarSorts__whenListing__thenPreserveAll13FieldsBothDirectionsAndCaseFlagWithoutNewTieBreak() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any())).thenReturn(List.of());
        String[][] columns = {{"id", "id"}, {"careRegNo", "care_reg_no"}, {"name", "name"}, {"phone", "phone"},
                {"address", "address"}, {"ownerName", "owner_name"}, {"organizationName", "organization_name"}, {"email", "email"},
                {"introduction", "introduction"}, {"adoptionProcedure", "adoption_procedure"}, {"operatingHours", "operating_hours"},
                {"createdAt", "created_at"}, {"updatedAt", "updated_at"}};
        for (String[] column : columns) {
            for (boolean ascending : List.of(true, false)) {
                shelters.search(new Search(null, null, null, null), new PageRequest(0, 20, List.of(new Order(column[0], ascending, false))));
                verify(jdbc).query(endsWith(" ORDER BY s." + column[1] + (ascending ? " ASC" : " DESC") + " LIMIT :limit OFFSET :offset"),
                        any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any());
            }
        }
        shelters.search(new Search(null, null, null, null), new PageRequest(0, 20,
                List.of(new Order("name", true, true), new Order("createdAt", false, false))));
        verify(jdbc).query(endsWith(" ORDER BY lower(s.name) ASC,s.created_at DESC LIMIT :limit OFFSET :offset"),
                any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any());
        assertThatThrownBy(() -> shelters.search(new Search(null, null, null, null), new PageRequest(0, 20,
                List.of(new Order("name;drop table shelters", true, false))))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void givenCollectionSort__whenListing__thenApplySqlLimitBeforeEntityDeduplicationAndPartialPageInference() {
        Summary first = new Summary(4L, "reg-4", "센터4", null, null, null, null);
        Summary second = new Summary(9L, "reg-9", "센터9", null, null, null, null);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any())).thenReturn(List.of(first, first));
        PageRequest page = new PageRequest(0, 2, List.of(new Order("animals.id", true, false), new Order("animals.breed", false, true)));
        var result = shelters.search(new Search(null, null, null, null), page);
        assertThat(result.content()).containsExactly(first);
        assertThat(result.totalElements()).isOne();
        verify(jdbc).query(argThat((String sql) -> sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id")
                && sql.contains(" ORDER BY sort_0.id ASC,lower(sort_0.breed) DESC LIMIT :limit OFFSET :offset")
                && !sql.contains("DISTINCT") && !sql.contains("sort_1")), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any());
        verify(jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any())).thenReturn(List.of(first, second));
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(99L);
        assertThat(shelters.search(new Search(null, null, null, null), page).totalElements()).isEqualTo(99);
        verify(jdbc).queryForObject(argThat((String sql) -> sql.contains("count(*)") && !sql.contains("JOIN") && !sql.contains("ORDER BY")),
                any(MapSqlParameterSource.class), eq(Long.class));
    }

    @ParameterizedTest
    @MethodSource("associationSortPaths")
    void givenNestedOrCamelAssociationSort__whenListing__thenPreserveMappedAssociationPathsAndStringCaseHandling(String path) {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any())).thenReturn(List.of());
        shelters.search(new Search(null, null, null, null), new PageRequest(0, 20, List.of(new Order(path, true, true), new Order("animals.species", false, true))));
        verify(jdbc).query(argThat((String sql) -> sql.contains("LEFT JOIN pawbridge_animal.animals sort_0 ON sort_0.shelter_id=s.id")
                && sql.contains("LEFT JOIN pawbridge_animal.shelters sort_1 ON sort_1.id=sort_0.shelter_id")
                && sql.contains(" ORDER BY lower(sort_1.name) ASC,sort_0.species DESC LIMIT :limit OFFSET :offset")),
                any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any());
    }
    static Stream<String> associationSortPaths() { return Stream.of("animals.shelter.name", "animals_shelter_name", "animalsShelterName"); }

    @ParameterizedTest
    @MethodSource("terminalSortPaths")
    void givenTerminalAssociationOrCamelIdSort__whenListing__thenUseOriginalSinglePrimaryKeyOrdering(String path, String expression) {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any())).thenReturn(List.of());
        shelters.search(new Search(null, null, null, null), new PageRequest(0, 20, List.of(new Order(path, true, false))));
        verify(jdbc).query(argThat((String sql) -> sql.contains("LEFT JOIN pawbridge_animal.animals sort_0")
                && sql.contains(" ORDER BY " + expression + " ASC LIMIT :limit OFFSET :offset")),
                any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any());
    }
    static Stream<Arguments> terminalSortPaths() {
        return Stream.of(Arguments.of("animals", "sort_0.id"), Arguments.of("animals.shelter", "sort_1.id"),
                Arguments.of("animalsId", "sort_0.id"), Arguments.of("AnimalsShelter", "sort_1.id"));
    }

    @Test
    void givenDeclaredQueryAliasSort__whenSearchingKeyword__thenUseOriginalRootAliasWithoutAddingJoin() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any())).thenReturn(List.of());
        shelters.search(new Search(null, null, null, "센터"), new PageRequest(0, 20, List.of(new Order("s.name", false, false))));
        verify(jdbc).query(argThat((String sql) -> sql.contains("WHERE s.name LIKE :keyword ESCAPE ''")
                && sql.contains(" ORDER BY s.name DESC LIMIT :limit OFFSET :offset") && !sql.contains("JOIN")),
                any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Summary>>any());
    }

    @Test
    void givenPageOffsetOutsideJpaIntegerRange__whenListing__thenKeepSourceInputFailureBeforeSql() {
        assertThatThrownBy(() -> shelters.search(new Search(null, null, null, null), new PageRequest(Integer.MAX_VALUE, 20, List.of())))
                .isInstanceOf(InvalidDataAccessApiUsageException.class).hasMessage("Page offset exceeds Integer.MAX_VALUE (2147483647)");
        verifyNoInteractions(jdbc);
    }

    @Test
    void givenDetailAndStoredPublicInformation__whenFindingByIdOrRegistration__thenReadBothInOneReadScopeAndKeepAllFields() throws Exception {
        LocalDateTime created = LocalDateTime.of(2026, 9, 29, 13, 4, 5, 123456000), updated = created.plusDays(1);
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", Long.class)).thenReturn(4L);
        when(row.getString("care_reg_no")).thenReturn("reg-4");
        when(row.getString("name")).thenReturn("보호 센터");
        when(row.getString("phone")).thenReturn("02-1234");
        when(row.getString("address")).thenReturn("서울");
        when(row.getString("organization_name")).thenReturn("시청");
        when(row.getString("owner_name")).thenReturn("대표");
        when(row.getString("email")).thenReturn("sample@example.test");
        when(row.getString("introduction")).thenReturn("소개");
        when(row.getString("adoption_procedure")).thenReturn("절차");
        when(row.getString("operating_hours")).thenReturn("09:00");
        when(row.getObject("created_at", LocalDateTime.class)).thenReturn(created);
        when(row.getObject("updated_at", LocalDateTime.class)).thenReturn(updated);
        ResultSet publicRow = mock(ResultSet.class);
        when(publicRow.getString("details")).thenReturn("""
                {"source":"ignored","phone":"02-public","latitude":37.5,"longitude":127.1,
                 "weekdayOpen":"09:00","weekdayClose":"18:00","weekendOpen":"10:00","weekendClose":"16:00",
                 "closedDays":"월요일","sourceUpdatedDate":"2026-09-29","collectedAt":"2000-01-01T00:00"}
                """);
        when(publicRow.getTimestamp("collected_at")).thenReturn(Timestamp.valueOf(updated));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Details>>any()))
                .thenAnswer(call -> {
                    String sql = call.getArgument(0);
                    RowMapper<?> mapping = call.getArgument(2);
                    return List.of(mapping.mapRow(sql.contains("shelter_public_information") ? publicRow : row, 0));
                });
        PublicInformation expectedPublic = new PublicInformation("APMS_SHELTER_INFO", "02-public", 37.5, 127.1,
                "09:00", "18:00", "10:00", "16:00", "월요일", "2026-09-29", updated);
        Details expected = new Details(4L, "reg-4", "보호 센터", "02-1234", "서울", "대표", "시청", "sample@example.test",
                "소개", "절차", "09:00", created, updated, expectedPublic);
        assertThat(shelters.findById(4)).isEqualTo(expected);
        assertThat(shelters.findByCareRegNo("reg-4")).isEqualTo(expected);
        verify(jdbc).query(contains("WHERE s.id=:value"), argThat((MapSqlParameterSource p) -> p.getValue("value").equals(4L)), org.mockito.ArgumentMatchers.<RowMapper<Details>>any());
        verify(jdbc).query(contains("WHERE s.care_reg_no=:value"), argThat((MapSqlParameterSource p) -> p.getValue("value").equals("reg-4")), org.mockito.ArgumentMatchers.<RowMapper<Details>>any());
        verify(jdbc, times(2)).query(contains("shelter_public_information"), argThat((MapSqlParameterSource p) -> p.getValue("id").equals(4L)), org.mockito.ArgumentMatchers.<RowMapper<PublicInformation>>any());
    }

    @Test
    void givenMissingDetailOrRegistration__whenFinding__thenKeepSourceGenericFailureWithoutPublicInformationRead() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Details>>any())).thenReturn(List.of());
        assertThatThrownBy(() -> shelters.findById(4)).isInstanceOf(IllegalStateException.class).hasMessage("Shelter not found: 4");
        assertThatThrownBy(() -> shelters.findByCareRegNo("absent")).isInstanceOf(IllegalStateException.class).hasMessage("Shelter not found: absent");
        verify(jdbc, never()).query(contains("shelter_public_information"), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<PublicInformation>>any());
    }

    @Test
    void givenNullableSummaryAndNoPublicInformation__whenReading__thenRetainNullsWithoutFabricatedFields() {
        Details details = new Details(4L, "reg-4", "센터", null, null, null, null, null, null, null, null, null, null, null);
        when(jdbc.query(contains("WHERE s.id=:value"), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Details>>any())).thenReturn(List.of(details));
        when(jdbc.query(contains("shelter_public_information"), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<PublicInformation>>any())).thenReturn(List.of());
        assertThat(shelters.findById(4)).isEqualTo(details);
    }

    @Test
    void givenMalformedPublicInformation__whenReadingDetail__thenKeepGenericFailureWithoutRawJsonOrCause() throws Exception {
        Details details = new Details(4L, "reg", "센터", null, null, null, null, null, null, null, null, null, null, null);
        when(jdbc.query(contains("WHERE s.id=:value"), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Details>>any())).thenReturn(List.of(details));
        ResultSet invalid = mock(ResultSet.class);
        when(invalid.getString("details")).thenReturn("[\"synthetic-private-detail\"]");
        when(jdbc.query(contains("shelter_public_information"), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<PublicInformation>>any()))
                .thenAnswer(call -> List.of(call.<RowMapper<PublicInformation>>getArgument(2).mapRow(invalid, 0)));
        assertThatThrownBy(() -> shelters.findById(4)).isInstanceOf(IllegalStateException.class).hasNoCause().hasMessage("Invalid shelter public information");
    }

    @Test
    void givenDirectoryCount__whenCounting__thenReturnOriginalScalarLongWithoutOtherReads() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(9876543210L);
        assertThat(shelters.count()).isEqualTo(9876543210L);
        verify(jdbc).queryForObject(eq("SELECT count(*) FROM pawbridge_animal.shelters"), any(MapSqlParameterSource.class), eq(Long.class));
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(discoveryJdbc);
    }
}
