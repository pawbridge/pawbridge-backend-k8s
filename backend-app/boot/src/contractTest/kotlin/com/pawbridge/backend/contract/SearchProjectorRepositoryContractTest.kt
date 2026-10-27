package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** The animal and shelter search projection repair: dirty rows, audit of stale documents, lock skipping and the guards. */
class SearchProjectorRepositoryContractTest : AnimalRepositoryContractBase() {
    private val analyzer = KoreanSearchAnalyzer()
    private lateinit var projector: PostgresqlSearchProjector

    @BeforeEach
    fun build() {
        projector = PostgresqlSearchProjector(JdbcTemplate(writeSource), TransactionTemplate(writeManager), analyzer)
        seedShelter(1, name = "행복 보호소")
        seedShelter(2, name = "바다 보호소")
        seedAnimal(10, 1, breed = "푸들")
        seedAnimal(11, 1, breed = "시츄")
        seedAnimal(12, 2, breed = "말티즈")
    }

    @AfterEach
    fun close() {
        analyzer.close()
    }

    private fun dirtyAnimals() = count("SELECT count(*) FROM pawbridge_animal.animals WHERE search_dirty")

    private fun animalDocuments() = count("SELECT count(*) FROM pawbridge_animal.animal_search_documents")

    @Test
    fun `새로 들어온 더티 동물과 보호소일 때__복구하면__문서를 쓰고 더티 표시를 끄며 다시 하면 할 일이 없다`() {
        // given
        // when
        val animals = projector.refreshDirtyAnimals(10)
        val shelters = projector.refreshDirtyShelters(10)

        // then
        assertThat(animals).isEqualTo(3)
        assertThat(shelters).isEqualTo(2)
        assertThat(dirtyAnimals()).isZero()
        assertThat(animalDocuments()).isEqualTo(3)
        assertThat(count("SELECT count(*) FROM pawbridge_animal.shelter_search_documents")).isEqualTo(2)
        assertThat(projector.refreshDirtyAnimals(10)).isZero()
    }

    @Test
    fun `내용이 바뀐 동물일 때__수정하면__트리거가 판본을 올리고 더티로 되돌리며 복구가 새 판본의 문서를 쓴다`() {
        // given
        projector.refreshDirtyAnimals(10)
        val before = count("SELECT search_revision FROM pawbridge_animal.animals WHERE id = 10")

        // when
        execute("UPDATE pawbridge_animal.animals SET breed = '골든 리트리버' WHERE id = 10")
        execute("UPDATE pawbridge_animal.animals SET favorite_count = 5 WHERE id = 11")
        val dirtyAfterEdit = dirtyAnimals()
        projector.refreshDirtyAnimals(10)

        // then
        assertThat(before).isEqualTo(1)
        assertThat(dirtyAfterEdit).`as`("Only a search-relevant column marks the row").isOne()
        assertThat(count("SELECT search_revision FROM pawbridge_animal.animals WHERE id = 10")).isEqualTo(2)
        assertThat(count("SELECT source_revision FROM pawbridge_animal.animal_search_documents WHERE animal_id = 10")).isEqualTo(2)
        assertThat(count("SELECT search_revision FROM pawbridge_animal.animals WHERE id = 11")).isEqualTo(1)
    }

    @Test
    fun `문서 판본이나 분석기 판본이 어긋나거나 문서가 없을 때__감사 복구를 한 행씩 하면__모두 복구하고 정상인 행은 그대로 둔다`() {
        // given
        projector.refreshAnimals(10)
        projector.refreshShelters(10)
        execute("DELETE FROM pawbridge_animal.animal_search_documents WHERE animal_id = 10")
        execute("UPDATE pawbridge_animal.animal_search_documents SET source_revision = 0 WHERE animal_id = 11")
        execute("UPDATE pawbridge_animal.animal_search_documents SET analyzer_version = 'old' WHERE animal_id = 12")
        execute("UPDATE pawbridge_animal.shelter_search_documents SET analyzer_version = 'old' WHERE shelter_id = 2")
        val untouched = scalar("SELECT to_jsonb(d)::text FROM pawbridge_animal.shelter_search_documents d WHERE shelter_id = 1")

        // when
        val animalPasses = generateSequence { projector.refreshAnimals(1) }.takeWhile { it > 0 }.take(10).sum()
        val shelterPasses = generateSequence { projector.refreshShelters(1) }.takeWhile { it > 0 }.take(10).sum()

        // then
        assertThat(animalPasses).isEqualTo(3)
        assertThat(shelterPasses).isEqualTo(1)
        assertThat(animalDocuments()).isEqualTo(3)
        assertThat(count("SELECT count(*) FROM pawbridge_animal.animal_search_documents d JOIN pawbridge_animal.animals a ON a.id = d.animal_id WHERE d.source_revision = a.search_revision")).isEqualTo(3)
        assertThat(scalar("SELECT to_jsonb(d)::text FROM pawbridge_animal.shelter_search_documents d WHERE shelter_id = 1")).isEqualTo(untouched)
    }

    @Test
    fun `다른 세션이 더티 원본을 잠그고 있을 때__복구하면__잠긴 행을 건너뛰어 0을 돌려주고 풀린 뒤에 복구한다`() {
        // given
        execute("UPDATE pawbridge_animal.animals SET search_dirty = false WHERE id <> 10")

        // when
        val whileLocked = ContractPostgres.adminConnection { other ->
            other.autoCommit = false
            other.createStatement().use { it.execute("SELECT 1 FROM pawbridge_animal.animals WHERE id = 10 FOR UPDATE") }
            val skipped = projector.refreshDirtyAnimals(10)
            other.rollback()
            skipped
        }
        val afterRelease = projector.refreshDirtyAnimals(10)

        // then
        assertThat(whileLocked).isZero()
        assertThat(afterRelease).isOne()
    }

    @Test
    fun `바깥 트랜잭션 안이거나 쪽 크기가 범위 밖일 때__복구하면__질의 전에 거부하고 아무것도 바꾸지 않는다`() {
        // given
        val outer = TransactionTemplate(writeManager)

        // when
        // then
        assertNoSideEffects {
            assertThatThrownBy { outer.execute { projector.refreshDirtyAnimals(10) } }.isInstanceOf(IllegalStateException::class.java)
            assertThatThrownBy { projector.refreshAnimals(0) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { projector.refreshShelters(101) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
