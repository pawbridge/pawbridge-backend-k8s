package com.pawbridge.backend.animal.internal.travel.infrastructure.collection

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.lenient
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class TourApiClientTest {
    private val properties = properties()
    private val http: HttpClient = mock(HttpClient::class.java)
    private val client = TourApiClient(properties, ObjectMapper(), http)

    @Test
    fun givenBulkPetPage__whenFetch__thenUseSeparateKeyAndServiceWithoutContentId() {
        properties.isBulkPetEnabled = true
        properties.koreanServiceKey = "koreanSynthetic1234%2B%2F%3D"
        reply(200, bulkPayload("[{\"contentid\":\"123\",\"acmpyNeedMtr\":\"목줄\"}]", 101))

        val page = client.fetchPage(TourApiClient.Operation.PET_BULK, "", 2)

        assertThat(page.totalCount).isEqualTo(101)
        assertThat(page.items[0]).containsEntry("acmpyNeedMtr", "목줄")
        val request = ArgumentCaptor.forClass(HttpRequest::class.java)
        verify(http).sendAsync(request.capture(), any<HttpResponse.BodyHandler<ByteArray>>())
        assertThat(request.value.uri().path).isEqualTo("/B551011/KorService2/detailPetTour2")
        assertThat(request.value.uri().rawQuery)
            .contains("pageNo=2", "numOfRows=100", "serviceKey=koreanSynthetic1234%2B%2F%3D")
            .doesNotContain("contentId", "syntheticOnly")
    }

    @Test
    fun givenDisabledOrMissingKoreanKey__whenFetch__thenNeverFallbackToPetServiceKey() {
        assertThatThrownBy { client.fetch(TourApiClient.Operation.PET_BULK, "") }.hasMessage("UNAVAILABLE")
        properties.isBulkPetEnabled = true
        assertThatThrownBy { client.fetch(TourApiClient.Operation.PET_BULK, "") }.hasMessage("UNAVAILABLE")
        assertThatThrownBy { client.fetch(TourApiClient.Operation.COMMON, "123") }.hasMessage("UNAVAILABLE")
        verifyNoInteractions(http)
    }

    @Test
    fun givenDisplayDetail__whenFetch__thenUseKoreanServiceAndCredential() {
        properties.koreanServiceKey = "koreanSynthetic1234%2B%2F%3D"
        for (operation in listOf(TourApiClient.Operation.COMMON, TourApiClient.Operation.PET)) {
            reset(http)
            reply(200, payload("{\"contentid\":\"123\",\"title\":\"장소\"}"))

            assertThat(client.fetch(operation, "123")).hasSize(1)

            val request = ArgumentCaptor.forClass(HttpRequest::class.java)
            verify(http).sendAsync(request.capture(), any<HttpResponse.BodyHandler<ByteArray>>())
            assertThat(request.value.uri().path).isEqualTo("/B551011/KorService2/" + operation.path)
            assertThat(request.value.uri().rawQuery)
                .contains("contentId=123", "serviceKey=koreanSynthetic1234%2B%2F%3D")
                .doesNotContain("syntheticOnly")
        }
    }

    @Test
    fun givenVisitResources__whenFetch__thenUseContentTypeOnlyWhereRequiredAndRetainFields() {
        properties.koreanServiceKey = "koreanSynthetic1234%2B%2F%3D"
        reply(
            200,
            bulkPayload(
                "[{\"contentid\":\"123\",\"contenttypeid\":\"12\",\"usetime\":\"09:00~18:00\"," +
                    "\"expguide\":\"해설 프로그램\",\"useseason\":\"연중\"}]",
                1,
            ),
        )

        assertThat(client.fetchDetail(TourApiClient.Operation.INTRO, "123", "12")[0])
            .containsEntry("usetime", "09:00~18:00")
            .containsEntry("expguide", "해설 프로그램")
            .containsEntry("useseason", "연중")

        var request = ArgumentCaptor.forClass(HttpRequest::class.java)
        verify(http).sendAsync(request.capture(), any<HttpResponse.BodyHandler<ByteArray>>())
        assertThat(request.value.uri().path).endsWith("/detailIntro2")
        assertThat(request.value.uri().rawQuery).contains("contentId=123", "contentTypeId=12", "numOfRows=1")
        reset(http)
        reply(
            200,
            bulkPayload(
                "[{\"contentid\":\"123\",\"contenttypeid\":\"12\",\"serialnum\":\"1\",\"infoname\":\"안내\",\"infotext\":\"설명\"}]",
                1,
            ),
        )
        assertThat(client.fetchDetail(TourApiClient.Operation.INFO, "123", "12")[0]).containsEntry("infoname", "안내")
        reset(http)
        reply(
            200,
            bulkPayload(
                "[{\"contentid\":\"123\",\"serialnum\":\"1\",\"originimgurl\":\"https://tong.visitkorea.or.kr/cms/resource/1/a.jpg\",\"cpyrhtDivCd\":\"Type1\"}]",
                1,
            ),
        )
        assertThat(client.fetchDetail(TourApiClient.Operation.IMAGES, "123", "12")[0]).containsEntry("serialnum", "1")
        request = ArgumentCaptor.forClass(HttpRequest::class.java)
        verify(http).sendAsync(request.capture(), any<HttpResponse.BodyHandler<ByteArray>>())
        assertThat(request.value.uri().path).endsWith("/detailImage2")
        assertThat(request.value.uri().rawQuery).contains("contentId=123", "imageYN=Y", "numOfRows=100")
            .doesNotContain("contentTypeId")
    }

    @Test
    fun givenVisitResourceMoreThanOnePage__whenFetch__thenRejectPartialSnapshot() {
        properties.koreanServiceKey = KEY
        reply(
            200,
            bulkPayload("[{\"contentid\":\"123\",\"contenttypeid\":\"12\",\"serialnum\":\"1\",\"infoname\":\"안내\"}]", 101),
        )

        assertThatThrownBy { client.fetchDetail(TourApiClient.Operation.INFO, "123", "12") }
            .hasMessage("UNAVAILABLE")
    }

    @Test
    fun givenVisitResourceHasNoRows__whenFetch__thenAcceptSuccessfulEmptySnapshot() {
        properties.koreanServiceKey = KEY
        reply(
            200,
            (
                "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":0," +
                    "\"items\":\"\"}}}"
                ).toByteArray(StandardCharsets.UTF_8),
        )

        assertThat(client.fetchDetail(TourApiClient.Operation.INTRO, "123", "28")).isEmpty()
    }

    @Test
    fun givenDuplicateBulkIdentities__whenFetch__thenRejectPage() {
        properties.isBulkPetEnabled = true
        properties.bulkServiceKey = KEY
        reply(200, bulkPayload("[{\"contentid\":\"123\"},{\"contentid\":\"123\"}]", 2))

        assertThatThrownBy { client.fetch(TourApiClient.Operation.PET_BULK, "") }
            .hasMessage("UNAVAILABLE")
            .hasNoCause()
    }

    @Test
    fun givenBulkWithoutTotalCount__whenFetch__thenDoNotInferCompletionFromPageSize() {
        properties.isBulkPetEnabled = true
        properties.bulkServiceKey = KEY
        reply(200, payload("{\"contentid\":\"123\"}"))

        assertThatThrownBy { client.fetch(TourApiClient.Operation.PET_BULK, "") }.hasMessage("UNAVAILABLE")
    }

    @Test
    fun givenHiddenSyncPage__whenFetch__thenPreserveMetadataAndExplicitPaging() {
        reply(
            200,
            (
                "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":101," +
                    "\"items\":{\"item\":[{\"contentid\":\"123\",\"showflag\":\"0\",\"lDongRegnCd\":\"36110\"," +
                    "\"modifiedtime\":\"20260912000000\"}]}}}}"
                ).toByteArray(StandardCharsets.UTF_8),
        )

        val page = client.fetchPage(TourApiClient.Operation.SYNC, "0", 2)

        assertThat(page.totalCount).isEqualTo(101)
        assertThat(page.items[0]).containsEntry("showflag", "0").containsEntry("lDongRegnCd", "36110")
            .containsEntry("modifiedtime", "20260912000000")
        val request = ArgumentCaptor.forClass(HttpRequest::class.java)
        verify(http).sendAsync(request.capture(), any<HttpResponse.BodyHandler<ByteArray>>())
        assertThat(request.value.uri().path).endsWith("/petTourSyncList2")
        assertThat(request.value.uri().rawQuery).contains("pageNo=2", "numOfRows=100", "showflag=0")
    }

    @Test
    fun givenInvalidPaging__whenFetch__thenNoProviderCall() {
        assertThatThrownBy { client.fetchPage(TourApiClient.Operation.SYNC, "1", 0) }
            .isInstanceOf(PetTravelException::class.java)
        assertThatThrownBy { client.fetchPage(TourApiClient.Operation.SYNC, "all", 1) }
            .isInstanceOf(PetTravelException::class.java)
        verifyNoInteractions(http)
    }

    @Test
    fun givenEncodedKey__whenFetch__thenEncodeOnceAndSendOnlyToFixedHost() {
        properties.serviceKey = "syntheticOnly1234%2B%2F%3D"
        reply(200, payload("{\"code\":\"36110\",\"name\":\"서울\"}"))

        assertThat(client.fetch(TourApiClient.Operation.REGIONS, "")).hasSize(1)

        val request = ArgumentCaptor.forClass(HttpRequest::class.java)
        verify(http).sendAsync(request.capture(), any<HttpResponse.BodyHandler<ByteArray>>())
        assertThat(request.value.uri().host).isEqualTo("apis.data.go.kr")
        assertThat(request.value.uri().path).isEqualTo("/B551011/KorPetTourService2/ldongCode2")
        assertThat(request.value.uri().rawQuery).contains("serviceKey=syntheticOnly1234%2B%2F%3D")
        assertThat(request.value.timeout()).contains(Duration.ofSeconds(5))
    }

    @Test
    fun givenUnsafeOrEmptyKey__whenFetch__thenNoHttpRequest() {
        properties.serviceKey = ""

        assertThatThrownBy { client.fetch(TourApiClient.Operation.REGIONS, "") }
            .isInstanceOf(PetTravelException::class.java)

        verifyNoInteractions(http)
    }

    @Test
    fun givenErrorOrRedirect__whenFetch__thenNoRetryAndSafeException() {
        for (status in intArrayOf(302, 429, 500)) {
            reset(http)
            reply(status, KEY.toByteArray(StandardCharsets.UTF_8))

            assertThatThrownBy { client.fetch(TourApiClient.Operation.REGIONS, "") }
                .isInstanceOf(PetTravelException::class.java).hasMessage("UNAVAILABLE").hasNoCause()

            verify(http).sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())
        }
    }

    @Test
    fun givenExceptionContainingSecret__whenFetch__thenRemoveCause() {
        `when`(http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()))
            .thenReturn(CompletableFuture.failedFuture<HttpResponse<ByteArray>>(IllegalStateException(KEY)))

        assertThatThrownBy { client.fetch(TourApiClient.Operation.REGIONS, "") }
            .hasMessage("UNAVAILABLE").hasNoCause()
    }

    @Test
    fun givenTimeout__whenFetch__thenCancelPendingRequest() {
        @Suppress("UNCHECKED_CAST")
        val future = mock(CompletableFuture::class.java) as CompletableFuture<HttpResponse<ByteArray>>
        `when`(http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>())).thenReturn(future)
        `when`(future.get(6, TimeUnit.SECONDS)).thenThrow(TimeoutException(KEY))

        assertThatThrownBy { client.fetch(TourApiClient.Operation.REGIONS, "") }.hasMessage("UNAVAILABLE")

        verify(future).cancel(true)
    }

    @Test
    fun givenMalformedOrProviderError__whenParse__thenSafeFailure() {
        for (
            body in listOf(
                KEY,
                "{}",
                "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"" + KEY + "\"}}}",
            )
        ) {
            assertThatThrownBy {
                client.parse(body.toByteArray(StandardCharsets.UTF_8), TourApiClient.Operation.REGIONS, KEY)
            }.hasMessage("UNAVAILABLE").hasNoCause()
        }
    }

    @Test
    fun givenSinglePetObject__whenParse__thenAllowlistPlainTextAndRedactKey() {
        val data = client.parse(
            payload(
                "{\"contentid\":\"123\",\"acmpyPsblCpam\":\"<b>안내견</b>\",\"etcAcmpyInfo\":\"" + KEY +
                    "\",\"unknown\":\"private\"}",
            ),
            TourApiClient.Operation.PET,
            KEY,
        )

        assertThat(data[0]).containsEntry("acmpyPsblCpam", "안내견")
            .containsEntry("etcAcmpyInfo", "[REDACTED]").doesNotContainKey("unknown")
    }

    @Test
    fun givenEmptyItems__whenParse__thenEmptyResult() {
        assertThat(client.parse(payload("[]"), TourApiClient.Operation.PLACES, KEY)).isEmpty()
        assertThat(
            client.parse(
                "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":\"\"}}}"
                    .toByteArray(StandardCharsets.UTF_8),
                TourApiClient.Operation.PET,
                KEY,
            ),
        ).isEmpty()
    }

    @Test
    fun givenOversizedBody__whenConsume__thenCancelBeforeAccumulating() {
        val subscriber = TourApiClient.LimitedBody()
        val subscription = mock(Flow.Subscription::class.java)

        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.allocate(TourApiClient.MAX_BYTES + 1)))

        verify(subscription).cancel()
        assertThatThrownBy { subscriber.getBody().toCompletableFuture().join() }
            .hasCauseInstanceOf(PetTravelException::class.java)
    }

    @Test
    fun givenMoreThanRequestedRows__whenParse__thenRejectInsteadOfUnboundedList() {
        val row = "{\"contentid\":\"123\",\"title\":\"장소\"}"
        val rows = "[" + Collections.nCopies(11, row).joinToString(",") + "]"

        assertThatThrownBy { client.parse(payload(rows), TourApiClient.Operation.PLACES, KEY) }
            .hasMessage("UNAVAILABLE")
    }

    @Test
    fun givenRepresentativePhoto__whenParse__thenRetainUrlAndItsOwnCopyrightType() {
        val data = client.parse(
            payload(
                "{\"contentid\":\"123\",\"title\":\"장소\"," +
                    "\"firstimage\":\"http://tong.visitkorea.or.kr/cms/resource/1/photo.jpg\",\"cpyrhtDivCd\":\"Type1\"}",
            ),
            TourApiClient.Operation.PLACES,
            KEY,
        )

        assertThat(data[0]).containsEntry("firstimage", "http://tong.visitkorea.or.kr/cms/resource/1/photo.jpg")
            .containsEntry("cpyrhtDivCd", "Type1")
    }

    @Test
    fun givenWeightComparison__whenParse__thenPreserveRestriction() {
        val data = client.parse(
            payload("{\"contentid\":\"123\",\"acmpyPsblCpam\":\"<10kg, >3kg\"}"),
            TourApiClient.Operation.PET,
            KEY,
        )

        assertThat(data[0]["acmpyPsblCpam"]).isEqualTo("<10kg, >3kg")
    }

    @Test
    fun givenOversizedCondition__whenParse__thenRejectInsteadOfTruncating() {
        val body = payload("{\"contentid\":\"123\",\"acmpyNeedMtr\":\"" + "x".repeat(10001) + "\"}")

        assertThatThrownBy { client.parse(body, TourApiClient.Operation.PET, KEY) }.hasMessage("UNAVAILABLE")
    }

    @Suppress("UNCHECKED_CAST")
    private fun reply(status: Int, body: ByteArray) {
        val response = mock(HttpResponse::class.java) as HttpResponse<ByteArray>
        `when`(response.statusCode()).thenReturn(status)
        lenient().`when`(response.body()).thenReturn(body)
        `when`(http.sendAsync(any(), any<HttpResponse.BodyHandler<ByteArray>>()))
            .thenReturn(CompletableFuture.completedFuture(response))
    }

    companion object {
        private const val KEY = "syntheticOnly1234+/="

        private fun bulkPayload(rows: String, total: Int): ByteArray {
            return (
                "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":" + total +
                    ",\"items\":{\"item\":" + rows + "}}}}"
                ).toByteArray(StandardCharsets.UTF_8)
        }

        private fun payload(rows: String): ByteArray {
            return (
                "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":{\"item\":" + rows + "}}}}"
                ).toByteArray(StandardCharsets.UTF_8)
        }

        private fun properties(): TourApiProperties {
            val properties = TourApiProperties()
            properties.isEnabled = true
            properties.serviceKey = KEY
            return properties
        }
    }
}
