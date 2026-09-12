package com.pawbridge.animalservice.photo;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhotoArchiveHttpClientTest {
    private final HttpClient transport=mock(HttpClient.class);
    private final byte[] raw={1,2,3};
    private PhotoArchiveHttpClient client() {
        var p=new PhotoArchiveProperties(); p.setOptimizerUrl(URI.create("http://optimizer/internal/photos/optimize"));
        p.setInternalApiKey("test-only-key"); return new PhotoArchiveHttpClient(transport,p);
    }
    private Map<String,List<String>> headers(byte[] bytes) {
        return new HashMap<>(Map.of("Content-Type",List.of("image/png"),"X-Source-Sha256",List.of(ArchivedPhoto.sha256(raw)),
                "X-Stored-Sha256",List.of(ArchivedPhoto.sha256(bytes)),"X-Photo-Width",List.of("1"),
                "X-Photo-Height",List.of("1"),"X-Photo-Recipe",List.of("original-v1")));
    }
    @SuppressWarnings("unchecked")
    private void respond(int status, byte[] bytes, Map<String,List<String>> headers) {
        HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(bytes);
        when(response.headers()).thenReturn(HttpHeaders.of(headers,(a,b)->true));
        when(transport.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }
    @Test void arbitrary_host_userinfo_and_nonstandard_port_are_rejected_before_network() {
        for (String url:List.of("http://127.0.0.1/x","http://openapi.animal.go.kr.evil.test/x",
                "http://user@openapi.animal.go.kr/x","file:///tmp/x","http://openapi.animal.go.kr:8080/x"))
            assertThatThrownBy(()->client().download(url)).hasMessage("SOURCE_URL");
        verifyNoInteractions(transport);
    }
    @Test void redirect_is_not_treated_as_photo_and_download_has_no_internal_key() {
        respond(302,new byte[0],Map.of("Location",List.of("http://127.0.0.1/private")));
        assertThatThrownBy(()->client().download("https://openapi.animal.go.kr/photo")).hasMessage("SOURCE_HTTP_302");
        var request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).sendAsync(request.capture(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertThat(request.getValue().headers().firstValue("X-Internal-API-Key")).isEmpty();
    }
    @Test void valid_optimizer_contract_preserves_original_and_sets_internal_key() {
        respond(200,raw,headers(raw)); var photo=client().optimize(raw);
        assertThat(photo.bytes()).isEqualTo(raw);
        assertThat(photo.objectKey()).isEqualTo("apms/photos/"+ArchivedPhoto.sha256(raw)+".png");
        var request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).sendAsync(request.capture(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertThat(request.getValue().headers().firstValue("X-Internal-API-Key")).contains("test-only-key");
    }
    @Test void wrong_hash_duplicate_header_and_oversized_dimensions_fail_contract() {
        for (var change:Map.of("X-Source-Sha256",List.of("wrong"),"X-Stored-Sha256",List.of("wrong"),
                "X-Photo-Width",List.of("99999999"),"Content-Type",List.of("image/png","image/jpeg")).entrySet()) {
            var h=headers(raw); h.put(change.getKey(),change.getValue()); respond(200,raw,h);
            assertThatThrownBy(()->client().optimize(raw)).hasMessage("OPTIMIZER_CONTRACT");
        }
    }
    @Test void original_recipe_cannot_return_changed_bytes_even_with_valid_hash() {
        byte[] changed={3,2,1}; respond(200,changed,headers(changed));
        assertThatThrownBy(()->client().optimize(raw)).hasMessage("OPTIMIZER_CONTRACT");
    }
    @Test void optimizer_unavailable_is_retryable_and_does_not_fall_back_silently() {
        respond(503,new byte[0],Map.of());
        assertThatThrownBy(()->client().optimize(raw)).isInstanceOfSatisfying(PhotoArchiveFailure.class,
                failure->assertThat(failure.slowRetry()).isFalse());
    }
    @Test void streaming_limit_cancels_without_content_length() {
        var body=new PhotoArchiveHttpClient.LimitedBody(); var subscription=mock(Flow.Subscription.class);
        body.onSubscribe(subscription); body.onNext(List.of(ByteBuffer.wrap(new byte[ArchivedPhoto.MAX_BYTES]),ByteBuffer.wrap(new byte[1])));
        verify(subscription).cancel(); assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
    }
}
