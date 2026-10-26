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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
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
    private val animals: AnimalDiscovery = mockk<AnimalDiscovery>()
    private val python: PythonAnimalRecommendations = mockk<PythonAnimalRecommendations>()
    private val jdbc: NamedParameterJdbcOperations = mockk<NamedParameterJdbcOperations>()
    private val manager = LocalTransactions()
    private val reads = TransactionTemplate(manager)
    private val recommendations = JdbcAnimalRecommendations(animals, python, jdbc, reads)

    @Test
    fun `입양 완료된 원본과 낡은 후보가 있을 때__추천하면__원격 순서를 유지하고 현재 같은 종의 공고나 보호 중인 후보만 남긴다`() {
        // given
        every { animals.findById(any()) } returns source(Species.DOG, AnimalStatus.ADOPTED)
        every { python.recommend(any(), any()) } returns listOf(1L, 4L, 2L, 3L, 4L, 5L)
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Card>>()) } returns listOf(
            card(5, Species.DOG, AnimalStatus.NOTICE),
            card(4, Species.DOG, AnimalStatus.PROTECT),
            card(2, Species.DOG, AnimalStatus.RETURNED),
            card(3, Species.CAT, AnimalStatus.NOTICE),
        )

        // when
        val result = recommendations.recommend(1)

        // then
        val sql = slot<String>()
        val parameters = slot<MapSqlParameterSource>()
        assertThat(result.map { card -> card.id }).containsExactly(4L, 5L)
        verifySequence {
            animals.findById(1)
            python.recommend(1, "DOG")
            jdbc.query(capture(sql), capture(parameters), any<RowMapper<Card>>())
        }
        assertThat(sql.captured).contains("pawbridge_animal.animals", "JOIN pawbridge_animal.shelters", "a.id IN (:ids)")
            .doesNotContain("embedding", "vector", "ORDER BY")
        assertThat(parameters.captured.getValue("ids")).isEqualTo(listOf(4L, 2L, 3L, 5L))
    }

    @Test
    fun `삭제된 후보가 있을 때__현재 행을 다시 확인하면__원격 순서를 대체하지 않고 그 후보만 뺀다`() {
        // given
        every { animals.findById(any()) } returns source(Species.CAT, AnimalStatus.PROTECT)
        every { python.recommend(any(), any()) } returns listOf(8L, 9L)
        every {
            jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Card>>())
        } returns listOf(card(9, Species.CAT, AnimalStatus.NOTICE))

        // when
        val result = recommendations.recommend(1)

        // then
        assertThat(result.map { card -> card.id }).containsExactly(9L)
        verify(exactly = 1) { python.recommend(1, "CAT") }
        verify(exactly = 1) {
            jdbc.query(any<String>(), match<MapSqlParameterSource> { it.getValue("ids") == listOf(8L, 9L) }, any<RowMapper<Card>>())
        }
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    fun `원격 추천 ID가 잘못됐을 때__추천하면__후보를 조회하기 전에 이용 불가로 실패한다`(ids: List<Long?>?) {
        // given
        every { animals.findById(any()) } returns source(Species.DOG, AnimalStatus.PROTECT)
        every { python.recommend(any(), any()) } returns ids

        // when
        val thrown = assertThatThrownBy { recommendations.recommend(1) }

        // then
        thrown.isInstanceOf(RecommendationUnavailableException::class.java)
            .hasNoCause()
        verify(exactly = 1) { python.recommend(1, "DOG") }
        confirmVerified(jdbc)
    }

    @Test
    fun `원본이 없거나 지원하지 않는 종일 때__추천하면__파이썬 호출 전에 없음 또는 빈 결과를 그대로 낸다`() {
        // given
        every { animals.findById(1) } throws AnimalNotFoundException()
        every { animals.findById(2) } returns source(Species.ETC, AnimalStatus.PROTECT)

        // when
        val missing = assertThatThrownBy { recommendations.recommend(1) }
        val unsupported = recommendations.recommend(2)

        // then
        missing.isInstanceOf(AnimalNotFoundException::class.java)
        assertThat(unsupported).isEmpty()
        confirmVerified(python, jdbc)
    }

    @ParameterizedTest
    @MethodSource("emptyCandidates")
    fun `원격 ID가 비었거나 자기 자신뿐일 때__추천하면__후보 조회 없이 빈 결과를 낸다`(ids: List<Long?>?) {
        // given
        every { animals.findById(any()) } returns source(Species.CAT, AnimalStatus.PROTECT)
        every { python.recommend(any(), any()) } returns ids

        // when
        val result = recommendations.recommend(1)

        // then
        assertThat(result).isEmpty()
        verify(exactly = 1) { python.recommend(1, "CAT") }
        confirmVerified(jdbc)
    }

    @Test
    fun `파이썬 실패 메시지에 비밀이 들어 있을 때__추천하면__원인과 후보 조회 없이 이용 불가로 실패한다`() {
        // given
        every { animals.findById(any()) } returns source(Species.DOG, AnimalStatus.PROTECT)
        every { python.recommend(any(), any()) } throws IllegalStateException("synthetic-private-token/body")

        // when
        val thrown = assertThatThrownBy { recommendations.recommend(1) }

        // then
        thrown.isInstanceOf(RecommendationUnavailableException::class.java)
            .hasNoCause()
            .hasMessage("추천 자료를 준비 중입니다. 잠시 후 다시 시도해 주세요")
        verify(exactly = 1) { python.recommend(1, "DOG") }
        confirmVerified(jdbc)
    }

    @Test
    fun `원본 조회와 후보 조회가 별도 트랜잭션일 때__파이썬을 호출하면__데이터베이스 트랜잭션이 활성이 아니다`() {
        // given
        val remote = AtomicInteger()

        every { animals.findById(any()) } answers {
            reads.execute { status ->
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
                source(Species.DOG, AnimalStatus.PROTECT)
            }!!
        }
        every { python.recommend(any(), any()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            remote.incrementAndGet()
            listOf(4L)
        }
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Card>>()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            listOf(card(4, Species.DOG, AnimalStatus.PROTECT))
        }

        // when
        val result = recommendations.recommend(1)

        // then
        assertThat(result).hasSize(1)
        assertThat(remote.get()).isOne()
        assertThat(manager.begins).isEqualTo(2)
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        verify(exactly = 1) { animals.findById(1) }
        verify(exactly = 1) { python.recommend(1, "DOG") }
    }

    @Test
    fun `저장된 추천 필드가 있을 때__후보 행을 매핑하면__모든 필드와 원래의 나이 계산을 유지한다`() {
        // given
        val row = mockk<ResultSet>()
        val happened = LocalDate.of(2026, 9, 29)
        val ended = LocalDate.of(2026, 10, 9)
        val created = LocalDateTime.of(2026, 9, 29, 13, 4, 5, 123456000)

        every { animals.findById(any()) } returns source(Species.DOG, AnimalStatus.PROTECT)
        every { python.recommend(any(), any()) } returns listOf(4L)
        every { row.getObject("id", Long::class.javaObjectType) } returns 4L
        every { row.getString("apms_notice_no") } returns "공고-4"
        every { row.getString("species") } returns "DOG"
        every { row.getString("breed") } returns "믹스"
        every { row.getString("gender") } returns "FEMALE"
        every { row.getObject("birth_year", Int::class.javaObjectType) } returns LocalDate.now().year - 3
        every { row.getString("weight") } returns "0.69(Kg)"
        every { row.getString("color") } returns "레몬색&흰색"
        every { row.getObject("happen_date", LocalDate::class.java) } returns happened
        every { row.getString("special_mark") } returns "귀 반점"
        every { row.getString("status") } returns "PROTECT"
        every { row.getObject("notice_end_date", LocalDate::class.java) } returns ended
        every { row.getString("image_url") } returns "https://example.test/a.jpg"
        every { row.getObject("favorite_count", Int::class.javaObjectType) } returns 7
        every { row.getObject("shelter_id", Long::class.javaObjectType) } returns 8L
        every { row.getString("shelter_name") } returns "보호소"
        every { row.getObject("created_at", LocalDateTime::class.java) } returns created
        every { jdbc.query(any<String>(), any<MapSqlParameterSource>(), any<RowMapper<Card>>()) } answers {
            listOf(arg<RowMapper<Card>>(2).mapRow(row, 0)!!)
        }

        // when
        val mapped = recommendations.recommend(1)

        // then
        assertThat(mapped).containsExactly(
            Card(
                4L, "공고-4", Species.DOG, "믹스", Gender.FEMALE,
                LocalDate.now().year - 3, 3, "0.69(Kg)", "레몬색&흰색", happened, "귀 반점", AnimalStatus.PROTECT,
                ended, "https://example.test/a.jpg", 7, 8L, "보호소", created,
            ),
        )

        // given
        every { row.getObject("birth_year", Int::class.javaObjectType) } returns null

        // when
        val withoutBirthYear = recommendations.recommend(1)

        // then
        assertThat(withoutBirthYear.first().age).isNull()
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
