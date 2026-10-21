package com.pawbridge.backend.animal.internal.command.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class PostgresqlSearchProjectorTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final KoreanSearchAnalyzer analyzer = mock(KoreanSearchAnalyzer.class);
    private final PostgresqlSearchProjector projector = new PostgresqlSearchProjector(jdbc, transaction, analyzer);
    private boolean callbackActive;
    private int callbacks;

    @BeforeEach
    void executeOnlyTheSuppliedTransactionCallback() {
        doAnswer(call -> {
            assertThat(callbackActive).isFalse();
            callbackActive = true;
            callbacks++;
            try {
                TransactionCallback<?> callback = call.getArgument(0);
                return callback.doInTransaction(new SimpleTransactionStatus());
            } finally {
                callbackActive = false;
            }
        }).when(transaction).execute(any());
        doAnswer(call -> {
            assertThat(callbackActive).isTrue();
            return null;
        }).when(jdbc).execute(anyString());
    }

    @AfterEach
    void clearOnlyTheTransactionMarkerSetByThisTest() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void givenInvalidLimits__whenAnyRefreshIsRequested__thenRejectBeforeTransactionOrSql() {
        for (IntUnaryOperator refresh : refreshMethods()) {
            for (int limit : new int[]{-1, 0, 101}) {
                assertThatThrownBy(() -> refresh.applyAsInt(limit)).isInstanceOf(IllegalArgumentException.class);
            }
        }
        verifyNoInteractions(transaction, jdbc, analyzer);
    }

    @Test
    void givenAnActualCallerTransaction__whenAnyRefreshIsRequested__thenRejectWithoutNestedTransactionOrSql() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        for (IntUnaryOperator refresh : refreshMethods()) {
            assertThatThrownBy(() -> refresh.applyAsInt(50)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(transaction, jdbc, analyzer);
    }

    @Test
    void givenDirtyAnimals__whenRefreshed__thenLockOneBoundedPageAndReuseTheExistingWriterInsideTheCallback() throws Exception {
        rows(List.of(animalRow()));
        animalTerms();

        assertThat(projector.refreshDirtyAnimals(2)).isEqualTo(1);

        var sql = ArgumentCaptor.forClass(String.class);
        var order = inOrder(jdbc);
        order.verify(jdbc).execute("SET LOCAL lock_timeout='1s'");
        order.verify(jdbc).execute("SET LOCAL statement_timeout='4s'");
        order.verify(jdbc).queryForList(sql.capture(), eq(2));
        assertThat(sql.getValue()).contains("FROM pawbridge_animal.animals a WHERE search_dirty",
                "ORDER BY id LIMIT ? FOR UPDATE OF a SKIP LOCKED").doesNotContain("NOT EXISTS", "analyzer_version");
        order.verify(jdbc).update(startsWith("INSERT INTO pawbridge_animal.animal_search_documents"), any(Object[].class));
        order.verify(jdbc).update("UPDATE pawbridge_animal.animals SET search_dirty=false WHERE id=?", 42L);
        verify(analyzer).animalTerms("믹스견", "갈색", "귀", "순함", "서울");
        assertThat(callbacks).isEqualTo(1);
    }

    @Test
    void givenAnimalIntegrityAudit__whenNoUnlockedRowsAreReturned__thenCheckRevisionAndVersionAndReturnZero() {
        rows(List.of());

        assertThat(projector.refreshAnimals(100)).isZero();

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(KoreanSearchAnalyzer.VERSION), eq(100));
        assertThat(sql.getValue()).contains("search_dirty OR NOT EXISTS (SELECT 1 FROM pawbridge_animal.animal_search_documents d",
                "d.animal_id=a.id AND d.source_revision=a.search_revision AND d.analyzer_version=?",
                "ORDER BY id LIMIT ? FOR UPDATE OF a SKIP LOCKED");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(analyzer);
        assertThat(callbacks).isEqualTo(1);
    }

    @Test
    void givenDirtyShelters__whenRefreshed__thenLockTheSourceAndReuseTheExistingShelterWriter() throws Exception {
        rows(List.of(Map.of("id", 7L, "search_revision", 3L, "name", "서울 센터", "address", "강남")));
        when(analyzer.shelterTerms("서울 센터", "강남"))
                .thenReturn(new KoreanSearchAnalyzer.ShelterTerms("서울 센터", "강남"));

        assertThat(projector.refreshDirtyShelters(1)).isEqualTo(1);

        var sql = ArgumentCaptor.forClass(String.class);
        var order = inOrder(jdbc);
        order.verify(jdbc).execute("SET LOCAL lock_timeout='1s'");
        order.verify(jdbc).execute("SET LOCAL statement_timeout='4s'");
        order.verify(jdbc).queryForList(sql.capture(), eq(1));
        assertThat(sql.getValue()).contains("FROM pawbridge_animal.shelters s WHERE search_dirty",
                "ORDER BY id LIMIT ? FOR UPDATE OF s SKIP LOCKED").doesNotContain("NOT EXISTS", "analyzer_version");
        order.verify(jdbc).update(startsWith("INSERT INTO pawbridge_animal.shelter_search_documents"), any(Object[].class));
        order.verify(jdbc).update("UPDATE pawbridge_animal.shelters SET search_dirty=false WHERE id=?", 7L);
        assertThat(callbacks).isEqualTo(1);
    }

    @Test
    void givenShelterIntegrityAudit__whenNoUnlockedRowsAreReturned__thenCheckRevisionAndVersionWithoutWriting() {
        rows(List.of());

        assertThat(projector.refreshShelters(50)).isZero();

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(KoreanSearchAnalyzer.VERSION), eq(50));
        assertThat(sql.getValue()).contains("search_dirty OR NOT EXISTS (SELECT 1 FROM pawbridge_animal.shelter_search_documents d",
                "d.shelter_id=s.id AND d.source_revision=s.search_revision AND d.analyzer_version=?",
                "ORDER BY id LIMIT ? FOR UPDATE OF s SKIP LOCKED");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(analyzer);
        assertThat(callbacks).isEqualTo(1);
    }

    @Test
    void givenAProjectionWriteFailure__whenRefreshed__thenPropagateTheSameFailureWithoutClearingDirty() throws Exception {
        rows(List.of(animalRow()));
        animalTerms();
        var failure = new DataAccessResourceFailureException("synthetic write failure");
        doThrow(failure).when(jdbc).update(startsWith("INSERT INTO pawbridge_animal.animal_search_documents"), any(Object[].class));

        assertThatThrownBy(() -> projector.refreshDirtyAnimals(1)).isSameAs(failure);

        verify(jdbc, never()).update("UPDATE pawbridge_animal.animals SET search_dirty=false WHERE id=?", 42L);
        assertThat(callbackActive).isFalse();
        assertThat(callbacks).isEqualTo(1);
    }

    @Test
    void givenBothSourceTypes__whenRefreshedSequentially__thenUseSeparateTransactionCallbacks() {
        rows(List.of());

        assertThat(projector.refreshDirtyShelters(50)).isZero();
        assertThat(projector.refreshDirtyAnimals(50)).isZero();

        assertThat(callbacks).isEqualTo(2);
        assertThat(callbackActive).isFalse();
    }

    private List<IntUnaryOperator> refreshMethods() {
        return List.of(projector::refreshAnimals, projector::refreshDirtyAnimals,
                projector::refreshShelters, projector::refreshDirtyShelters);
    }

    private void rows(List<Map<String, Object>> selected) {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> {
            assertThat(callbackActive).isTrue();
            return selected;
        });
    }

    private Map<String, Object> animalRow() {
        return Map.of("id", 42L, "search_revision", 9L, "breed", "믹스견", "color", "갈색",
                "special_mark", "귀", "description", "순함", "happen_place", "서울");
    }

    private void animalTerms() throws Exception {
        when(analyzer.animalTerms("믹스견", "갈색", "귀", "순함", "서울"))
                .thenReturn(new KoreanSearchAnalyzer.AnimalTerms("귀", "믹스견", "갈색", "순함", "서울", "관계"));
    }
}
