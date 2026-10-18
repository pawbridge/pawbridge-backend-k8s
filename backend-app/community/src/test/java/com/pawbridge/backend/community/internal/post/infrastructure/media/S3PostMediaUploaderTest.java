package com.pawbridge.backend.community.internal.post.infrastructure.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.community.internal.post.application.PostUpload;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class S3PostMediaUploaderTest {
    private static final String BUCKET = "f3-synthetic-bucket";
    private static final String PUBLIC_BASE = "https://media.example.test";
    private final S3Client s3 = mock(S3Client.class);
    private final S3PostMediaUploader uploader = new S3PostMediaUploader(s3, BUCKET, PUBLIC_BASE + "///", "");

    @Test
    void given__no_attachments_or_zero_size__when__upload__then__storage_and_content_are_not_opened() {
        PostUpload empty = new PostUpload("empty.txt", "text/plain", 0, () -> {
            throw new AssertionError("Empty attachments must not be opened");
        });

        assertTrue(uploader.upload(null).isEmpty());
        assertTrue(uploader.upload(List.of()).isEmpty());
        assertTrue(uploader.upload(List.of(empty)).isEmpty());
        verifyNoInteractions(s3);
    }

    @ParameterizedTest
    @MethodSource("allowedMedia")
    void given__allowed_media__when__upload__then__bytes_mime_folder_and_public_url_match_legacy(
            String mime, String folder) throws Exception {
        byte[] bytes = {0, 7, (byte) 255, 11};
        TrackingStream stream = new TrackingStream(bytes);
        List<PutObjectRequest> requests = new ArrayList<>();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            PutObjectRequest request = invocation.getArgument(0);
            RequestBody body = invocation.getArgument(1);
            requests.add(request);
            assertEquals((long) bytes.length, body.optionalContentLength().orElseThrow().longValue());
            assertArrayEquals(bytes, body.contentStreamProvider().newStream().readAllBytes());
            assertFalse(stream.closed);
            return null;
        });

        List<String> urls = uploader.upload(List.of(new PostUpload("pet.Photo.PNG", mime, bytes.length, () -> stream)));

        PutObjectRequest request = requests.getFirst();
        assertEquals(BUCKET, request.bucket());
        assertEquals(mime, request.contentType());
        assertTrue(request.key().matches(folder + "[0-9a-f-]{36}\\.PNG"));
        assertEquals(List.of(PUBLIC_BASE + "/" + request.key()), urls);
        assertTrue(stream.closed);
    }

    private static Stream<Arguments> allowedMedia() {
        return Stream.of(
                Arguments.of("image/jpeg", "posts/images/"),
                Arguments.of("image/jpg", "posts/images/"),
                Arguments.of("image/png", "posts/images/"),
                Arguments.of("image/gif", "posts/images/"),
                Arguments.of("image/webp", "posts/images/"),
                Arguments.of("video/mp4", "posts/videos/"),
                Arguments.of("video/quicktime", "posts/videos/"),
                Arguments.of("video/x-msvideo", "posts/videos/"),
                Arguments.of("video/x-matroska", "posts/videos/"),
                Arguments.of("IMAGE/PNG", "posts/images/"),
                Arguments.of("VIDEO/MP4", "posts/videos/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"f3-owner", "f3-owner/", "f3-owner/nested", "f3-owner/nested/"})
    void given__configured_prefix__when__upload__then__normalized_prefix_and_attachment_order_are_preserved(
            String prefix) {
        List<PutObjectRequest> requests = new ArrayList<>();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return null;
        });
        S3PostMediaUploader prefixed = new S3PostMediaUploader(s3, BUCKET, PUBLIC_BASE, prefix);

        List<String> urls = prefixed.upload(List.of(
                upload("first.png", "image/png", new byte[]{1}),
                upload("empty", null, new byte[]{}),
                upload("second.mp4", "video/mp4", new byte[]{2})));

        String normalized = prefix.endsWith("/") ? prefix : prefix + "/";
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).key().startsWith(normalized + "posts/images/"));
        assertTrue(requests.get(1).key().startsWith(normalized + "posts/videos/"));
        assertEquals(requests.stream().map(request -> PUBLIC_BASE + "/" + request.key()).toList(), urls);
    }

    @ParameterizedTest
    @MethodSource("filenames")
    void given__filename__when__upload__then__only_last_extension_is_preserved(String filename, String extension) {
        List<PutObjectRequest> requests = new ArrayList<>();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return null;
        });

        uploader.upload(List.of(upload(filename, "image/png", new byte[]{1})));

        assertTrue(requests.getFirst().key().matches("posts/images/[0-9a-f-]{36}" + extension));
    }

    private static Stream<Arguments> filenames() {
        return Stream.of(Arguments.of(null, ""), Arguments.of("no-extension", ""),
                Arguments.of("image.tar.GZ", "\\.GZ"), Arguments.of("image.", "\\."));
    }

    @ParameterizedTest
    @MethodSource("unsupportedMedia")
    void given__unsupported_media__when__upload__then__legacy_failure_is_wrapped_before_opening_content(
            String contentType, String expectedMessage) {
        PostUpload upload = new PostUpload("pet.txt", contentType, 1, () -> {
            throw new AssertionError("Rejected media must not be opened");
        });

        RuntimeException failure = assertThrows(RuntimeException.class, () -> uploader.upload(List.of(upload)));

        assertEquals("파일 업로드 중 오류가 발생했습니다", failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(expectedMessage, failure.getCause().getMessage());
        verifyNoInteractions(s3);
    }

    private static Stream<Arguments> unsupportedMedia() {
        return Stream.of(
                Arguments.of(null, "파일 타입을 확인할 수 없습니다. (파일: pet.txt)"),
                Arguments.of("text/plain", "지원하지 않는 파일 형식입니다. (업로드한 파일: pet.txt, 타입: text/plain). "
                        + "허용된 형식 - 이미지: jpg, jpeg, png, gif, webp / 영상: mp4, mov, avi, mkv"),
                Arguments.of("image/svg+xml", "지원하지 않는 파일 형식입니다. (업로드한 파일: pet.txt, 타입: image/svg+xml). "
                        + "허용된 형식 - 이미지: jpg, jpeg, png, gif, webp / 영상: mp4, mov, avi, mkv"));
    }

    @Test
    void given__opening_content_fails__when__upload__then__failure_is_wrapped_without_storage_call() {
        IOException cause = new IOException("synthetic read failure");
        PostUpload upload = new PostUpload("pet.png", "image/png", 1, () -> { throw cause; });

        RuntimeException failure = assertThrows(RuntimeException.class, () -> uploader.upload(List.of(upload)));

        assertEquals("파일 업로드 중 오류가 발생했습니다", failure.getMessage());
        assertSame(cause, failure.getCause());
        verifyNoInteractions(s3);
    }

    @Test
    void given__second_put_fails__when__upload__then__streams_close_and_no_cleanup_or_later_put_occurs() {
        TrackingStream first = new TrackingStream(new byte[]{1});
        TrackingStream second = new TrackingStream(new byte[]{2});
        IllegalStateException cause = new IllegalStateException("synthetic storage failure");
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(null).thenThrow(cause);
        PostUpload third = new PostUpload("third.png", "image/png", 1, () -> {
            throw new AssertionError("No later attachment may open after a failure");
        });

        RuntimeException failure = assertThrows(RuntimeException.class, () -> uploader.upload(List.of(
                new PostUpload("first.png", "image/png", 1, () -> first),
                new PostUpload("second.png", "image/png", 1, () -> second), third)));

        assertEquals("파일 업로드 중 오류가 발생했습니다", failure.getMessage());
        assertSame(cause, failure.getCause());
        assertTrue(first.closed);
        assertTrue(second.closed);
        verify(s3, times(2))
                .putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verifyNoMoreInteractions(s3);
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 200_000})
    void given__non_resettable_source__when__sdk_reads_for_signing_and_sending__then__fresh_bytes_and_closed_streams(int length) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) 7);
        List<TrackingStream> opened = new ArrayList<>();
        PostUpload upload = new PostUpload("disk.png", "image/png", length, () -> {
            TrackingStream stream = new TrackingStream(bytes);
            opened.add(stream);
            return new FilterInputStream(stream) {
                @Override public boolean markSupported() { return false; }
                @Override public void reset() throws IOException { throw new IOException("non-resettable disk source"); }
            };
        });
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            InputStream signing = body.contentStreamProvider().newStream();
            assertFalse(signing.markSupported());
            assertArrayEquals(bytes, signing.readAllBytes());
            InputStream sending = body.contentStreamProvider().newStream();
            assertArrayEquals(bytes, sending.readAllBytes());
            return null;
        });

        uploader.upload(List.of(upload));

        assertEquals(2, opened.size());
        assertTrue(opened.stream().allMatch(stream -> stream.closed));
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void given__source_reopen_failure__when__sdk_requests_another_read__then__previous_stream_closes_and_upload_fails() {
        TrackingStream first = new TrackingStream(new byte[]{1});
        IOException cause = new IOException("synthetic reopen failure");
        int[] opens = {0};
        PostUpload upload = new PostUpload("disk.png", "image/png", 1, () -> {
            if (opens[0]++ == 0) return first;
            throw cause;
        });
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            body.contentStreamProvider().newStream().readAllBytes();
            body.contentStreamProvider().newStream();
            return null;
        });

        RuntimeException failure = assertThrows(RuntimeException.class, () -> uploader.upload(List.of(upload)));

        assertSame(cause, assertInstanceOf(UncheckedIOException.class, failure.getCause()).getCause());
        assertTrue(first.closed);
        verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verifyNoMoreInteractions(s3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/f3/", "../", "f3/../", "f3//", " f3/", "f3/ ", "f3\\", "f3?x=1", "f3%2f"})
    void given__invalid_prefix__when__construct__then__legacy_configuration_validation_rejects_it(String prefix) {
        assertThrows(IllegalArgumentException.class,
                () -> new S3PostMediaUploader(s3, BUCKET, PUBLIC_BASE, prefix));
        verifyNoInteractions(s3);
    }

    private static PostUpload upload(String filename, String mime, byte[] bytes) {
        return new PostUpload(filename, mime, bytes.length, () -> new ByteArrayInputStream(bytes));
    }

    private static final class TrackingStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
