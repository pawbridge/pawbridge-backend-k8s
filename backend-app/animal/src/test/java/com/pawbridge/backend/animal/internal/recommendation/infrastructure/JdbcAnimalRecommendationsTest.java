package com.pawbridge.backend.animal.internal.recommendation.infrastructure;

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.*;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException;
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations.Card;
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcAnimalRecommendationsTest {
    private final AnimalDiscovery animals = mock(AnimalDiscovery.class);
    private final PythonAnimalRecommendations python = mock(PythonAnimalRecommendations.class);
    private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    private final LocalTransactions manager = new LocalTransactions();
    private final TransactionTemplate reads = new TransactionTemplate(manager);
    private final JdbcAnimalRecommendations recommendations = new JdbcAnimalRecommendations(animals, python, jdbc, reads);

    @Test
    void givenAdoptedSourceAndStaleCandidates__whenRecommending__thenKeepRemoteOrderAndOnlyCurrentSameSpeciesNoticeOrProtect() {
        when(animals.findById(1)).thenReturn(source(Species.DOG, AnimalStatus.ADOPTED));
        when(python.recommend(1, "DOG")).thenReturn(List.of(1L, 4L, 2L, 3L, 4L, 5L));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Card>>any()))
                .thenReturn(List.of(card(5, Species.DOG, AnimalStatus.NOTICE), card(4, Species.DOG, AnimalStatus.PROTECT),
                        card(2, Species.DOG, AnimalStatus.RETURNED), card(3, Species.CAT, AnimalStatus.NOTICE)));

        assertThat(recommendations.recommend(1)).extracting(Card::id).containsExactly(4L, 5L);

        var sql = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        var order = inOrder(animals, python, jdbc);
        order.verify(animals).findById(1);
        order.verify(python).recommend(1, "DOG");
        order.verify(jdbc).query(sql.capture(), parameters.capture(), org.mockito.ArgumentMatchers.<RowMapper<Card>>any());
        assertThat(sql.getValue()).contains("pawbridge_animal.animals", "JOIN pawbridge_animal.shelters", "a.id IN (:ids)")
                .doesNotContain("embedding", "vector", "ORDER BY");
        assertThat(parameters.getValue().getValue("ids")).isEqualTo(List.of(4L, 2L, 3L, 5L));
        verifyNoMoreInteractions(animals, python, jdbc);
    }

    @Test
    void givenDeletedCandidate__whenRecheckingCurrentRows__thenOmitItWithoutReplacingRemoteOrder() {
        when(animals.findById(1)).thenReturn(source(Species.CAT, AnimalStatus.PROTECT));
        when(python.recommend(1, "CAT")).thenReturn(List.of(8L, 9L));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Card>>any()))
                .thenReturn(List.of(card(9, Species.CAT, AnimalStatus.NOTICE)));
        assertThat(recommendations.recommend(1)).extracting(Card::id).containsExactly(9L);
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    void givenMalformedRemoteIds__whenRecommending__thenFailUnavailableBeforeCandidateLookup(List<Long> ids) {
        when(animals.findById(1)).thenReturn(source(Species.DOG, AnimalStatus.PROTECT));
        when(python.recommend(1, "DOG")).thenReturn(ids);
        assertThatThrownBy(() -> recommendations.recommend(1)).isInstanceOf(RecommendationUnavailableException.class)
                .hasNoCause();
        verifyNoInteractions(jdbc);
    }

    static Stream<List<Long>> invalidIds() {
        return Stream.of(null, Arrays.asList(2L, null), List.of(0L), List.of(-1L),
                List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L));
    }

    @Test
    void givenMissingSourceOrUnsupportedSpecies__whenRecommending__thenPreserveMissingOrEmptyBeforePython() {
        when(animals.findById(1)).thenThrow(new AnimalNotFoundException());
        assertThatThrownBy(() -> recommendations.recommend(1)).isInstanceOf(AnimalNotFoundException.class);
        when(animals.findById(2)).thenReturn(source(Species.ETC, AnimalStatus.PROTECT));
        assertThat(recommendations.recommend(2)).isEmpty();
        verifyNoInteractions(python, jdbc);
    }

    @ParameterizedTest
    @MethodSource("emptyCandidates")
    void givenEmptyOrOnlySelfIds__whenRecommending__thenReturnEmptyWithoutCandidateLookup(List<Long> ids) {
        when(animals.findById(1)).thenReturn(source(Species.CAT, AnimalStatus.PROTECT));
        when(python.recommend(1, "CAT")).thenReturn(ids);
        assertThat(recommendations.recommend(1)).isEmpty();
        verifyNoInteractions(jdbc);
    }
    static Stream<List<Long>> emptyCandidates() { return Stream.of(List.of(), List.of(1L, 1L)); }

    @Test
    void givenPythonFailureContainingSecrets__whenRecommending__thenFailUnavailableWithNoCauseOrCandidateRead() {
        when(animals.findById(1)).thenReturn(source(Species.DOG, AnimalStatus.PROTECT));
        when(python.recommend(1, "DOG")).thenThrow(new IllegalStateException("synthetic-private-token/body"));
        assertThatThrownBy(() -> recommendations.recommend(1)).isInstanceOf(RecommendationUnavailableException.class)
                .hasNoCause().hasMessage("추천 자료를 준비 중입니다. 잠시 후 다시 시도해 주세요");
        verifyNoInteractions(jdbc);
    }

    @Test
    void givenSeparateSourceAndCandidateTransactions__whenCallingPython__thenNoDatabaseTransactionIsActive() {
        AtomicInteger remote = new AtomicInteger();
        when(animals.findById(1)).thenAnswer(call -> reads.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return source(Species.DOG, AnimalStatus.PROTECT);
        }));
        when(python.recommend(1, "DOG")).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            remote.incrementAndGet();
            return List.of(4L);
        });
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Card>>any()))
                .thenAnswer(call -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    return List.of(card(4, Species.DOG, AnimalStatus.PROTECT));
                });
        assertThat(recommendations.recommend(1)).hasSize(1);
        assertThat(remote.get()).isOne();
        assertThat(manager.begins).isEqualTo(2);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void givenStoredRecommendationFields__whenMappingCandidateRows__thenKeepAllFieldsAndOriginalAgeArithmetic() throws Exception {
        when(animals.findById(1)).thenReturn(source(Species.DOG, AnimalStatus.PROTECT));
        when(python.recommend(1, "DOG")).thenReturn(List.of(4L));
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", Long.class)).thenReturn(4L);
        when(row.getString("apms_notice_no")).thenReturn("공고-4");
        when(row.getString("species")).thenReturn("DOG");
        when(row.getString("breed")).thenReturn("믹스");
        when(row.getString("gender")).thenReturn("FEMALE");
        when(row.getObject("birth_year", Integer.class)).thenReturn(LocalDate.now().getYear() - 3);
        when(row.getString("weight")).thenReturn("0.69(Kg)");
        when(row.getString("color")).thenReturn("레몬색&흰색");
        LocalDate happened = LocalDate.of(2026, 9, 29), ended = LocalDate.of(2026, 10, 9);
        LocalDateTime created = LocalDateTime.of(2026, 9, 29, 13, 4, 5, 123456000);
        when(row.getObject("happen_date", LocalDate.class)).thenReturn(happened);
        when(row.getString("special_mark")).thenReturn("귀 반점");
        when(row.getString("status")).thenReturn("PROTECT");
        when(row.getObject("notice_end_date", LocalDate.class)).thenReturn(ended);
        when(row.getString("image_url")).thenReturn("https://example.test/a.jpg");
        when(row.getObject("favorite_count", Integer.class)).thenReturn(7);
        when(row.getObject("shelter_id", Long.class)).thenReturn(8L);
        when(row.getString("shelter_name")).thenReturn("보호소");
        when(row.getObject("created_at", LocalDateTime.class)).thenReturn(created);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Card>>any()))
                .thenAnswer(call -> List.of(call.<RowMapper<Card>>getArgument(2).mapRow(row, 0)));

        assertThat(recommendations.recommend(1)).containsExactly(new Card(4L, "공고-4", Species.DOG, "믹스", Gender.FEMALE,
                LocalDate.now().getYear() - 3, 3, "0.69(Kg)", "레몬색&흰색", happened, "귀 반점", AnimalStatus.PROTECT,
                ended, "https://example.test/a.jpg", 7, 8L, "보호소", created));
        when(row.getObject("birth_year", Integer.class)).thenReturn(null);
        assertThat(recommendations.recommend(1).getFirst().age()).isNull();
    }

    private static Details source(Species species, AnimalStatus status) {
        return new Details(1L, null, species, null, Gender.UNKNOWN, NeuterStatus.UNKNOWN, null, null, null,
                null, null, status, null, null, null, null, null, null, null, null, 1L, null, null, null,
                null, ApiSource.MANUAL, null, null);
    }
    private static Card card(long id, Species species, AnimalStatus status) {
        return new Card(id, null, species, null, Gender.UNKNOWN, null, null, null, null, null, null, status,
                null, null, null, null, null, null);
    }
    private static final class LocalTransactions extends AbstractPlatformTransactionManager {
        private int begins;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) { begins++; }
        protected void doCommit(DefaultTransactionStatus status) { }
        protected void doRollback(DefaultTransactionStatus status) { }
    }
}
