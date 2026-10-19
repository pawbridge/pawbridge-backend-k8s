package com.pawbridge.backend.community.internal.post.infrastructure.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.community.internal.post.application.PostUpload;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

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

    @ParameterizedTest
    @MethodSource("opaqueUploadSuffixes")
    void given__uploader_generated_opaque_key__when__removing_saved_url__then__delete_exact_put_key_without_reinterpretation(
            String suffix, String mime) {
        List<PutObjectRequest> uploaded = new ArrayList<>();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            uploaded.add(call.getArgument(0));
            return null;
        });
        var uploader = new S3PostMediaUploader(s3, "synthetic-bucket", base, "owner");
        var file = new PostUpload("pet" + suffix, mime, 1, () -> new ByteArrayInputStream(new byte[]{1}));
        List<String> savedUrls = uploader.upload(List.of(file));

        remover.remove(savedUrls);

        PutObjectRequest put = uploaded.getFirst();
        String folder = "owner/posts/" + (mime.startsWith("video/") ? "videos/" : "images/");
        assertTrue(put.key().startsWith(folder));
        assertEquals(suffix, put.key().substring(folder.length() + 36));
        assertEquals(List.of(base + "/" + put.key()), savedUrls);
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3).deleteObject(DeleteObjectRequest.builder().bucket(put.bucket()).key(put.key()).build());
        verifyNoMoreInteractions(s3);
    }

    private static Stream<Arguments> opaqueUploadSuffixes() {
        return Stream.of("", ".", ".pn%67", ".png%2Fopaque", ".png?token=synthetic", ".png#fragment",
                ".pn\\g", ".png/segment", ".png//segment", ".png/%2e%2e/object",
                ".png?target=//elsewhere/object", ".png\nline", ".png\rline", ".png\tline", ".png\0tail", ".png\177tail")
                .flatMap(suffix -> Stream.of(Arguments.of(suffix, "image/png"), Arguments.of(suffix, "video/mp4")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://elsewhere.invalid/owner/posts/images/12345678-1234-4234-8234-123456789abc.png?x",
            "https://fixture.invalid/other/posts/images/12345678-1234-4234-8234-123456789abc.png?x",
            "https://fixture.invalid/owner%2fposts/images/12345678-1234-4234-8234-123456789abc.png?x",
            "https://fixture.invalid/owner/posts%2fimages/12345678-1234-4234-8234-123456789abc.png?x",
            "https://fixture.invalid/owner/posts/images/not-a-uuid.png?x",
            "https://fixture.invalid/owner/posts/images/12345678-1234-4234-8234-123456789ABC.png?x",
            "https://fixture.invalid/owner/posts/images/12345678-1234-1234-8234-123456789abc.png?x",
            "https://fixture.invalid/owner/posts/reports/12345678-1234-4234-8234-123456789abc.png?x",
            "https://fixture.invalid/owner/posts/images/12345678-1234-4234-8234-123456789abc.part.png?x",
            "https://fixture.invalid/owner/posts/images/12345678-1234-4234-8234-123456789abc.png/../other",
            "https://fixture.invalid/owner/posts/images/\n12345678-1234-4234-8234-123456789abc.png"})
    void given__opaque_characters_outside_exact_uploader_grammar__when__remove__then__retain_scope_and_traversal_guards(String url) {
        remover.remove(List.of(url));
        verifyNoInteractions(s3);
    }
}
