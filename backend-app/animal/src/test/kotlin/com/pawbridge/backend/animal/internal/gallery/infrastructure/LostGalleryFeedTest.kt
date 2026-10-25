package com.pawbridge.backend.animal.internal.gallery.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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
    fun snapshot_delegates_private_object_keys_and_same_content_returns_no_body() {
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

        val first = feed.snapshot(null)

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
        val unchanged = feed.snapshot(first.etag)
        assertEquals(first.etag, unchanged.etag)
        assertNull(unchanged.body)
        assertEquals(1, signedKeys.size, "unchanged content must not be signed again")
    }

    @Test
    fun feed_authentication_precedes_data_access_and_failure_does_not_expose_partial_content() {
        val feed = mock(LostGalleryFeed::class.java)
        val mvc = MockMvcBuilders.standaloneSetup(LostGalleryFeedController(feed, "test-key")).build()

        mvc.perform(get("/internal/animals/lost-gallery")).andExpect(status().isUnauthorized())
        mvc.perform(get("/internal/animals/lost-gallery").header("X-Internal-Api-Key", "wrong"))
            .andExpect(status().isUnauthorized())
        verifyNoInteractions(feed)
        `when`(feed.snapshot(null)).thenThrow(IllegalStateException("sensitive upstream details"))
        mvc.perform(get("/internal/animals/lost-gallery").header("X-Internal-Api-Key", "test-key"))
            .andExpect(status().isServiceUnavailable()).andExpect(content().string(""))
    }

    @Test
    fun unchanged_feed_preserves_etag_and_does_not_cache_signed_urls() {
        val feed = mock(LostGalleryFeed::class.java)
        val etag = "\"" + sha + "\""
        `when`(feed.snapshot(etag)).thenReturn(LostGalleryFeed.Result(etag, null))
        val mvc = MockMvcBuilders.standaloneSetup(LostGalleryFeedController(feed, "test-key")).build()

        mvc.perform(
            get("/internal/animals/lost-gallery").header("X-Internal-Api-Key", "test-key")
                .header("If-None-Match", etag),
        ).andExpect(status().isNotModified())
            .andExpect(header().string("ETag", etag)).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().string(""))
    }

    @Test
    fun untrusted_object_paths_and_missing_integrity_metadata_are_rejected_before_signing() {
        for (
            photo in listOf(
                LostGalleryFeed.StoredPhoto(sha, "other/private.jpg", 1, "image/jpeg"),
                LostGalleryFeed.StoredPhoto(sha, "apms/photos/" + sha + ".jpg", 0, "image/jpeg"),
                LostGalleryFeed.StoredPhoto("bad", "apms/photos/" + sha + ".jpg", 1, "image/jpeg"),
            )
        ) {
            assertThrows(IllegalStateException::class.java) { LostGalleryFeed.validatePhoto(photo) }
        }
    }

    @Test
    fun legacy_feed_stops_at_500_rows_and_stream_failure_closes_the_jdbc_cursor() {
        val source = mock(DataSource::class.java)
        val connection = mock(Connection::class.java)
        val statement = mock(PreparedStatement::class.java)
        val result = mock(ResultSet::class.java)
        val conflicts = mock(PreparedStatement::class.java)
        val conflictResult = mock(ResultSet::class.java)
        `when`(source.connection).thenReturn(connection)
        `when`(connection.prepareStatement(LostGalleryFeed.SQL)).thenReturn(statement)
        `when`(connection.prepareStatement(argThat(ArgumentMatcher<String> { sql -> sql != null && sql.startsWith("SELECT stored_sha256") })))
            .thenReturn(conflicts)
        `when`(conflicts.executeQuery()).thenReturn(conflictResult)
        `when`(statement.executeQuery()).thenReturn(result)
        val row = AtomicInteger()
        `when`(result.next()).thenAnswer { row.incrementAndGet() <= 501 }
        `when`(result.getLong("id")).thenAnswer { row.get().toLong() }
        `when`(result.getLong("stored_bytes")).thenReturn(123L)
        `when`(result.getString("stored_sha256")).thenReturn(sha)
        `when`(result.getString("object_key")).thenReturn("apms/photos/" + sha + ".jpg")
        `when`(result.getString("species")).thenReturn("DOG")
        `when`(result.getString("status")).thenReturn("PROTECT")
        `when`(result.getString("content_type")).thenReturn("image/jpeg")
        val streamActive = AtomicBoolean()
        val feed = ReadableFeed(
            JdbcTemplate(source),
            mapper,
            null,
            TransactionOperations.withoutTransaction(),
            transaction(streamActive),
        )

        assertThrows(IllegalStateException::class.java) { feed.content() }

        assertEquals(501, row.get())
        verify(result).close()
        clearInvocations(result, statement)
        row.set(0)
        assertThrows(IllegalStateException::class.java) {
            feed.streamEntries(
                Consumer { entry ->
                    assertTrue(streamActive.get(), "source rows must be consumed inside the explicit stream transaction")
                    assertEquals(1L, entry.record["id"])
                    assertEquals("PROTECT", entry.record["status"])
                    throw IllegalStateException("consumer storage full")
                },
            )
        }
        assertEquals(1, row.get())
        verify(statement).setFetchSize(200)
        verify(connection, never()).getMetaData()
        assertFalse(streamActive.get(), "failed source must exit its stream transaction")
        verify(result).close()
        verify(statement).close()
    }

    @Test
    fun givenObjectKeyWithAnotherDigest_whenReadingEitherFeed_thenRejectBeforeSigningOrPublishingAndCloseCursor() {
        val source = mock(DataSource::class.java)
        val connection = mock(Connection::class.java)
        val statement = mock(PreparedStatement::class.java)
        val rows = mock(ResultSet::class.java)
        val conflict = mock(PreparedStatement::class.java)
        val conflictRows = mock(ResultSet::class.java)
        `when`(source.connection).thenReturn(connection)
        `when`(connection.prepareStatement(LostGalleryFeed.SQL)).thenReturn(statement)
        `when`(connection.prepareStatement(argThat(ArgumentMatcher<String> { sql -> sql != null && sql.startsWith("SELECT stored_sha256") })))
            .thenReturn(conflict)
        `when`(conflict.executeQuery()).thenReturn(conflictRows)
        `when`(statement.executeQuery()).thenReturn(rows)
        `when`(rows.next()).thenReturn(true, false)
        `when`(rows.getLong("id")).thenReturn(1L)
        `when`(rows.getLong("stored_bytes")).thenReturn(123L)
        `when`(rows.getString("content_type")).thenReturn("image/jpeg")
        `when`(rows.getString("stored_sha256")).thenReturn(sha)
        `when`(rows.getString("object_key")).thenReturn("apms/photos/" + "b".repeat(64) + ".jpg")
        `when`(rows.getString("species")).thenReturn("DOG")
        val signed = AtomicInteger()
        val published = AtomicInteger()
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

        assertEquals(
            "Invalid archived photo metadata",
            assertThrows(IllegalStateException::class.java) { feed.snapshot(null) }.message,
        )
        `when`(rows.next()).thenReturn(true, false)
        assertEquals(
            "Invalid archived photo metadata",
            assertThrows(IllegalStateException::class.java) {
                feed.streamEntries(Consumer { published.incrementAndGet() })
            }.message,
        )

        assertEquals(0, signed.get())
        assertEquals(0, published.get())
        verify(rows, times(2)).close()
        verify(statement, times(2)).close()
        verify(connection, never()).getMetaData()
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
