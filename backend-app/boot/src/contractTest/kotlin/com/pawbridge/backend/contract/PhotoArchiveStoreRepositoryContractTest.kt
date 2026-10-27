package com.pawbridge.backend.contract

import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.ArchivedPhoto
import com.pawbridge.backend.animal.internal.photoarchive.infrastructure.PhotoArchiveStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

/** The APMS photo archive queue on the real tables: discovery, lease claims with SKIP LOCKED, completion and retry. */
class PhotoArchiveStoreRepositoryContractTest : AnimalRepositoryContractBase() {
    private lateinit var store: PhotoArchiveStore

    @BeforeEach
    fun build() {
        store = PhotoArchiveStore(JdbcTemplate(writeSource), writeManager)
        seedShelter(1)
    }

    private fun apms(id: Long, first: String?, second: String? = null, source: String = "APMS_ANIMAL", desertion: String? = "D-$id") {
        seedAnimal(id, 1, apiSource = source, desertionNo = desertion)
        execute("UPDATE pawbridge_animal.animals SET image_url = ?, image_url2 = ? WHERE id = ?", first, second, id)
    }

    private fun archive(animal: Long, slot: Int): String? = scalar(
        "SELECT state || '|' || generation || '|' || attempts || '|' || coalesce(source_url, '-') FROM pawbridge_animal.apms_photo_archive WHERE animal_id = ? AND slot = ?",
        animal, slot,
    )

    @Test
    fun `APMS 동물과 다른 출처 동물이 있을 때__처음 탐색하면__APMS 사진 칸만 대기로 만들고 빈 칸과 다른 출처는 만들지 않는다`() {
        // given
        apms(10, " https://fixture.invalid/a.jpg ", "https://fixture.invalid/b.jpg")
        apms(11, "https://fixture.invalid/c.jpg", "  ")
        apms(12, "https://fixture.invalid/d.jpg", source = "MANUAL")
        apms(13, "https://fixture.invalid/e.jpg", desertion = null)

        // when
        val seen = store.discover(10)

        // then
        assertThat(seen).isGreaterThan(0)
        assertThat(archive(10, 1)).isEqualTo("PENDING|1|0|https://fixture.invalid/a.jpg")
        assertThat(archive(10, 2)).isEqualTo("PENDING|1|0|https://fixture.invalid/b.jpg")
        assertThat(archive(11, 1)).isEqualTo("PENDING|1|0|https://fixture.invalid/c.jpg")
        assertThat(archive(11, 2)).isNull()
        assertThat(count("SELECT count(*) FROM pawbridge_animal.apms_photo_archive WHERE animal_id IN (12, 13)")).isZero()
    }

    @Test
    fun `이미 보관 기록이 있는 사진 주소가 바뀌거나 지워졌을 때__다시 탐색하면__세대를 올리고 대기나 없음으로 되돌린다`() {
        // given
        apms(10, "https://fixture.invalid/a.jpg", "https://fixture.invalid/b.jpg")
        store.discover(10)
        store.discover(10)
        execute("UPDATE pawbridge_animal.apms_photo_archive SET state = 'READY', attempts = 2 WHERE animal_id = 10")
        execute("UPDATE pawbridge_animal.animals SET image_url = 'https://fixture.invalid/a2.jpg', image_url2 = NULL WHERE id = 10")

        // when
        var rounds = 0
        while (archive(10, 1)!!.startsWith("READY") && rounds++ < 5) {
            store.discover(10)
        }

        // then
        assertThat(archive(10, 1)).isEqualTo("PENDING|2|0|https://fixture.invalid/a2.jpg")
        assertThat(archive(10, 2)).isEqualTo("ABSENT|2|0|-")
    }

    @Test
    fun `기한이 된 사진과 안 된 사진과 만료된 임대가 있을 때__받아 가면__기한 순서로 하나만 주고 임대를 건다`() {
        // given
        apms(10, "https://fixture.invalid/a.jpg", "https://fixture.invalid/b.jpg")
        apms(11, "https://fixture.invalid/c.jpg")
        store.discover(10)
        execute("UPDATE pawbridge_animal.apms_photo_archive SET next_attempt_at = now() - interval '1 hour'")
        execute("UPDATE pawbridge_animal.apms_photo_archive SET next_attempt_at = now() + interval '1 hour' WHERE animal_id = 10 AND slot = 1")
        execute("UPDATE pawbridge_animal.apms_photo_archive SET next_attempt_at = now() - interval '3 hour' WHERE animal_id = 10 AND slot = 2")
        execute("UPDATE pawbridge_animal.apms_photo_archive SET next_attempt_at = now() - interval '2 hour' WHERE animal_id = 11")

        // when
        val first = store.claim(60).get()
        val second = store.claim(60).get()
        val none = store.claim(60)
        execute("UPDATE pawbridge_animal.apms_photo_archive SET lease_until = now() - interval '1 second' WHERE animal_id = 11")
        val reclaimed = store.claim(60).get()
        val reclaimedAgain = store.claim(60)

        // then
        assertThat(first.animalId to first.slot).`as`("The oldest due attempt first").isEqualTo(10L to 2)
        assertThat(second.animalId to second.slot).isEqualTo(11L to 1)
        assertThat(none).`as`("The remaining photo is not due, the others hold leases").isEmpty
        assertThat(reclaimed.animalId).isEqualTo(11)
        assertThat(reclaimedAgain).isEmpty
        assertThat(reclaimed.token).isNotEqualTo(first.token)
        assertThat(archive(11, 1)).startsWith("PROCESSING")
    }

    @Test
    fun `다른 세션이 가장 앞 행을 잠그고 있을 때__받아 가면__잠긴 행을 건너뛰어 다음 행을 받는다`() {
        // given
        apms(10, "https://fixture.invalid/a.jpg")
        apms(11, "https://fixture.invalid/b.jpg")
        store.discover(10)
        execute("UPDATE pawbridge_animal.apms_photo_archive SET next_attempt_at = now() - interval '1 hour'")

        // when
        val claimed = ContractPostgres.adminConnection { other ->
            other.autoCommit = false
            other.createStatement().use { it.execute("SELECT 1 FROM pawbridge_animal.apms_photo_archive WHERE animal_id = 10 AND slot = 1 FOR UPDATE") }
            val claim = store.claim(60).get()
            other.rollback()
            claim
        }

        // then
        assertThat(claimed.animalId).`as`("Animal 10 is locked by the other session").isEqualTo(11)
    }

    @Test
    fun `임대를 가진 작업이 끝났을 때__완료하면__보관 정보를 쓰고 대기하며 세대 토큰 만료 원본이 어긋난 작업은 거절한다`() {
        // given
        apms(10, "https://fixture.invalid/a.jpg")
        store.discover(10)
        execute("UPDATE pawbridge_animal.apms_photo_archive SET next_attempt_at = now() - interval '1 hour'")
        val claim = store.claim(60).get()
        val photo = ArchivedPhoto(ByteArray(4), "a".repeat(64), "b".repeat(64), "image/webp", 10, 20, "recipe-v1")

        // when
        val staleToken = store.complete(claim.copy(token = "other-token"), photo, 3600)
        val staleGeneration = store.complete(claim.copy(generation = claim.generation + 1), photo, 3600)
        execute("UPDATE pawbridge_animal.animals SET image_url = 'https://fixture.invalid/changed.jpg' WHERE id = 10")
        val sourceChanged = store.complete(claim, photo, 3600)
        execute("UPDATE pawbridge_animal.animals SET image_url = 'https://fixture.invalid/a.jpg' WHERE id = 10")
        execute("UPDATE pawbridge_animal.apms_photo_archive SET lease_until = now() - interval '1 second' WHERE animal_id = 10")
        val leaseExpired = store.complete(claim, photo, 3600)
        execute("UPDATE pawbridge_animal.apms_photo_archive SET lease_until = now() + interval '1 hour' WHERE animal_id = 10")
        val done = store.complete(claim, photo, 3600)

        // then
        assertThat(listOf(staleToken, staleGeneration, sourceChanged, leaseExpired)).containsOnly(false)
        assertThat(done).isTrue()
        assertThat(archive(10, 1)).startsWith("READY|1|0|")
        assertThat(scalar("SELECT object_key || '|' || content_type || '|' || stored_bytes || '|' || width || 'x' || height || '|' || (lease_token IS NULL) FROM pawbridge_animal.apms_photo_archive WHERE animal_id = 10"))
            .isEqualTo("apms/photos/${"b".repeat(64)}.webp|image/webp|4|10x20|true")
        assertThat(store.complete(claim, photo, 3600)).`as`("The lease is gone after completion").isFalse()
    }

    @Test
    fun `임대를 가진 작업이 실패했을 때__재시도로 기록하면__시도 수를 올리고 다음 시각을 미루며 임대가 없는 작업은 거절한다`() {
        // given
        apms(10, "https://fixture.invalid/a.jpg")
        store.discover(10)
        execute("UPDATE pawbridge_animal.apms_photo_archive SET next_attempt_at = now() - interval '1 hour'")
        val claim = store.claim(60).get()

        // when
        val wrongToken = store.retry(claim.copy(token = "other"), "TIMEOUT", 600)
        val retried = store.retry(claim, "TIMEOUT", 600)
        val again = store.retry(claim, "TIMEOUT", 600)

        // then
        assertThat(wrongToken).isFalse()
        assertThat(retried).isTrue()
        assertThat(again).isFalse()
        assertThat(archive(10, 1)).isEqualTo("RETRY|1|1|https://fixture.invalid/a.jpg")
        assertThat(scalar("SELECT error_code || '|' || (next_attempt_at > now()) FROM pawbridge_animal.apms_photo_archive WHERE animal_id = 10")).isEqualTo("TIMEOUT|true")
    }
}
