package com.pawbridge.backend.animal.internal.recommendation.infrastructure

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.ApiSource
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Details
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.NeuterStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations.Card
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class JdbcAnimalRecommendationsTest {
    private val animals: AnimalDiscovery = mock(AnimalDiscovery::class.java)
    private val python: PythonAnimalRecommendations = mock(PythonAnimalRecommendations::class.java)
    private val jdbc: NamedParameterJdbcOperations = mock(NamedParameterJdbcOperations::class.java)
    private val manager = LocalTransactions()
    private val reads = TransactionTemplate(manager)
    private val recommendations = JdbcAnimalRecommendations(animals, python, jdbc, reads)

    @Test
    fun givenAdoptedSourceAndStaleCandidates__whenRecommending__thenKeepRemoteOrderAndOnlyCurrentSameSpeciesNoticeOrProtect() {
        `when`(animals.findById(1)).thenReturn(source(Species.DOG, AnimalStatus.ADOPTED))
        `when`(python.recommend(1, "DOG")).thenReturn(listOf(1L, 4L, 2L, 3L, 4L, 5L))
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Card>>())).thenReturn(
            listOf(
                card(5, Species.DOG, AnimalStatus.NOTICE),
                card(4, Species.DOG, AnimalStatus.PROTECT),
                card(2, Species.DOG, AnimalStatus.RETURNED),
                card(3, Species.CAT, AnimalStatus.NOTICE),
            ),
        )

        assertThat(recommendations.recommend(1).map { card -> card.id }).containsExactly(4L, 5L)

        val sql = ArgumentCaptor.forClass(String::class.java)
        val parameters = ArgumentCaptor.forClass(MapSqlParameterSource::class.java)
        val order = inOrder(animals, python, jdbc)
        order.verify(animals).findById(1)
        order.verify(python).recommend(1, "DOG")
        order.verify(jdbc).query(sql.capture(), parameters.capture(), any<RowMapper<Card>>())
        assertThat(sql.value).contains("pawbridge_animal.animals", "JOIN pawbridge_animal.shelters", "a.id IN (:ids)")
            .doesNotContain("embedding", "vector", "ORDER BY")
        assertThat(parameters.value.getValue("ids")).isEqualTo(listOf(4L, 2L, 3L, 5L))
        verifyNoMoreInteractions(animals, python, jdbc)
    }

    @Test
    fun givenDeletedCandidate__whenRecheckingCurrentRows__thenOmitItWithoutReplacingRemoteOrder() {
        `when`(animals.findById(1)).thenReturn(source(Species.CAT, AnimalStatus.PROTECT))
        `when`(python.recommend(1, "CAT")).thenReturn(listOf(8L, 9L))
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Card>>()))
            .thenReturn(listOf(card(9, Species.CAT, AnimalStatus.NOTICE)))

        assertThat(recommendations.recommend(1).map { card -> card.id }).containsExactly(9L)
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    fun givenMalformedRemoteIds__whenRecommending__thenFailUnavailableBeforeCandidateLookup(ids: List<Long?>?) {
        `when`(animals.findById(1)).thenReturn(source(Species.DOG, AnimalStatus.PROTECT))
        `when`(python.recommend(1, "DOG")).thenReturn(ids)

        assertThatThrownBy { recommendations.recommend(1) }
            .isInstanceOf(RecommendationUnavailableException::class.java)
            .hasNoCause()
        verifyNoInteractions(jdbc)
    }

    @Test
    fun givenMissingSourceOrUnsupportedSpecies__whenRecommending__thenPreserveMissingOrEmptyBeforePython() {
        `when`(animals.findById(1)).thenThrow(AnimalNotFoundException())
        assertThatThrownBy { recommendations.recommend(1) }.isInstanceOf(AnimalNotFoundException::class.java)
        `when`(animals.findById(2)).thenReturn(source(Species.ETC, AnimalStatus.PROTECT))

        assertThat(recommendations.recommend(2)).isEmpty()

        verifyNoInteractions(python, jdbc)
    }

    @ParameterizedTest
    @MethodSource("emptyCandidates")
    fun givenEmptyOrOnlySelfIds__whenRecommending__thenReturnEmptyWithoutCandidateLookup(ids: List<Long?>?) {
        `when`(animals.findById(1)).thenReturn(source(Species.CAT, AnimalStatus.PROTECT))
        `when`(python.recommend(1, "CAT")).thenReturn(ids)

        assertThat(recommendations.recommend(1)).isEmpty()

        verifyNoInteractions(jdbc)
    }

    @Test
    fun givenPythonFailureContainingSecrets__whenRecommending__thenFailUnavailableWithNoCauseOrCandidateRead() {
        `when`(animals.findById(1)).thenReturn(source(Species.DOG, AnimalStatus.PROTECT))
        `when`(python.recommend(1, "DOG")).thenThrow(IllegalStateException("synthetic-private-token/body"))

        assertThatThrownBy { recommendations.recommend(1) }
            .isInstanceOf(RecommendationUnavailableException::class.java)
            .hasNoCause()
            .hasMessage("추천 자료를 준비 중입니다. 잠시 후 다시 시도해 주세요")
        verifyNoInteractions(jdbc)
    }

    @Test
    fun givenSeparateSourceAndCandidateTransactions__whenCallingPython__thenNoDatabaseTransactionIsActive() {
        val remote = AtomicInteger()
        `when`(animals.findById(1)).thenAnswer {
            reads.execute { status ->
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
                source(Species.DOG, AnimalStatus.PROTECT)
            }
        }
        `when`(python.recommend(1, "DOG")).thenAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            remote.incrementAndGet()
            listOf(4L)
        }
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Card>>())).thenAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            listOf(card(4, Species.DOG, AnimalStatus.PROTECT))
        }

        assertThat(recommendations.recommend(1)).hasSize(1)

        assertThat(remote.get()).isOne()
        assertThat(manager.begins).isEqualTo(2)
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
    }

    @Test
    fun givenStoredRecommendationFields__whenMappingCandidateRows__thenKeepAllFieldsAndOriginalAgeArithmetic() {
        `when`(animals.findById(1)).thenReturn(source(Species.DOG, AnimalStatus.PROTECT))
        `when`(python.recommend(1, "DOG")).thenReturn(listOf(4L))
        val row = mock(ResultSet::class.java)
        `when`(row.getObject("id", Long::class.javaObjectType)).thenReturn(4L)
        `when`(row.getString("apms_notice_no")).thenReturn("공고-4")
        `when`(row.getString("species")).thenReturn("DOG")
        `when`(row.getString("breed")).thenReturn("믹스")
        `when`(row.getString("gender")).thenReturn("FEMALE")
        `when`(row.getObject("birth_year", Int::class.javaObjectType)).thenReturn(LocalDate.now().year - 3)
        `when`(row.getString("weight")).thenReturn("0.69(Kg)")
        `when`(row.getString("color")).thenReturn("레몬색&흰색")
        val happened = LocalDate.of(2026, 9, 29)
        val ended = LocalDate.of(2026, 10, 9)
        val created = LocalDateTime.of(2026, 9, 29, 13, 4, 5, 123456000)
        `when`(row.getObject("happen_date", LocalDate::class.java)).thenReturn(happened)
        `when`(row.getString("special_mark")).thenReturn("귀 반점")
        `when`(row.getString("status")).thenReturn("PROTECT")
        `when`(row.getObject("notice_end_date", LocalDate::class.java)).thenReturn(ended)
        `when`(row.getString("image_url")).thenReturn("https://example.test/a.jpg")
        `when`(row.getObject("favorite_count", Int::class.javaObjectType)).thenReturn(7)
        `when`(row.getObject("shelter_id", Long::class.javaObjectType)).thenReturn(8L)
        `when`(row.getString("shelter_name")).thenReturn("보호소")
        `when`(row.getObject("created_at", LocalDateTime::class.java)).thenReturn(created)
        `when`(jdbc.query(anyString(), any(MapSqlParameterSource::class.java), any<RowMapper<Card>>()))
            .thenAnswer { call -> listOf(call.getArgument<RowMapper<Card>>(2).mapRow(row, 0)) }

        assertThat(recommendations.recommend(1)).containsExactly(
            Card(
                4L, "공고-4", Species.DOG, "믹스", Gender.FEMALE,
                LocalDate.now().year - 3, 3, "0.69(Kg)", "레몬색&흰색", happened, "귀 반점", AnimalStatus.PROTECT,
                ended, "https://example.test/a.jpg", 7, 8L, "보호소", created,
            ),
        )
        `when`(row.getObject("birth_year", Int::class.javaObjectType)).thenReturn(null)
        assertThat(recommendations.recommend(1).first().age).isNull()
    }

    private fun source(species: Species, status: AnimalStatus): Details {
        return Details(
            1L, null, species, null, Gender.UNKNOWN, NeuterStatus.UNKNOWN, null, null, null,
            null, null, status, null, null, null, null, null, null, null, null, 1L, null, null, null,
            null, ApiSource.MANUAL, null, null,
        )
    }

    private fun card(id: Long, species: Species, status: AnimalStatus): Card {
        return Card(
            id, null, species, null, Gender.UNKNOWN, null, null, null, null, null, null, status,
            null, null, null, null, null, null,
        )
    }

    private class LocalTransactions : AbstractPlatformTransactionManager() {
        var begins = 0

        override fun doGetTransaction(): Any {
            return Any()
        }

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            begins++
        }

        override fun doCommit(status: DefaultTransactionStatus) {
        }

        override fun doRollback(status: DefaultTransactionStatus) {
        }
    }

    companion object {
        @JvmStatic
        fun invalidIds(): Stream<List<Long?>?> = Stream.of(
            null,
            listOf(2L, null),
            listOf(0L),
            listOf(-1L),
            listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L),
        )

        @JvmStatic
        fun emptyCandidates(): Stream<List<Long?>?> = Stream.of(listOf(), listOf(1L, 1L))
    }
}
