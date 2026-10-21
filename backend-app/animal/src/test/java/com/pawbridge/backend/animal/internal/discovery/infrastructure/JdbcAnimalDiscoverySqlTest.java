package com.pawbridge.backend.animal.internal.discovery.infrastructure;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.*;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException;
import com.pawbridge.backend.animal.internal.discovery.application.SearchProjectionPendingException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcAnimalDiscoverySqlTest {
    private KoreanSearchAnalyzer analyzer;
    private NamedParameterJdbcOperations jdbc;
    private JdbcAnimalDiscovery animals;
    private final List<Call> calls = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        analyzer = new KoreanSearchAnalyzer();
        jdbc = mock(NamedParameterJdbcOperations.class);
        animals = new JdbcAnimalDiscovery(jdbc, TransactionOperations.withoutTransaction(), analyzer);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenAnswer(call -> {
            capture(call.getArgument(0), call.getArgument(1)); return false;
        });
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenAnswer(call -> {
            capture(call.getArgument(0), call.getArgument(1)); return 101L;
        });
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
            capture(call.getArgument(0), call.getArgument(1)); return List.of();
        });
    }
    @AfterEach void close() { analyzer.close(); }

    @Test void defaults_limit_visible_statuses_and_exact_notice_removes_only_the_default_status_filter() {
        animals.search(Search.empty(), page("createdAt", false));
        assertThat(calls.getFirst().sql()).contains("a.status IN ('NOTICE','PROTECT')");
        assertThat(calls.getLast().sql()).endsWith("ORDER BY a.created_at DESC NULLS LAST,a.id ASC LIMIT :limit OFFSET :offset");
        assertThat(calls.getLast().parameters()).containsEntry("limit", 20).containsEntry("offset", 0L);
        calls.clear();
        animals.search(search(null, null, "  공고-1  ", null), page("createdAt", false));
        assertThat(calls.getFirst().sql()).contains("a.apms_notice_no=:notice").doesNotContain("a.status IN");
        assertThat(calls.getFirst().parameters()).containsEntry("notice", "공고-1");
        calls.clear();
        animals.search(search(null, null, "공고-1", AnimalStatus.ADOPTED), page("createdAt", false));
        assertThat(calls.getFirst().sql()).contains("a.apms_notice_no=:notice", "a.status=:status");
        assertThat(calls.getFirst().parameters()).containsEntry("status", "ADOPTED");
    }

    @Test void all_filters_combine_and_breed_address_wildcards_are_literal() {
        LocalDate from = LocalDate.of(2026, 1, 1), to = from.plusDays(365);
        Search request = new Search(Species.DOG, " Mix%_! ", Gender.FEMALE, NeuterStatus.NO,
                AnimalStatus.PROTECT, 2, 9, " 서울% 강남_ ", "역삼!", "검은 말티즈", "N-1", 88L, from, to);
        animals.search(request, page("age", true));
        Call count = calls.get(1);
        assertThat(count.sql()).contains("a.apms_notice_no=:notice", "a.species=:species", "a.gender=:gender",
                "a.neuter_status=:neuter", "a.shelter_id=:shelter", "a.happen_date BETWEEN :intakeFrom AND :intakeTo",
                "a.status=:status", "a.birth_year<=:maxBirthYear", "a.birth_year>=:minBirthYear",
                "lower(s.address) LIKE :region0 ESCAPE '!'", "lower(s.address) LIKE :region1 ESCAPE '!'",
                "lower(s.address) LIKE :city0 ESCAPE '!'", "lower(a.breed) LIKE :breedPart ESCAPE '!'",
                "(ad.tokens || sd.tokens) @@ plainto_tsquery('simple',:keywordTokens)");
        assertThat(count.parameters()).containsEntry("species", "DOG").containsEntry("gender", "FEMALE")
                .containsEntry("neuter", "NO").containsEntry("shelter", 88L).containsEntry("intakeFrom", from)
                .containsEntry("intakeTo", to).containsEntry("maxBirthYear", LocalDate.now().getYear() - 2)
                .containsEntry("minBirthYear", LocalDate.now().getYear() - 9).containsEntry("region0", "%서울!%%")
                .containsEntry("region1", "%강남!_%").containsEntry("city0", "%역삼!!%")
                .containsEntry("breedPart", "%mix!%!_!!%").containsEntry("breedExact", "mix%_!");
        assertThat(calls.getLast().sql()).contains("a.birth_year DESC NULLS LAST,a.id ASC");
    }

    @Test void allowed_sort_aliases_preserve_both_directions_and_age_reversal() {
        Map<String, String> columns = Map.ofEntries(Map.entry("createdAt", "created_at"), Map.entry("created_at", "created_at"),
                Map.entry("happenDate", "happen_date"), Map.entry("happen_date", "happen_date"),
                Map.entry("updatedAt", "updated_at"), Map.entry("updated_at", "updated_at"),
                Map.entry("noticeEndDate", "notice_end_date"), Map.entry("notice_end_date", "notice_end_date"),
                Map.entry("birthYear", "birth_year"), Map.entry("birth_year", "birth_year"), Map.entry("age", "birth_year"),
                Map.entry("apmsDesertionNo", "apms_desertion_no"), Map.entry("apms_desertion_no", "apms_desertion_no"),
                Map.entry("favoriteCount", "favorite_count"), Map.entry("favorite_count", "favorite_count"));
        for (var entry : columns.entrySet()) for (boolean asc : List.of(true, false)) {
            animals.search(Search.empty(), page(entry.getKey(), asc));
            String direction = (entry.getKey().equals("age") ? !asc : asc) ? " ASC" : " DESC";
            assertThat(calls.getLast().sql()).contains("ORDER BY a." + entry.getValue() + direction + " NULLS LAST,a.id ASC");
        }
    }

    @Test void nori_keyword_uses_both_documents_and_relation_queries_keep_relation_evidence() {
        animals.search(search("검은색 말티즈", null, null, null), page("relevance", true));
        assertThat(calls).hasSize(3);
        assertThat(calls.getFirst().sql()).contains("a.search_dirty", "s.search_dirty", "a.status IN ('NOTICE','PROTECT')");
        String sql = calls.getLast().sql();
        assertThat(sql).contains("pawbridge_animal.animal_search_documents", "pawbridge_animal.shelter_search_documents",
                "(ad.tokens || sd.tokens) @@ plainto_tsquery('simple',:keywordTokens)", "UNION ALL SELECT resident.id",
                "ts_rank_cd(", "ORDER BY relevance DESC,a.created_at DESC NULLS LAST,a.id ASC").doesNotContain(" LIKE ");
        assertThat((String) calls.getLast().parameters().get("keywordTokens")).contains("검", "말티즈").doesNotContain("색");
        calls.clear();
        animals.search(search("귀가 접힌", null, null, null), page("relevance", false));
        assertThat(calls.getLast().sql()).contains("ad.relation_tokens @@ plainto_tsquery('simple',:relation)")
                .doesNotContain("UNION ALL SELECT resident.id");
        assertThat(calls.getLast().parameters()).containsKey("relation");
    }

    @Test void related_pending_projection_stops_before_count_and_content_but_plain_breed_does_not_require_projection() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(true);
        assertThatThrownBy(() -> animals.search(search("강아지", null, null, null), page("createdAt", false)))
                .isInstanceOf(SearchProjectionPendingException.class);
        verify(jdbc, never()).queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class));
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        animals.search(search(null, "말티즈", null, null), page("relevance", false));
        assertThat(calls.getLast().sql()).contains("lower(a.breed) LIKE :breedPart").doesNotContain("search_documents");
    }

    @Test void empty_and_beyond_count_pages_keep_source_page_fields_without_content_query() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        Page empty = animals.search(Search.empty(), page("createdAt", false));
        assertThat(empty).isEqualTo(new Page(List.of(), 0, 20, 0, 0, true, true));
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        Page beyond = animals.search(Search.empty(), new PageRequest(499, 20, List.of(), true));
        assertThat(beyond.number()).isEqualTo(499);
        assertThat(beyond.totalElements()).isEqualTo(1);
        assertThat(beyond.last()).isTrue();
        assertThat(beyond.first()).isFalse();
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    }

    @Test void missing_detail_has_no_status_filter_and_raises_source_not_found() {
        assertThatThrownBy(() -> animals.findById(72)).isInstanceOf(AnimalNotFoundException.class);
        assertThat(calls.getFirst().sql()).contains("pawbridge_animal.animals", "pawbridge_animal.shelters", "WHERE a.id=:id")
                .doesNotContain("status IN", "search_documents");
        assertThat(calls.getFirst().parameters()).containsEntry("id", 72L);
    }

    @Test void all_query_methods_are_read_only_repeatable_read_with_five_second_timeouts() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new SQLException("synthetic unavailable database"));
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var configured = new JdbcAnimalDiscovery(source, manager, analyzer);
        assertThatThrownBy(() -> configured.search(Search.empty(), page("createdAt", false))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> configured.findByApmsDesertionNo("D-72")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> configured.findExpiringSoonAnimals(page("unknown", false))).isInstanceOf(DataAccessException.class);
        verify(manager, times(3)).getTransaction(argThat(definition -> definition.isReadOnly()
                && definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ && definition.getTimeout() == 5));
        verify(statement, times(3)).setQueryTimeout(5);
    }

    @Test void apms_number_binds_exact_value_and_reads_one_shelter_join_without_status_filter() throws Exception {
        java.sql.ResultSet row = mock(java.sql.ResultSet.class);
        when(row.getLong("id")).thenReturn(72L);
        when(row.getLong("shelter_id")).thenReturn(8L);
        when(row.getString("species")).thenReturn("DOG");
        when(row.getString("gender")).thenReturn("UNKNOWN");
        when(row.getString("neuter_status")).thenReturn("UNKNOWN");
        when(row.getString("status")).thenReturn("ADOPTED");
        when(row.getString("api_source")).thenReturn("APMS_ANIMAL");
        when(row.getString("apms_desertion_no")).thenReturn(" D-72 ");
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
            capture(call.getArgument(0), call.getArgument(1));
            RowMapper<Details> mapper = call.getArgument(2);
            return List.of(mapper.mapRow(row, 0));
        });
        Details detail = animals.findByApmsDesertionNo(" D-72 ");
        assertThat(detail.id()).isEqualTo(72L);
        assertThat(detail.shelterId()).isEqualTo(8L);
        assertThat(detail.status()).isEqualTo(AnimalStatus.ADOPTED);
        assertThat(detail.apmsDesertionNo()).isEqualTo(" D-72 ");
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().sql()).contains("JOIN pawbridge_animal.shelters", "WHERE a.apms_desertion_no=:number")
                .doesNotContain("status IN", "search_documents", "count(*)");
        assertThat(calls.getFirst().parameters()).containsOnly(entry("number", " D-72 "));
        verify(jdbc, never()).queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class));
    }

    @Test void missing_apms_and_database_failures_propagate_without_fallback_queries() {
        assertThatThrownBy(() -> animals.findByApmsDesertionNo("missing")).isInstanceOf(AnimalNotFoundException.class);
        var failure = new org.springframework.dao.DataAccessResourceFailureException("synthetic unavailable");
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenThrow(failure);
        assertThatThrownBy(() -> animals.findByApmsDesertionNo("D-72")).isSameAs(failure);
        verify(jdbc, times(2)).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        verify(jdbc, never()).queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class));
    }

    @Test void expiring_soon_uses_protect_inclusive_jvm_dates_and_fixed_order_ignoring_single_requested_sort() {
        LocalDate before = LocalDate.now();
        animals.findExpiringSoonAnimals(new PageRequest(2, 20, List.of(new Order("unknown", false)), true));
        LocalDate after = LocalDate.now();
        assertThat(calls).hasSize(2);
        Call count = calls.getFirst(), content = calls.getLast();
        assertThat(count.sql()).contains("a.status=:status", "a.notice_end_date BETWEEN :today AND :deadline")
                .doesNotContain("search_documents", "search_dirty");
        assertThat(count.parameters()).containsEntry("status", "PROTECT");
        LocalDate today = (LocalDate) count.parameters().get("today");
        assertThat(today).isBetween(before, after);
        assertThat(count.parameters()).containsEntry("deadline", today.plusDays(3));
        assertThat(content.sql()).endsWith("ORDER BY a.notice_end_date ASC NULLS LAST,a.id ASC LIMIT :limit OFFSET :offset");
        assertThat(content.parameters()).containsEntry("limit", 20).containsEntry("offset", 40L);
    }

    @Test void expiring_empty_and_beyond_count_pages_do_not_read_content() {
        var totals = new java.util.concurrent.atomic.AtomicInteger();
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenAnswer(call -> {
            capture(call.getArgument(0), call.getArgument(1));
            return (long) totals.getAndIncrement();
        });
        Page empty = animals.findExpiringSoonAnimals(new PageRequest(0, 20, List.of(), true));
        assertThat(empty).isEqualTo(new Page(List.of(), 0, 20, 0, 0, true, true));
        Page beyond = animals.findExpiringSoonAnimals(new PageRequest(499, 20, List.of(), true));
        assertThat(beyond).isEqualTo(new Page(List.of(), 499, 20, 1, 1, false, true));
        assertThat(calls).hasSize(2);
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    }

    @Test void expiring_page_bounds_and_multiple_sorts_fail_before_query_sql() {
        for (PageRequest invalid : List.of(new PageRequest(0, 20, List.of(), false),
                new PageRequest(0, 101, List.of(), true), new PageRequest(500, 20, List.of(), true),
                new PageRequest(0, 20, List.of(new Order("createdAt", true), new Order("id", true)), true))) {
            assertThatThrownBy(() -> animals.findExpiringSoonAnimals(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(jdbc);
    }

    private static Search search(String keyword, String breed, String notice, AnimalStatus status) {
        return new Search(null, breed, null, null, status, null, null, null, null, keyword, notice, null, null, null);
    }
    private static PageRequest page(String property, boolean ascending) {
        return new PageRequest(0, 20, List.of(new Order(property, ascending)), true);
    }
    private void capture(String sql, SqlParameterSource source) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (String key : source.getParameterNames()) parameters.put(key, source.getValue(key));
        calls.add(new Call(sql, parameters));
    }
    private record Call(String sql, Map<String, Object> parameters) { }
}
