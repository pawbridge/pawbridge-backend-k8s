package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** The lost-pet gallery read: which archived photos are published, in which order, and that reading writes nothing. */
class LostGalleryFeedRepositoryContractTest : JpaContractBase() {
    @Autowired
    private lateinit var mapper: ObjectMapper

    private lateinit var feed: LostGalleryFeed

    @BeforeEach
    fun build() {
        val reads = TransactionTemplate(manager).apply { isReadOnly = true }
        feed = LostGalleryFeed(JdbcTemplate(source), mapper, { key -> "https://signed.invalid/$key" }, reads, reads)
        seedShelter(SHELTER)
    }

    private fun sha(character: Char) = character.toString().repeat(64)

    private fun archive(
        animal: Long,
        character: Char,
        state: String = "READY",
        slot: Int = 1,
        archivedUrl: String? = "https://source.invalid/$animal.jpg",
        bytes: Int = 1_000,
        mime: String = "image/jpeg",
        key: String = "apms/photos/${sha(character)}.jpg",
    ) {
        execute(
            "INSERT INTO pawbridge_animal.apms_photo_archive(animal_id, slot, desertion_no, source_url, state, archived_source_url, " +
                "stored_sha256, object_key, content_type, stored_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            animal, slot, "D-$animal", "https://source.invalid/$animal.jpg", state, archivedUrl, sha(character), key, mime, bytes,
        )
    }

    private fun animalWithPhoto(id: Long, species: String = "DOG", imageUrl: String? = "https://source.invalid/$id.jpg") {
        seedAnimal(id, SHELTER, species = species, imageUrl = imageUrl)
    }

    private fun ids(result: LostGalleryFeed.Result): List<Int> =
        mapper.readTree(result.body).get("records").map { it.get("id").asInt() }

    @Test
    fun `게시 조건이 다른 사진들이 있을 때__스냅샷을 읽으면__조건에 맞는 개와 고양이 사진만 동물 번호 순으로 서명해 준다`() {
        // given
        animalWithPhoto(3, "CAT"); archive(3, 'c')
        animalWithPhoto(1); archive(1, 'a')
        animalWithPhoto(2, imageUrl = "  https://source.invalid/2.jpg  "); archive(2, 'b')
        animalWithPhoto(4); archive(4, 'd', state = "PENDING")
        animalWithPhoto(5); archive(5, 'e', slot = 2)
        animalWithPhoto(6); archive(6, 'f', archivedUrl = "https://source.invalid/other.jpg")
        animalWithPhoto(7, "ETC"); archive(7, '1')
        animalWithPhoto(8, imageUrl = "https://source.invalid/changed.jpg"); archive(8, '2')

        // when
        val result = assertNoSideEffects { feed.snapshot(null) }

        // then
        assertThat(ids(result)).isEqualTo(listOf(1, 2, 3))
        val body = mapper.readTree(result.body)
        assertThat(body.get("count").asInt()).isEqualTo(3)
        assertThat(body.get("photos").map { it.get("url").asText() })
            .isEqualTo(listOf("a", "b", "c").map { "https://signed.invalid/apms/photos/${sha(it[0])}.jpg" })
        assertThat(body.get("records").first().get("species").asText()).isEqualTo("DOG")
    }

    @Test
    fun `같은 내용을 다시 읽을 때__이전 태그를 주면__본문 없이 같은 태그를 주고 내용이 바뀌면 태그도 바뀐다`() {
        // given
        animalWithPhoto(1); archive(1, 'a')
        val first = feed.snapshot(null)

        // when
        val unchanged = feed.snapshot(first.etag)
        execute("UPDATE pawbridge_animal.animals SET description = '바뀐 설명' WHERE id = 1")
        val changed = feed.snapshot(first.etag)

        // then
        assertThat(unchanged.etag).isEqualTo(first.etag)
        assertThat(unchanged.body).isNull()
        assertThat(changed.etag).isNotEqualTo(first.etag)
        assertThat(changed.body).isNotNull()
    }

    @Test
    fun `게시할 사진이 없거나 사진 정보가 어긋날 때__읽으면__실패한다`() {
        // given
        assertThatThrownBy { feed.snapshot(null) }.isInstanceOf(IllegalStateException::class.java)
        animalWithPhoto(1); archive(1, 'a')
        animalWithPhoto(2); archive(2, 'a', bytes = 2_000)

        // when
        // then
        assertThatThrownBy { feed.snapshot(null) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("Conflicting")
        assertThatThrownBy { feed.streamEntries { } }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("Conflicting")
        execute("DELETE FROM pawbridge_animal.apms_photo_archive WHERE animal_id = 2")
        execute("UPDATE pawbridge_animal.apms_photo_archive SET content_type = 'image/gif' WHERE animal_id = 1")
        assertThatThrownBy { feed.snapshot(null) }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("Invalid archived photo")
    }

    @Test
    fun `사진이 있을 때__항목을 흘려 읽으면__스냅샷과 같은 동물 순서로 사진 정보와 함께 준다`() {
        // given
        animalWithPhoto(2); archive(2, 'b')
        animalWithPhoto(1); archive(1, 'a')
        val entries = ArrayList<LostGallerySnapshots.Entry>()

        // when
        feed.streamEntries { entries.add(it) }

        // then
        assertThat(entries.map { it.record["id"] }).isEqualTo(listOf(1L, 2L))
        assertThat(entries.map { it.photo.sha256 }).isEqualTo(listOf(sha('a'), sha('b')))
    }

    private companion object {
        const val SHELTER = 8_400_001L
    }
}
