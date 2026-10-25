package com.pawbridge.backend.animal.internal.gallery.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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
    fun frozen_snapshot_keeps_original_metadata_and_pages_are_complete_without_duplicates() {
        val text = AtomicReference("원본")
        val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { consume ->
            for (id in 1..503) {
                consume.accept(entry(id, text.get()))
            }
        }
        LostGallerySnapshots(mapper, source, signer(), directory, Clock.systemUTC()).use { store ->
            val snapshot = store.create()
            text.set("변경됨")
            var cursor: String? = snapshot.cursor
            var count = 0
            do {
                val raw = store.page(snapshot.snapshotId, cursor, 100)
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
    fun byte_limit_reduces_page_size_and_forged_or_foreign_cursors_are_rejected() {
        val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { c ->
            for (i in 1..500) {
                c.accept(entry(i, "털".repeat(9000)))
            }
        }
        LostGallerySnapshots(mapper, source, signer(), directory, Clock.systemUTC()).use { store ->
            val first = store.create()
            val second = store.create()
            val raw = store.page(first.snapshotId, first.cursor, 500)
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
    fun expired_snapshot_and_restarted_process_never_resume_from_stale_files() {
        val now = AtomicReference(Instant.parse("2026-09-15T00:00:00Z"))
        val clock = clockAt(now)
        val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { c -> c.accept(entry(1, "a")) }
        val id: String
        LostGallerySnapshots(mapper, source, signer(), directory, clock).use { store ->
            val snapshot = store.create()
            id = snapshot.snapshotId
            now.set(now.get().plus(Duration.ofHours(7)))
            assertThrows(LostGallerySnapshots.Gone::class.java) { store.page(id, snapshot.cursor, 100) }
        }
        Files.writeString(directory.resolve("b".repeat(32) + ".partial"), "incomplete")
        LostGallerySnapshots(mapper, source, signer(), directory, clock).use { store ->
            assertThrows(LostGallerySnapshots.Gone::class.java) { store.describe(id) }
            assertFalse(Files.exists(directory.resolve("b".repeat(32) + ".partial")))
        }
    }

    @Test
    fun active_pages_renew_idle_expiration_without_changing_snapshot_identity() {
        val now = AtomicReference(Instant.parse("2026-09-15T00:00:00Z"))
        val clock = clockAt(now)
        val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { c -> c.accept(entry(1, "a")) }
        LostGallerySnapshots(mapper, source, signer(), directory, clock).use { store ->
            val snapshot = store.create()
            now.set(now.get().plus(Duration.ofHours(5)))
            store.page(snapshot.snapshotId, snapshot.cursor, 100)
            now.set(now.get().plus(Duration.ofHours(5)))
            val resumed = store.describe(snapshot.snapshotId)
            assertEquals(snapshot.snapshotSha256, resumed.snapshotSha256)
            assertEquals(snapshot.cursor, resumed.cursor)
            assertTrue(resumed.expiresAt > snapshot.expiresAt)
        }
    }

    @Test
    fun fingerprint_matches_python_protocol_fixture_including_unicode_and_nulls() {
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

        assertEquals(
            "25f0beaba9ab422cfe17d3be38b2555abd41182ce0885c67debd4e71d43d5dc3",
            HexFormat.of().formatHex(LostGallerySnapshots.advance(LostGallerySnapshots.initialChain(), entry)),
        )
    }

    @Test
    fun failed_source_does_not_publish_or_leave_partial_snapshot() {
        val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { c ->
            c.accept(entry(1, "a"))
            c.accept(entry(1, "duplicate"))
        }
        LostGallerySnapshots(mapper, source, signer(), directory, Clock.systemUTC()).use { store ->
            assertThrows(IllegalStateException::class.java) { store.create() }
            Files.list(directory).use { files ->
                assertEquals(1L, files.count())
            }
        }
    }

    @Test
    fun creation_retry_reuses_the_same_snapshot_instead_of_exhausting_retention() {
        val calls = AtomicInteger()
        val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { c ->
            calls.incrementAndGet()
            c.accept(entry(1, "a"))
        }
        LostGallerySnapshots(mapper, source, signer(), directory, Clock.systemUTC()).use { store ->
            val first = store.create("b".repeat(32))
            for (i in 0 until 4) {
                assertEquals(first.snapshotId, store.create("b".repeat(32)).snapshotId)
            }
            assertEquals(1, calls.get())
            assertNotEquals(first.snapshotId, store.create("c".repeat(32)).snapshotId)
            assertThrows(LostGallerySnapshots.BadCursor::class.java) { store.create("invalid") }
        }
    }

    @Test
    fun authentication_precedes_snapshot_work_and_expiry_has_explicit_response() {
        val store = mock(LostGallerySnapshots::class.java)
        val mvc = MockMvcBuilders.standaloneSetup(LostGalleryPagesController(store, "key")).build()

        mvc.perform(post("/internal/animals/lost-gallery/snapshots")).andExpect(status().isUnauthorized())
        verifyNoInteractions(store)
        `when`(store.describe("a".repeat(32))).thenThrow(LostGallerySnapshots.Gone())
        mvc.perform(get("/internal/animals/lost-gallery/snapshots/" + "a".repeat(32)).header("X-Internal-Api-Key", "key"))
            .andExpect(status().isGone())
        `when`(store.create(org.mockito.ArgumentMatchers.any<String>())).thenThrow(IllegalStateException("private detail"))
        mvc.perform(post("/internal/animals/lost-gallery/snapshots").header("X-Internal-Api-Key", "key"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(""))
    }

    @Test
    fun generated_snapshots_fit_the_192_mib_heap_without_collecting_source_rows() {
        for (count in intArrayOf(5000, 37605, 100000)) {
            val source = Consumer<Consumer<LostGallerySnapshots.Entry>> { c ->
                for (i in 1..count) {
                    c.accept(entry(i, "갈색 강아지 보호소 기록"))
                }
            }
            LostGallerySnapshots(mapper, source, signer(), directory, Clock.systemUTC()).use { store ->
                val descriptor = store.create()
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
