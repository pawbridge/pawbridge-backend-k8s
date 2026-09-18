package com.pawbridge.animalservice.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TourApiClientTest {
    private static final String KEY = "syntheticOnly1234+/=";
    private final TourApiProperties properties = properties();
    private final HttpClient http = mock(HttpClient.class);
    private final TourApiClient client = new TourApiClient(properties, new ObjectMapper(), http);

    @Test
    void givenBulkPetPage__whenFetch__thenUseSeparateKeyAndServiceWithoutContentId() {
        properties.setBulkPetEnabled(true);
        properties.setKoreanServiceKey("koreanSynthetic1234%2B%2F%3D");
        reply(200, bulkPayload("[{\"contentid\":\"123\",\"acmpyNeedMtr\":\"목줄\"}]",101));
        var page=client.fetchPage(TourApiClient.Operation.PET_BULK,"",2);
        assertThat(page.totalCount()).isEqualTo(101);
        assertThat(page.items().get(0)).containsEntry("acmpyNeedMtr","목줄");
        var request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().getPath()).isEqualTo("/B551011/KorService2/detailPetTour2");
        assertThat(request.getValue().uri().getRawQuery()).contains("pageNo=2","numOfRows=100","serviceKey=koreanSynthetic1234%2B%2F%3D")
                .doesNotContain("contentId","syntheticOnly");
    }

    @Test
    void givenDisabledOrMissingKoreanKey__whenFetch__thenNeverFallbackToPetServiceKey() {
        assertThatThrownBy(()->client.fetch(TourApiClient.Operation.PET_BULK,"")).hasMessage("UNAVAILABLE");
        properties.setBulkPetEnabled(true);
        assertThatThrownBy(()->client.fetch(TourApiClient.Operation.PET_BULK,"")).hasMessage("UNAVAILABLE");
        assertThatThrownBy(()->client.fetch(TourApiClient.Operation.COMMON,"123")).hasMessage("UNAVAILABLE");
        verifyNoInteractions(http);
    }

    @Test
    void givenDisplayDetail__whenFetch__thenUseKoreanServiceAndCredential() {
        properties.setKoreanServiceKey("koreanSynthetic1234%2B%2F%3D");
        for (var operation : List.of(TourApiClient.Operation.COMMON, TourApiClient.Operation.PET)) {
            reset(http);
            reply(200, payload("{\"contentid\":\"123\",\"title\":\"장소\"}"));
            assertThat(client.fetch(operation,"123")).hasSize(1);
            var request=ArgumentCaptor.forClass(HttpRequest.class);
            verify(http).sendAsync(request.capture(),any(HttpResponse.BodyHandler.class));
            assertThat(request.getValue().uri().getPath()).isEqualTo("/B551011/KorService2/" + operation.path);
            assertThat(request.getValue().uri().getRawQuery())
                    .contains("contentId=123","serviceKey=koreanSynthetic1234%2B%2F%3D")
                    .doesNotContain("syntheticOnly");
        }
    }

    @Test
    void givenVisitResources__whenFetch__thenUseContentTypeOnlyWhereRequiredAndRetainFields() {
        properties.setKoreanServiceKey("koreanSynthetic1234%2B%2F%3D");
        reply(200, bulkPayload("[{\"contentid\":\"123\",\"contenttypeid\":\"12\",\"usetime\":\"09:00~18:00\"," +
                "\"expguide\":\"해설 프로그램\",\"useseason\":\"연중\"}]",1));
        assertThat(client.fetchDetail(TourApiClient.Operation.INTRO,"123","12").get(0))
                .containsEntry("usetime","09:00~18:00")
                .containsEntry("expguide","해설 프로그램")
                .containsEntry("useseason","연중");
        var request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().getPath()).endsWith("/detailIntro2");
        assertThat(request.getValue().uri().getRawQuery()).contains("contentId=123","contentTypeId=12","numOfRows=1");

        reset(http);
        reply(200, bulkPayload("[{\"contentid\":\"123\",\"contenttypeid\":\"12\",\"serialnum\":\"1\",\"infoname\":\"안내\",\"infotext\":\"설명\"}]",1));
        assertThat(client.fetchDetail(TourApiClient.Operation.INFO,"123","12").get(0)).containsEntry("infoname","안내");

        reset(http);
        reply(200, bulkPayload("[{\"contentid\":\"123\",\"serialnum\":\"1\",\"originimgurl\":\"https://tong.visitkorea.or.kr/cms/resource/1/a.jpg\",\"cpyrhtDivCd\":\"Type1\"}]",1));
        assertThat(client.fetchDetail(TourApiClient.Operation.IMAGES,"123","12").get(0)).containsEntry("serialnum","1");
        request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().getPath()).endsWith("/detailImage2");
        assertThat(request.getValue().uri().getRawQuery()).contains("contentId=123","imageYN=Y","numOfRows=100")
                .doesNotContain("contentTypeId");
    }

    @Test
    void givenVisitResourceMoreThanOnePage__whenFetch__thenRejectPartialSnapshot() {
        properties.setKoreanServiceKey(KEY);
        reply(200,bulkPayload("[{\"contentid\":\"123\",\"contenttypeid\":\"12\",\"serialnum\":\"1\",\"infoname\":\"안내\"}]",101));
        assertThatThrownBy(()->client.fetchDetail(TourApiClient.Operation.INFO,"123","12"))
                .hasMessage("UNAVAILABLE");
    }

    @Test
    void givenVisitResourceHasNoRows__whenFetch__thenAcceptSuccessfulEmptySnapshot() {
        properties.setKoreanServiceKey(KEY);
        reply(200,("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":0,"
                + "\"items\":\"\"}}}").getBytes(StandardCharsets.UTF_8));
        assertThat(client.fetchDetail(TourApiClient.Operation.INTRO,"123","28")).isEmpty();
    }

    @Test
    void givenDuplicateBulkIdentities__whenFetch__thenRejectPage() {
        properties.setBulkPetEnabled(true);properties.setBulkServiceKey(KEY);
        reply(200,bulkPayload("[{\"contentid\":\"123\"},{\"contentid\":\"123\"}]",2));
        assertThatThrownBy(()->client.fetch(TourApiClient.Operation.PET_BULK,"")).hasMessage("UNAVAILABLE").hasNoCause();
    }

    @Test
    void givenBulkWithoutTotalCount__whenFetch__thenDoNotInferCompletionFromPageSize() {
        properties.setBulkPetEnabled(true);properties.setBulkServiceKey(KEY);
        reply(200,payload("{\"contentid\":\"123\"}"));
        assertThatThrownBy(()->client.fetch(TourApiClient.Operation.PET_BULK,"")).hasMessage("UNAVAILABLE");
    }

    private static byte[] bulkPayload(String rows,int total) {
        return ("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":"+total
                +",\"items\":{\"item\":"+rows+"}}}}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void givenHiddenSyncPage__whenFetch__thenPreserveMetadataAndExplicitPaging() {
        reply(200, ("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":101,"
                + "\"items\":{\"item\":[{\"contentid\":\"123\",\"showflag\":\"0\",\"lDongRegnCd\":\"36110\","
                + "\"modifiedtime\":\"20260912000000\"}]}}}}").getBytes(StandardCharsets.UTF_8));
        var page=client.fetchPage(TourApiClient.Operation.SYNC,"0",2);
        assertThat(page.totalCount()).isEqualTo(101);
        assertThat(page.items().get(0)).containsEntry("showflag","0").containsEntry("lDongRegnCd","36110")
                .containsEntry("modifiedtime","20260912000000");
        var request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(),any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().getPath()).endsWith("/petTourSyncList2");
        assertThat(request.getValue().uri().getRawQuery()).contains("pageNo=2","numOfRows=100","showflag=0");
    }

    @Test
    void givenInvalidPaging__whenFetch__thenNoProviderCall() {
        assertThatThrownBy(()->client.fetchPage(TourApiClient.Operation.SYNC,"1",0)).isInstanceOf(PetTravelException.class);
        assertThatThrownBy(()->client.fetchPage(TourApiClient.Operation.SYNC,"all",1)).isInstanceOf(PetTravelException.class);
        verifyNoInteractions(http);
    }

    @Test
    void givenEncodedKey__whenFetch__thenEncodeOnceAndSendOnlyToFixedHost() {
        properties.setServiceKey("syntheticOnly1234%2B%2F%3D");
        reply(200, payload("{\"code\":\"36110\",\"name\":\"서울\"}"));
        assertThat(client.fetch(TourApiClient.Operation.REGIONS, "")).hasSize(1);
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().getHost()).isEqualTo("apis.data.go.kr");
        assertThat(request.getValue().uri().getPath()).isEqualTo("/B551011/KorPetTourService2/ldongCode2");
        assertThat(request.getValue().uri().getRawQuery()).contains("serviceKey=syntheticOnly1234%2B%2F%3D");
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(5));
    }

    @Test
    void givenUnsafeOrEmptyKey__whenFetch__thenNoHttpRequest() {
        properties.setServiceKey("");
        assertThatThrownBy(() -> client.fetch(TourApiClient.Operation.REGIONS, "")).isInstanceOf(PetTravelException.class);
        verifyNoInteractions(http);
    }

    @Test
    void givenErrorOrRedirect__whenFetch__thenNoRetryAndSafeException() {
        for (int status : new int[]{302, 429, 500}) {
            reset(http);
            reply(status, KEY.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> client.fetch(TourApiClient.Operation.REGIONS, ""))
                    .isInstanceOf(PetTravelException.class).hasMessage("UNAVAILABLE").hasNoCause();
            verify(http).sendAsync(any(), any(HttpResponse.BodyHandler.class));
        }
    }

    @Test
    void givenExceptionContainingSecret__whenFetch__thenRemoveCause() {
        when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException(KEY)));
        assertThatThrownBy(() -> client.fetch(TourApiClient.Operation.REGIONS, ""))
                .hasMessage("UNAVAILABLE").hasNoCause();
    }

    @Test
    void givenTimeout__whenFetch__thenCancelPendingRequest() throws Exception {
        CompletableFuture<HttpResponse<byte[]>> future = mock(CompletableFuture.class);
        when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenReturn(future);
        when(future.get(6, TimeUnit.SECONDS)).thenThrow(new TimeoutException(KEY));
        assertThatThrownBy(() -> client.fetch(TourApiClient.Operation.REGIONS, "")).hasMessage("UNAVAILABLE");
        verify(future).cancel(true);
    }

    @Test
    void givenMalformedOrProviderError__whenParse__thenSafeFailure() {
        for (String body : List.of(KEY, "{}", "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"" + KEY + "\"}}}")) {
            assertThatThrownBy(() -> client.parse(body.getBytes(StandardCharsets.UTF_8), TourApiClient.Operation.REGIONS, KEY))
                    .hasMessage("UNAVAILABLE").hasNoCause();
        }
    }

    @Test
    void givenSinglePetObject__whenParse__thenAllowlistPlainTextAndRedactKey() {
        var data = client.parse(payload("{\"contentid\":\"123\",\"acmpyPsblCpam\":\"<b>안내견</b>\",\"etcAcmpyInfo\":\"" + KEY + "\",\"unknown\":\"private\"}"),
                TourApiClient.Operation.PET, KEY);
        assertThat(data.get(0)).containsEntry("acmpyPsblCpam", "안내견")
                .containsEntry("etcAcmpyInfo", "[REDACTED]").doesNotContainKey("unknown");
    }

    @Test
    void givenEmptyItems__whenParse__thenEmptyResult() {
        assertThat(client.parse(payload("[]"), TourApiClient.Operation.PLACES, KEY)).isEmpty();
        assertThat(client.parse("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":\"\"}}}".getBytes(StandardCharsets.UTF_8),
                TourApiClient.Operation.PET, KEY)).isEmpty();
    }

    @Test
    void givenOversizedBody__whenConsume__thenCancelBeforeAccumulating() {
        var subscriber = new TourApiClient.LimitedBody();
        var subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.allocate(TourApiClient.MAX_BYTES + 1)));
        verify(subscription).cancel();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join()).hasCauseInstanceOf(PetTravelException.class);
    }

    @Test
    void givenMoreThanRequestedRows__whenParse__thenRejectInsteadOfUnboundedList() {
        String row = "{\"contentid\":\"123\",\"title\":\"장소\"}";
        String rows = "[" + String.join(",", java.util.Collections.nCopies(11, row)) + "]";
        assertThatThrownBy(() -> client.parse(payload(rows), TourApiClient.Operation.PLACES, KEY)).hasMessage("UNAVAILABLE");
    }

    private void reply(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        lenient().when(response.body()).thenReturn(body);
        when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
    }

    @Test
    void givenRepresentativePhoto__whenParse__thenRetainUrlAndItsOwnCopyrightType() {
        var data = client.parse(payload("{\"contentid\":\"123\",\"title\":\"장소\","
                + "\"firstimage\":\"http://tong.visitkorea.or.kr/cms/resource/1/photo.jpg\",\"cpyrhtDivCd\":\"Type1\"}"),
                TourApiClient.Operation.PLACES, KEY);
        assertThat(data.get(0)).containsEntry("firstimage", "http://tong.visitkorea.or.kr/cms/resource/1/photo.jpg")
                .containsEntry("cpyrhtDivCd", "Type1");
    }

    private static byte[] payload(String rows) {
        return ("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":{\"item\":" + rows + "}}}}").getBytes(StandardCharsets.UTF_8);
    }

    private static TourApiProperties properties() {
        var properties = new TourApiProperties();
        properties.setEnabled(true);
        properties.setServiceKey(KEY);
        return properties;
    }

    @Test
    void givenWeightComparison__whenParse__thenPreserveRestriction() {
        var data = client.parse(payload("{\"contentid\":\"123\",\"acmpyPsblCpam\":\"<10kg, >3kg\"}"), TourApiClient.Operation.PET, KEY);
        assertThat(data.get(0).get("acmpyPsblCpam")).isEqualTo("<10kg, >3kg");
    }

    @Test
    void givenOversizedCondition__whenParse__thenRejectInsteadOfTruncating() {
        var body = payload("{\"contentid\":\"123\",\"acmpyNeedMtr\":\"" + "x".repeat(10001) + "\"}");
        assertThatThrownBy(() -> client.parse(body, TourApiClient.Operation.PET, KEY)).hasMessage("UNAVAILABLE");
    }
}
