package com.pawbridge.backend.animal.internal.gallery.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.net.URI
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.Function
import javax.sql.DataSource

class LostGalleryFeedTest {
    private val mapper = ObjectMapper()
    private val sha = "a".repeat(64)

    /** Same feed as the production class; only exposes the protected reader the Java test called from its package. */
    private class ReadableFeed(
        jdbc: JdbcTemplate?,
        mapper: ObjectMapper,
        signer: Function<String, String>?,
        reads: TransactionOperations,
        streamReads: TransactionOperations,
    ) : LostGalleryFeed(jdbc, mapper, signer, reads, streamReads) {
        fun content(): Content {
            return readContent()
        }
    }

    private fun signer(): Function<String, String> {
        return Function { key ->
            "https://test.r2.cloudflarestorage.com/pawbridge-animal-originals/" + key + "?fake-signature=unit"
        }
    }

    @Test
    fun `비공개 객체 키가 있는 콘텐츠일 때__스냅샷을 만들면__키를 서명기에 넘겨 URL로 바꾸고 같은 내용이면 본문 없이 같은 ETag를 돌려준다`() {
        // given
        val readActive = AtomicBoolean()
        val signedKeys = ArrayList<String>()
        val signer = Function<String, String> { key ->
            assertFalse(readActive.get(), "v1 signing must happen after its database read transaction")
            signedKeys.add(key)
            signer().apply(key)
        }
        val feed = object : LostGalleryFeed(
            null,
            mapper,
            signer,
            transaction(readActive),
            TransactionOperations.withoutTransaction(),
        ) {
            override fun readContent(): Content {
                assertTrue(readActive.get(), "v1 content must execute inside its explicit reader transaction")
                return Content(
                    listOf(
                        mapOf(
                            "id" to 1,
                            "species" to "DOG",
                            "source_sha256" to sha,
                            "status" to "PROTECT",
                            "happen_place" to "상주시",
                        ),
                    ),
                    listOf(StoredPhoto(sha, "apms/photos/" + sha + ".jpg", 123, "image/jpeg")),
                )
            }
        }

        // when
        val first = feed.snapshot(null)
        val unchanged = feed.snapshot(first.etag)

        // then
        val json = mapper.readTree(first.body)
        assertTrue(json.get("complete").asBoolean())
        assertEquals(1, json.get("count").asInt())
        assertEquals("PROTECT", json.get("records").get(0).get("status").asText())
        assertEquals("상주시", json.get("records").get(0).get("happen_place").asText())
        val url = URI.create(json.get("photos").get(0).get("url").asText())
        assertEquals("test.r2.cloudflarestorage.com", url.host)
        assertEquals("/pawbridge-animal-originals/apms/photos/" + sha + ".jpg", url.path)
        assertEquals("fake-signature=unit", url.query)
        assertEquals(listOf("apms/photos/" + sha + ".jpg"), signedKeys)
        assertFalse(String(first.body!!, StandardCharsets.UTF_8).contains("test-secret"))
        assertEquals(first.etag, unchanged.etag)
        assertNull(unchanged.body)
        assertEquals(1, signedKeys.size, "unchanged content must not be signed again")
    }

    @Test
    fun `API 키가 없거나 틀리고 피드가 실패할 때__피드를 요청하면__데이터 접근 전에 401이고 실패해도 부분 내용을 노출하지 않는다`() {
        // given
        val feed = mockk<LostGalleryFeed>()
        val mvc = MockMvcBuilders.standaloneSetup(LostGalleryFeedController(feed, "test-key")).build()

        every { feed.snapshot(any()) } throws IllegalStateException("sensitive upstream details")

        // when
        val missingKey = mvc.perform(get("/internal/animals/lost-gallery")).andReturn().response
        val wrongKey = mvc.perform(get("/internal/animals/lost-gallery").header("X-Internal-Api-Key", "wrong"))
            .andReturn().response

        // then
        assertEquals(401, missingKey.status)
        assertEquals(401, wrongKey.status)
        confirmVerified(feed)

        // when
        val failed = mvc.perform(get("/internal/animals/lost-gallery").header("X-Internal-Api-Key", "test-key"))
            .andReturn().response

        // then
        assertEquals(503, failed.status)
        assertEquals("", failed.contentAsString)
        verify(exactly = 1) { feed.snapshot(null) }
    }

    @Test
    fun `이미 받은 ETag와 같을 때__피드를 요청하면__304와 같은 ETag를 돌려주고 서명 URL을 캐시하지 않는다`() {
        // given
        val feed = mockk<LostGalleryFeed>()
        val etag = "\"" + sha + "\""
        val mvc = MockMvcBuilders.standaloneSetup(LostGalleryFeedController(feed, "test-key")).build()

        every { feed.snapshot(any()) } returns LostGalleryFeed.Result(etag, null)

        // when
        val response = mvc.perform(
            get("/internal/animals/lost-gallery").header("X-Internal-Api-Key", "test-key")
                .header("If-None-Match", etag),
        ).andReturn().response

        // then
        assertEquals(304, response.status)
        assertEquals(etag, response.getHeader("ETag"))
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("", response.contentAsString)
        verify(exactly = 1) { feed.snapshot(etag) }
    }

    @Test
    fun `신뢰할 수 없는 객체 경로나 빠진 무결성 메타데이터일 때__사진을 검증하면__서명 전에 거부한다`() {
        // given
        val photos = listOf(
            LostGalleryFeed.StoredPhoto(sha, "other/private.jpg", 1, "image/jpeg"),
            LostGalleryFeed.StoredPhoto(sha, "apms/photos/" + sha + ".jpg", 0, "image/jpeg"),
            LostGalleryFeed.StoredPhoto("bad", "apms/photos/" + sha + ".jpg", 1, "image/jpeg"),
        )

        for (photo in photos) {
            // when & then
            assertThrows(IllegalStateException::class.java) { LostGalleryFeed.validatePhoto(photo) }
        }
    }

    @Test
    fun `원본이 501행을 돌려주고 스트림 소비자가 실패할 때__레거시 피드와 스트림을 읽으면__500행 한도에서 멈추고 두 경우 모두 JDBC 커서를 닫는다`() {
        // given
        val source = mockk<DataSource>()
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()
        val result = mockk<ResultSet>()
        val conflicts = mockk<PreparedStatement>()
        val conflictResult = mockk<ResultSet>()
        val row = AtomicInteger()
        val strings = mapOf(
            "stored_sha256" to sha,
            "object_key" to "apms/photos/" + sha + ".jpg",
            "species" to "DOG",
            "status" to "PROTECT",
            "content_type" to "image/jpeg",
        )

        every { source.connection } returns connection
        justRun { connection.close() }
        every { connection.prepareStatement(LostGalleryFeed.SQL) } returns statement
        every {
            connection.prepareStatement(match<String> { sql -> sql.startsWith("SELECT stored_sha256") })
        } returns conflicts
        every { conflicts.executeQuery() } returns conflictResult
        justRun { conflicts.queryTimeout = any() }
        justRun { conflicts.close() }
        every { conflictResult.next() } returns false
        justRun { conflictResult.close() }
        every { statement.executeQuery() } returns result
        justRun { statement.queryTimeout = any() }
        justRun { statement.fetchSize = any() }
        justRun { statement.close() }
        every { result.next() } answers { row.incrementAndGet() <= 501 }
        every { result.getLong("id") } answers { row.get().toLong() }
        every { result.getLong("stored_bytes") } returns 123L
        every { result.getString(any<String>()) } answers { strings[firstArg()] }
        justRun { result.close() }

        val streamActive = AtomicBoolean()
        val feed = ReadableFeed(
            JdbcTemplate(source),
            mapper,
            null,
            TransactionOperations.withoutTransaction(),
            transaction(streamActive),
        )

        // when
        val legacyFailure = assertThrows(IllegalStateException::class.java) { feed.content() }

        // then
        assertNotNull(legacyFailure)
        assertEquals(501, row.get())
        verify(exactly = 1) { result.close() }
        clearMocks(result, statement, answers = false)
        row.set(0)

        // when
        val streamFailure = assertThrows(IllegalStateException::class.java) {
            feed.streamEntries(
                Consumer { entry ->
                    assertTrue(streamActive.get(), "source rows must be consumed inside the explicit stream transaction")
                    assertEquals(1L, entry.record["id"])
                    assertEquals("PROTECT", entry.record["status"])
                    throw IllegalStateException("consumer storage full")
                },
            )
        }

        // then
        assertNotNull(streamFailure)
        assertEquals(1, row.get())
        verify(exactly = 1) { statement.fetchSize = 200 }
        verify(exactly = 0) { connection.metaData }
        assertFalse(streamActive.get(), "failed source must exit its stream transaction")
        verify(exactly = 1) { result.close() }
        verify(exactly = 1) { statement.close() }
    }

    @Test
    fun `객체 키가 다른 다이제스트를 가리킬 때__두 피드를 읽으면__서명이나 발행 전에 거부하고 커서를 닫는다`() {
        // given
        val source = mockk<DataSource>()
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()
        val rows = mockk<ResultSet>()
        val conflict = mockk<PreparedStatement>()
        val conflictRows = mockk<ResultSet>()
        val strings = mapOf(
            "content_type" to "image/jpeg",
            "stored_sha256" to sha,
            "object_key" to "apms/photos/" + "b".repeat(64) + ".jpg",
            "species" to "DOG",
        )
        val signed = AtomicInteger()
        val published = AtomicInteger()

        every { source.connection } returns connection
        justRun { connection.close() }
        every { connection.prepareStatement(LostGalleryFeed.SQL) } returns statement
        every {
            connection.prepareStatement(match<String> { sql -> sql.startsWith("SELECT stored_sha256") })
        } returns conflict
        every { conflict.executeQuery() } returns conflictRows
        justRun { conflict.queryTimeout = any() }
        justRun { conflict.close() }
        every { conflictRows.next() } returns false
        justRun { conflictRows.close() }
        every { statement.executeQuery() } returns rows
        justRun { statement.queryTimeout = any() }
        justRun { statement.fetchSize = any() }
        justRun { statement.close() }
        // One row is always available: both reads must already fail on that first row.
        every { rows.next() } returns true
        every { rows.getLong("id") } returns 1L
        every { rows.getLong("stored_bytes") } returns 123L
        every { rows.getString(any<String>()) } answers { strings[firstArg()] }
        justRun { rows.close() }

        val feed = LostGalleryFeed(
            JdbcTemplate(source),
            mapper,
            Function { key ->
                signed.incrementAndGet()
                signer().apply(key)
            },
            TransactionOperations.withoutTransaction(),
            TransactionOperations.withoutTransaction(),
        )

        // when
        val snapshotFailure = assertThrows(IllegalStateException::class.java) { feed.snapshot(null) }
        val streamFailure = assertThrows(IllegalStateException::class.java) {
            feed.streamEntries(Consumer { published.incrementAndGet() })
        }

        // then
        assertEquals("Invalid archived photo metadata", snapshotFailure.message)
        assertEquals("Invalid archived photo metadata", streamFailure.message)
        assertEquals(0, signed.get())
        assertEquals(0, published.get())
        verify(exactly = 2) { rows.next() }
        verify(exactly = 2) { rows.close() }
        verify(exactly = 2) { statement.close() }
        verify(exactly = 0) { connection.metaData }
        for (extension in listOf("jpg", "png", "webp")) {
            assertDoesNotThrow(
                Executable {
                    LostGalleryFeed.validatePhoto(
                        LostGalleryFeed.StoredPhoto(
                            sha,
                            "apms/photos/" + sha + "." + extension,
                            123,
                            if (extension == "jpg") "image/jpeg" else "image/" + extension,
                        ),
                    )
                },
            )
        }
    }

    private fun transaction(active: AtomicBoolean): TransactionOperations {
        return object : TransactionOperations {
            override fun <T> execute(action: TransactionCallback<T>): T? {
                assertFalse(active.getAndSet(true))
                try {
                    return action.doInTransaction(SimpleTransactionStatus())
                } finally {
                    active.set(false)
                }
            }
        }
    }
}
