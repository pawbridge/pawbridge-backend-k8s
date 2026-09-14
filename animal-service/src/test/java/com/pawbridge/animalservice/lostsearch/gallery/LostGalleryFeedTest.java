package com.pawbridge.animalservice.lostsearch.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.animalservice.exception.GlobalExceptionHandler;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LostGalleryFeedTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String sha = "a".repeat(64);

    private S3Presigner signer() {
        return S3Presigner.builder().region(Region.of("auto"))
                .endpointOverride(URI.create("https://test.r2.cloudflarestorage.com"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret")))
                .build();
    }

    @Test
    void snapshot_signs_private_get_urls_and_same_content_returns_no_body() throws Exception {
        try (var signer = signer()) {
            var feed = new LostGalleryFeed(null, mapper, signer) {
                @Override Content readContent() {
                    return new Content(List.of(Map.of("id", 1, "species", "DOG", "source_sha256", sha,
                                    "happen_place", "상주시")),
                            List.of(new StoredPhoto(sha, "apms/photos/" + sha + ".jpg", 123, "image/jpeg")));
                }
            };
            var first = feed.snapshot(null);
            var json = mapper.readTree(first.body());
            assertTrue(json.get("complete").asBoolean());
            assertEquals(1, json.get("count").asInt());
            assertEquals("상주시", json.get("records").get(0).get("happen_place").asText());
            var url = URI.create(json.get("photos").get(0).get("url").asText());
            assertEquals("test.r2.cloudflarestorage.com", url.getHost());
            assertEquals("/pawbridge-animal-originals/apms/photos/" + sha + ".jpg", url.getPath());
            assertTrue(url.getQuery().contains("X-Amz-Expires=3600"));
            assertFalse(new String(first.body(), java.nio.charset.StandardCharsets.UTF_8).contains("test-secret"));
            var unchanged = feed.snapshot(first.etag());
            assertEquals(first.etag(), unchanged.etag());
            assertNull(unchanged.body());
        }
    }

    @Test
    void feed_authentication_precedes_data_access_and_failure_does_not_expose_partial_content() throws Exception {
        var feed = mock(LostGalleryFeed.class);
        var mvc = MockMvcBuilders.standaloneSetup(new LostGalleryFeedController(feed, "test-key"))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
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
                .setControllerAdvice(new GlobalExceptionHandler()).build();
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
}
