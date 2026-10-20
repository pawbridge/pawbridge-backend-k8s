package com.pawbridge.backend.animal.internal.gallery.infrastructure;

import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController;
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LostGalleryFeedTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String sha = "a".repeat(64);

    java.util.function.Function<String,String> signer() {
        return key -> "https://test.r2.cloudflarestorage.com/pawbridge-animal-originals/" + key + "?fake-signature=unit";
    }

    @Test
    void snapshot_delegates_private_object_keys_and_same_content_returns_no_body() throws Exception {
        {
            var readActive = new java.util.concurrent.atomic.AtomicBoolean();
            var signedKeys = new java.util.ArrayList<String>();
            java.util.function.Function<String,String> signer = key -> {
                assertFalse(readActive.get(), "v1 signing must happen after its database read transaction");
                signedKeys.add(key); return signer().apply(key);
            };
            var feed = new LostGalleryFeed(null, mapper, signer, transaction(readActive), org.springframework.transaction.support.TransactionOperations.withoutTransaction()) {
                @Override Content readContent() {
                    assertTrue(readActive.get(), "v1 content must execute inside its explicit reader transaction");
                    return new Content(List.of(Map.of("id", 1, "species", "DOG", "source_sha256", sha, "status", "PROTECT",
                                    "happen_place", "상주시")),
                            List.of(new StoredPhoto(sha, "apms/photos/" + sha + ".jpg", 123, "image/jpeg")));
                }
            };
            var first = feed.snapshot(null);
            var json = mapper.readTree(first.body());
            assertTrue(json.get("complete").asBoolean());
            assertEquals(1, json.get("count").asInt());
            assertEquals("PROTECT", json.get("records").get(0).get("status").asText());
            assertEquals("상주시", json.get("records").get(0).get("happen_place").asText());
            var url = URI.create(json.get("photos").get(0).get("url").asText());
            assertEquals("test.r2.cloudflarestorage.com", url.getHost());
            assertEquals("/pawbridge-animal-originals/apms/photos/" + sha + ".jpg", url.getPath());
            assertEquals("fake-signature=unit", url.getQuery());
            assertEquals(List.of("apms/photos/" + sha + ".jpg"), signedKeys);
            assertFalse(new String(first.body(), java.nio.charset.StandardCharsets.UTF_8).contains("test-secret"));
            var unchanged = feed.snapshot(first.etag());
            assertEquals(first.etag(), unchanged.etag());
            assertNull(unchanged.body());
            assertEquals(1, signedKeys.size(), "unchanged content must not be signed again");
        }
    }

    @Test
    void feed_authentication_precedes_data_access_and_failure_does_not_expose_partial_content() throws Exception {
        var feed = mock(LostGalleryFeed.class);
        var mvc = MockMvcBuilders.standaloneSetup(new LostGalleryFeedController(feed, "test-key"))
                .build();
        mvc.perform(get("/internal/animals/lost-gallery")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/animals/lost-gallery").header("X-Internal-Api-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(feed);
        when(feed.snapshot(null)).thenThrow(new IllegalStateException("sensitive upstream details"));
        mvc.perform(get("/internal/animals/lost-gallery").header("X-Internal-Api-Key", "test-key"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(""));
    }

    @Test
    void unchanged_feed_preserves_etag_and_does_not_cache_signed_urls() throws Exception {
        var feed = mock(LostGalleryFeed.class);
        String etag = "\"" + sha + "\"";
        when(feed.snapshot(etag)).thenReturn(new LostGalleryFeed.Result(etag, null));
        var mvc = MockMvcBuilders.standaloneSetup(new LostGalleryFeedController(feed, "test-key"))
                .build();
        mvc.perform(get("/internal/animals/lost-gallery").header("X-Internal-Api-Key", "test-key")
                .header("If-None-Match", etag)).andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag)).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
    }

    @Test
    void untrusted_object_paths_and_missing_integrity_metadata_are_rejected_before_signing() {
        for (var photo : List.of(new LostGalleryFeed.StoredPhoto(sha, "other/private.jpg", 1, "image/jpeg"),
                new LostGalleryFeed.StoredPhoto(sha, "apms/photos/"+sha+".jpg", 0, "image/jpeg"),
                new LostGalleryFeed.StoredPhoto("bad", "apms/photos/"+sha+".jpg", 1, "image/jpeg"))) {
            assertThrows(IllegalStateException.class, () -> LostGalleryFeed.validatePhoto(photo));
        }
    }
    @Test
    void legacy_feed_stops_at_500_rows_and_stream_failure_closes_the_jdbc_cursor() throws Exception {
        var source = mock(javax.sql.DataSource.class);
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.PreparedStatement.class);
        var result = mock(java.sql.ResultSet.class);
        var conflicts = mock(java.sql.PreparedStatement.class);
        var conflictResult = mock(java.sql.ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(LostGalleryFeed.SQL)).thenReturn(statement);
        when(connection.prepareStatement(argThat(sql -> sql != null && sql.startsWith("SELECT stored_sha256")))).thenReturn(conflicts);
        when(conflicts.executeQuery()).thenReturn(conflictResult);
        when(statement.executeQuery()).thenReturn(result);
        var row = new java.util.concurrent.atomic.AtomicInteger();
        when(result.next()).thenAnswer(invocation -> row.incrementAndGet() <= 501);
        when(result.getLong("id")).thenAnswer(invocation -> (long) row.get());
        when(result.getLong("stored_bytes")).thenReturn(123L);
        when(result.getString("stored_sha256")).thenReturn(sha);
        when(result.getString("object_key")).thenReturn("apms/photos/" + sha + ".jpg");
        when(result.getString("species")).thenReturn("DOG");
        when(result.getString("status")).thenReturn("PROTECT");
        when(result.getString("content_type")).thenReturn("image/jpeg");
        var streamActive = new java.util.concurrent.atomic.AtomicBoolean();
        var feed = new LostGalleryFeed(new org.springframework.jdbc.core.JdbcTemplate(source), mapper, null,
                org.springframework.transaction.support.TransactionOperations.withoutTransaction(), transaction(streamActive));
        assertThrows(IllegalStateException.class, feed::readContent);
        assertEquals(501, row.get());
        verify(result).close();
        clearInvocations(result, statement);
        row.set(0);
        assertThrows(IllegalStateException.class, () -> feed.streamEntries(entry -> {
            assertTrue(streamActive.get(), "source rows must be consumed inside the explicit stream transaction");
            assertEquals(1L, entry.record().get("id"));
            assertEquals("PROTECT", entry.record().get("status"));
            throw new IllegalStateException("consumer storage full");
        }));
        assertEquals(1, row.get());
        verify(statement).setFetchSize(200);
        verify(connection, never()).getMetaData();
        assertFalse(streamActive.get(), "failed source must exit its stream transaction");
        verify(result).close();
        verify(statement).close();
    }


    @Test
    void givenObjectKeyWithAnotherDigest_whenReadingEitherFeed_thenRejectBeforeSigningOrPublishingAndCloseCursor() throws Exception {
        var source=mock(javax.sql.DataSource.class);var connection=mock(java.sql.Connection.class);
        var statement=mock(java.sql.PreparedStatement.class);var rows=mock(java.sql.ResultSet.class);
        var conflict=mock(java.sql.PreparedStatement.class);var conflictRows=mock(java.sql.ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(LostGalleryFeed.SQL)).thenReturn(statement);
        when(connection.prepareStatement(argThat(sql->sql!=null&&sql.startsWith("SELECT stored_sha256")))).thenReturn(conflict);
        when(conflict.executeQuery()).thenReturn(conflictRows);when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true,false);when(rows.getLong("id")).thenReturn(1L);
        when(rows.getLong("stored_bytes")).thenReturn(123L);when(rows.getString("content_type")).thenReturn("image/jpeg");
        when(rows.getString("stored_sha256")).thenReturn(sha);
        when(rows.getString("object_key")).thenReturn("apms/photos/"+"b".repeat(64)+".jpg");
        when(rows.getString("species")).thenReturn("DOG");
        var signed=new java.util.concurrent.atomic.AtomicInteger();var published=new java.util.concurrent.atomic.AtomicInteger();
        var feed=new LostGalleryFeed(new org.springframework.jdbc.core.JdbcTemplate(source),mapper,
                key->{signed.incrementAndGet();return signer().apply(key);},
                org.springframework.transaction.support.TransactionOperations.withoutTransaction(),
                org.springframework.transaction.support.TransactionOperations.withoutTransaction());
        assertEquals("Invalid archived photo metadata",assertThrows(IllegalStateException.class,()->feed.snapshot(null)).getMessage());
        when(rows.next()).thenReturn(true,false);
        assertEquals("Invalid archived photo metadata",assertThrows(IllegalStateException.class,
                ()->feed.streamEntries(entry->published.incrementAndGet())).getMessage());
        assertEquals(0,signed.get());assertEquals(0,published.get());
        verify(rows,times(2)).close();verify(statement,times(2)).close();
        verify(connection,never()).getMetaData();
        for(String extension:List.of("jpg","png","webp"))
            assertDoesNotThrow(()->LostGalleryFeed.validatePhoto(new LostGalleryFeed.StoredPhoto(sha,"apms/photos/"+sha+"."+extension,123,
                    extension.equals("jpg")?"image/jpeg":"image/"+extension)));
    }

    private static org.springframework.transaction.support.TransactionOperations transaction(java.util.concurrent.atomic.AtomicBoolean active) {
        return new org.springframework.transaction.support.TransactionOperations() {
            @Override public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                assertFalse(active.getAndSet(true));
                try { return action.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus()); }
                finally { active.set(false); }
            }
        };
    }
}
