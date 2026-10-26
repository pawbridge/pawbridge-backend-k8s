package com.pawbridge.backend.animal.internal.gallery.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Function

class LostGallerySnapshotsTest {
    @TempDir
    lateinit var directory: Path

    private val mapper = ObjectMapper()

    private fun signer(): Function<String, String> {
        return Function { key ->
            "https://test.r2.cloudflarestorage.com/pawbridge-animal-originals/" + key + "?fake-signature=unit"
        }
    }

    private fun entry(id: Int, description: String): LostGallerySnapshots.Entry {
        val row = LinkedHashMap<String, Any?>()
        row["id"] = id.toLong()
        row["species"] = "DOG"
        row["source_sha256"] = SHA
        row["status"] = "PROTECT"
        row["description"] = description
        return LostGallerySnapshots.Entry(
            row,
            LostGalleryFeed.StoredPhoto(SHA, "apms/photos/" + SHA + ".jpg", 123, "image/jpeg"),
        )
    }

    private fun clockAt(now: AtomicReference<Instant>): Clock {
        return object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = now.get()
        }
    }

    @Test
    fun `원본 행 503개일 때__스냅샷을 만들고 원본을 바꾼 뒤 페이지를 읽으면__원래 메타데이터로 중복 없이 완전하게 돌려주고 보관 한도와 해제를 지킨다`() {
        // given
        val text = AtomicReference("원본")
        val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { consume ->
            for (id in 1..503) {
                consume.accept(entry(id, text.get()))
            }
        }
        LostGallerySnapshots(mapper, source, signer(), directory, Clock.systemUTC()).use { store ->
            // when
            val snapshot = store.create()
            text.set("변경됨")
            var cursor: String? = snapshot.cursor
            var count = 0
            do {
                val raw = store.page(snapshot.snapshotId, cursor, 100)

                // then
                assertTrue(raw.size <= LostGallerySnapshots.PAGE_BYTES)
                val page = mapper.readTree(raw)
                assertEquals(count, page.get("start").intValue())
                for (item in page.get("items")) {
                    count++
                    assertEquals(count, item.get("record").get("id").intValue())
                    assertEquals("원본", item.get("record").get("description").textValue())
                }
                cursor = if (page.get("nextCursor").isNull()) null else page.get("nextCursor").textValue()
                assertEquals(count == 503, page.get("complete").booleanValue())
            } while (cursor != null)
            assertEquals(503, count)
            assertNotEquals(snapshot.snapshotSha256, store.create().snapshotSha256)
            assertThrows(IllegalStateException::class.java) { store.create() }
            store.release(snapshot.snapshotId)
            assertThrows(LostGallerySnapshots.Gone::class.java) { store.describe(snapshot.snapshotId) }
        }
    }

    @Test
    fun `페이지 바이트 한도를 넘는 큰 행일 때__많은 항목을 요청하면__페이지 크기를 줄이고 위조되었거나 다른 스냅샷의 커서를 거부한다`() {
        // given
        val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { c ->
            for (i in 1..500) {
                c.accept(entry(i, "털".repeat(9000)))
            }
        }
        LostGallerySnapshots(mapper, source, signer(), directory, Clock.systemUTC()).use { store ->
            val first = store.create()
            val second = store.create()

            // when
            val raw = store.page(first.snapshotId, first.cursor, 500)

            // then
            val page = mapper.readTree(raw)
            assertTrue(raw.size <= LostGallerySnapshots.PAGE_BYTES)
            assertTrue(page.get("items").size() < 500)
            assertFalse(page.get("complete").booleanValue())
            assertThrows(LostGallerySnapshots.BadCursor::class.java) {
                store.page(first.snapshotId, first.cursor + "x", 100)
            }
            assertThrows(LostGallerySnapshots.BadCursor::class.java) {
                store.page(second.snapshotId, first.cursor, 100)
            }
            assertThrows(LostGallerySnapshots.BadCursor::class.java) {
                store.page(first.snapshotId, first.cursor, 501)
            }
        }
    }

    @Test
    fun `스냅샷이 만료되고 프로세스가 재시작됐을 때__다시 읽으면__오래된 파일에서 재개하지 않는다`() {
        // given
        val now = AtomicReference(Instant.parse("2026-09-15T00:00:00Z"))
        val clock = clockAt(now)
        val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { c -> c.accept(entry(1, "a")) }
        val id: String
        LostGallerySnapshots(mapper, source, signer(), directory, clock).use { store ->
            val snapshot = store.create()
            id = snapshot.snapshotId
            now.set(now.get().plus(Duration.ofHours(7)))

            // when
            assertThrows(LostGallerySnapshots.Gone::class.java) { store.page(id, snapshot.cursor, 100) }

            // then
        }
        Files.writeString(directory.resolve("b".repeat(32) + ".partial"), "incomplete")
        LostGallerySnapshots(mapper, source, signer(), directory, clock).use { store ->
            // when
            assertThrows(LostGallerySnapshots.Gone::class.java) { store.describe(id) }

            // then
            assertFalse(Files.exists(directory.resolve("b".repeat(32) + ".partial")))
        }
    }

    @Test
    fun `활성 페이지를 읽은 뒤일 때__유휴 만료 전에 다시 조회하면__스냅샷 식별을 바꾸지 않고 만료를 연장한다`() {
        // given
        val now = AtomicReference(Instant.parse("2026-09-15T00:00:00Z"))
        val clock = clockAt(now)
        val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { c -> c.accept(entry(1, "a")) }
        LostGallerySnapshots(mapper, source, signer(), directory, clock).use { store ->
            val snapshot = store.create()
            now.set(now.get().plus(Duration.ofHours(5)))
            store.page(snapshot.snapshotId, snapshot.cursor, 100)
            now.set(now.get().plus(Duration.ofHours(5)))

            // when
            val resumed = store.describe(snapshot.snapshotId)

            // then
            assertEquals(snapshot.snapshotSha256, resumed.snapshotSha256)
            assertEquals(snapshot.cursor, resumed.cursor)
            assertTrue(resumed.expiresAt > snapshot.expiresAt)
        }
    }

    @Test
    fun `유니코드와 널이 섞인 항목일 때__체인을 진행하면__파이썬 프로토콜 고정값과 같은 지문이 나온다`() {
        // given
        val row = LinkedHashMap<String, Any?>()
        row["id"] = 7L
        row["species"] = "DOG"
        row["source_sha256"] = SHA
        row["status"] = "PROTECT"
        row["happen_date"] = "2026-09-15"
        row["happen_place"] = "서울"
        row["color"] = "갈색"
        row["special_mark"] = null
        row["description"] = ""
        val entry = LostGallerySnapshots.Entry(
            row,
            LostGalleryFeed.StoredPhoto(SHA, "apms/photos/" + SHA + ".jpg", 123, "image/jpeg"),
        )

        // when
        val fingerprint = HexFormat.of().formatHex(LostGallerySnapshots.advance(LostGallerySnapshots.initialChain(), entry))

        // then
        assertEquals("25f0beaba9ab422cfe17d3be38b2555abd41182ce0885c67debd4e71d43d5dc3", fingerprint)
    }

    @Test
    fun `원본에 중복 행이 있어 실패할 때__스냅샷을 만들면__발행하지 않고 부분 스냅샷도 남기지 않는다`() {
        // given
        val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { c ->
            c.accept(entry(1, "a"))
            c.accept(entry(1, "duplicate"))
        }
        LostGallerySnapshots(mapper, source, signer(), directory, Clock.systemUTC()).use { store ->
            // when
            assertThrows(IllegalStateException::class.java) { store.create() }

            // then
            Files.list(directory).use { files ->
                assertEquals(1L, files.count())
            }
        }
    }

    @Test
    fun `같은 요청 ID로 재시도할 때__스냅샷을 만들면__보관 한도를 소진하지 않고 같은 스냅샷을 재사용한다`() {
        // given
        val calls = AtomicInteger()
        val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { c ->
            calls.incrementAndGet()
            c.accept(entry(1, "a"))
        }
        LostGallerySnapshots(mapper, source, signer(), directory, Clock.systemUTC()).use { store ->
            // when
            val first = store.create("b".repeat(32))

            // then
            for (i in 0 until 4) {
                assertEquals(first.snapshotId, store.create("b".repeat(32)).snapshotId)
            }
            assertEquals(1, calls.get())
            assertNotEquals(first.snapshotId, store.create("c".repeat(32)).snapshotId)
            assertThrows(LostGallerySnapshots.BadCursor::class.java) { store.create("invalid") }
        }
    }

    @Test
    fun `인증이 없거나 스냅샷이 만료되거나 내부 오류일 때__스냅샷 API를 호출하면__작업 전에 401이고 만료는 410이며 내부 오류는 빈 본문의 503이다`() {
        // given
        val store = mockk<LostGallerySnapshots>()
        val mvc = MockMvcBuilders.standaloneSetup(LostGalleryPagesController(store, "key")).build()

        every { store.describe(any()) } throws LostGallerySnapshots.Gone()
        every { store.create(any()) } throws IllegalStateException("private detail")

        // when
        val unauthenticated = mvc.perform(post("/internal/animals/lost-gallery/snapshots")).andReturn().response
        val expired = mvc.perform(
            get("/internal/animals/lost-gallery/snapshots/" + "a".repeat(32)).header("X-Internal-Api-Key", "key"),
        ).andReturn().response
        val failed = mvc.perform(
            post("/internal/animals/lost-gallery/snapshots").header("X-Internal-Api-Key", "key"),
        ).andReturn().response

        // then
        assertEquals(401, unauthenticated.status)
        assertEquals(410, expired.status)
        assertEquals(503, failed.status)
        assertEquals("", failed.contentAsString)
        verify(exactly = 1) { store.describe("a".repeat(32)) }
        verify(exactly = 1) { store.create(any()) }
        confirmVerified(store)
    }

    @Test
    fun `원본 행 5천·37605·10만 개일 때__스냅샷을 만들면__행을 모으지 않고 개수가 맞으며 파일 크기가 한도 이하이다`() {
        for (count in intArrayOf(5000, 37605, 100000)) {
            // given
            val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { c ->
                for (i in 1..count) {
                    c.accept(entry(i, "갈색 강아지 보호소 기록"))
                }
            }
            LostGallerySnapshots(mapper, source, signer(), directory, Clock.systemUTC()).use { store ->
                // when
                val descriptor = store.create()

                // then
                assertEquals(count, descriptor.count)
                assertTrue(
                    Files.size(directory.resolve(descriptor.snapshotId + ".snapshot")) <= LostGallerySnapshots.FILE_BYTES,
                )
                println(
                    "GALLERY_MEMORY count=" + count + " maxHeap=" + Runtime.getRuntime().maxMemory() +
                        " usedHeap=" + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()),
                )
                store.release(descriptor.snapshotId)
            }
        }
    }

    companion object {
        private val SHA: String = "a".repeat(64)
    }
}
