package com.pawbridge.backend.community.internal.post.infrastructure.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

class S3PostMediaRemoverTest {
    private final S3Client s3 = mock(S3Client.class);
    private final String base = "https://fixture.invalid";
    private final S3PostMediaRemover remover = new S3PostMediaRemover(s3, "synthetic-bucket", base + "///", "owner");

    @Test
    void given__null_or_empty_list__when__remove__then__sdk_is_not_called() {
        remover.remove(null);
        remover.remove(List.of());
        verifyNoInteractions(s3);
    }

    @Test
    void given__duplicate_null_and_ordered_urls__when__remove__then__valid_calls_preserve_order_and_duplicates() {
        List<DeleteObjectRequest> requests = new ArrayList<>();
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            return null;
        });
        remover.remove(Arrays.asList(base + "/owner/posts/images/first.png", null,
                base + "/owner/posts/images/first.png", base + "/owner/posts/videos/last.mp4"));
        assertEquals(List.of("owner/posts/images/first.png", "owner/posts/images/first.png", "owner/posts/videos/last.mp4"),
                requests.stream().map(DeleteObjectRequest::key).toList());
        assertTrue(requests.stream().allMatch(request -> "synthetic-bucket".equals(request.bucket())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://elsewhere.invalid/owner/a", "https://fixture.invalid.evil/owner/a",
            "http://fixture.invalid/owner/a", "https://fixture.invalid/other/a", "https://fixture.invalid/owner/",
            "https://fixture.invalid/owner/a%2fb", "https://fixture.invalid/owner/a%252fb",
            "https://fixture.invalid/owner/../a", "https://fixture.invalid/owner/./a",
            "https://fixture.invalid/owner//a", "https://fixture.invalid/owner/a?token=secret",
            "https://fixture.invalid/owner/a#fragment", "https://fixture.invalid/owner/a\\b", ""})
    void given__invalid_url_before_valid_url__when__remove__then__reject_only_that_url_and_continue(String invalid) {
        remover.remove(List.of(invalid, base + "/owner/posts/images/next.png"));
        verify(s3).deleteObject(DeleteObjectRequest.builder().bucket("synthetic-bucket").key("owner/posts/images/next.png").build());
        verifyNoMoreInteractions(s3);
    }

    @Test
    void given__sdk_failure_on_first_url__when__remove__then__next_url_is_attempted_without_application_retry() {
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new IllegalStateException("secret signed URL must not escape"))
                .thenReturn(null);
        assertDoesNotThrow(() -> remover.remove(List.of(base + "/owner/first", base + "/owner/second")));
        var order = inOrder(s3);
        order.verify(s3).deleteObject(DeleteObjectRequest.builder().bucket("synthetic-bucket").key("owner/first").build());
        order.verify(s3).deleteObject(DeleteObjectRequest.builder().bucket("synthetic-bucket").key("owner/second").build());
        order.verifyNoMoreInteractions();
    }

    @Test
    void given__empty_configured_prefix__when__remove__then__owned_base_is_allowed_but_traversal_and_encoding_are_not() {
        var root = new S3PostMediaRemover(s3, "synthetic-bucket", base, "");
        root.remove(List.of(base + "/posts/images/a.png", base + "/../outside", base + "/a%2fb", base + "/a?token=x"));
        verify(s3).deleteObject(DeleteObjectRequest.builder().bucket("synthetic-bucket").key("posts/images/a.png").build());
        verifyNoMoreInteractions(s3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/owner/", "../", "owner/../", "owner//", " owner/", "owner/ ", "owner\\", "owner?x=1", "owner%2f"})
    void given__invalid_configured_prefix__when__construct__then__reject_without_sdk_calls(String prefix) {
        assertThrows(IllegalArgumentException.class, () -> new S3PostMediaRemover(s3, "synthetic-bucket", base, prefix));
        verifyNoInteractions(s3);
    }
}
