package com.pawbridge.backend.animal.internal.travel.infrastructure

import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import com.pawbridge.backend.animal.internal.travel.application.PetTravelResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.util.Optional

class PetTravelServiceTest {
    private val catalog: PostgresqlTravelCatalog = mock(PostgresqlTravelCatalog::class.java)
    private val service = PostgresqlPetTravel(
        catalog,
        TransactionOperations.withoutTransaction(),
        TransactionOperations.withoutTransaction(),
    )
    private val now: Instant = Instant.parse("2026-09-12T00:00:00Z")

    private fun row(): Map<String, String> {
        return mapOf("contentid" to "123", "title" to "공원", "addr1" to "서울")
    }

    private fun detail(common: Map<String, String>, pet: Map<String, String>) {
        `when`(catalog.detail("123")).thenReturn(Optional.of(PostgresqlTravelCatalog.Place(common, pet, now)))
    }

    @Test
    fun givenSecondPage__whenRead__thenReturnStoredPageAndTotal() {
        `when`(catalog.regions()).thenReturn(listOf(PostgresqlTravelCatalog.Region("11", "서울", now, now)))
        `when`(catalog.collectionState()).thenReturn(PostgresqlTravelCatalog.CollectionState("HIDDEN", 1, null, now))
        `when`(catalog.countPlaces("11")).thenReturn(21L)
        `when`(catalog.places("11", 1)).thenReturn(listOf(PostgresqlTravelCatalog.Place(row(), mapOf(), now)))

        val response = service.places("11", 1)

        assertThat(response.page).isEqualTo(1)
        assertThat(response.size).isEqualTo(10)
        assertThat(response.totalElements).isEqualTo(21)
        assertThat(response.totalPages).isEqualTo(3)
        assertThat(response.items.map { place -> place.contentId }).containsExactly("123")
    }

    @ParameterizedTest
    @ValueSource(ints = [3, Int.MAX_VALUE])
    fun givenPageBeyondInventory__whenRead__thenEmptyPageRetainsPartialState(page: Int) {
        `when`(catalog.regions()).thenReturn(listOf(PostgresqlTravelCatalog.Region("11", "서울", now, null)))
        `when`(catalog.collectionState()).thenReturn(PostgresqlTravelCatalog.CollectionState("SHOWN", 1, null, null))
        `when`(catalog.countPlaces("11")).thenReturn(21L)

        val response = service.places("11", page)

        assertThat(response.items).isEmpty()
        assertThat(response.totalElements).isEqualTo(21)
        assertThat(response.availability).isEqualTo("PARTIAL")
        verify(catalog, never()).places(anyString(), anyInt())
    }

    @Test
    fun givenNegativePage__whenRead__thenRejectBeforeDatabase() {
        assertThatThrownBy { service.places("11", -1) }
            .isInstanceOf(PetTravelException::class.java)
            .extracting("code").isEqualTo(PetTravelException.Code.INVALID_REQUEST)
        verifyNoInteractions(catalog)
    }

    @Test
    fun givenStoredData__whenRead__thenNoKeyOrRedisDependencies() {
        `when`(catalog.regions()).thenReturn(listOf(PostgresqlTravelCatalog.Region("11", "서울", now, now)))
        `when`(catalog.collectionState()).thenReturn(PostgresqlTravelCatalog.CollectionState("HIDDEN", 1, null, now))
        `when`(catalog.countPlaces("11")).thenReturn(1L)
        `when`(catalog.places("11", 0)).thenReturn(listOf(PostgresqlTravelCatalog.Place(row(), mapOf(), now)))

        assertThat(service.places("11").items).containsExactly(PetTravelResponse.Place("123", "공원", "서울", null))
        assertThat(service.places("11").availability).isEqualTo("READY")
        detail(row(), mapOf("acmpyPsblCpam" to "시각 장애인 안내견", "acmpyNeedMtr" to "목줄"))
        assertThat(service.detail("123").conditions!!.allowedAnimals).isEqualTo("시각 장애인 안내견")
        assertThat(service.detail("123").petInformationAvailable).isTrue()
    }

    @ParameterizedTest
    @CsvSource("false,false,PREPARING", "false,true,FAILED", "true,false,READY", "true,true,STALE")
    fun givenCollectionState__whenRead__thenDistinguishAvailability(completed: Boolean, failed: Boolean, expected: String) {
        `when`(catalog.regions())
            .thenReturn(listOf(PostgresqlTravelCatalog.Region("11", "서울", now, if (completed) now else null)))
        `when`(catalog.collectionState()).thenReturn(
            PostgresqlTravelCatalog.CollectionState(
                "DETAILS",
                1,
                if (failed) "COLLECTION_FAILED" else null,
                if (completed) now else null,
            ),
        )

        assertThat(service.places("11").availability).isEqualTo(expected)
    }

    @Test
    fun givenNoRegions__whenRead__thenEmptyPreparationResponse() {
        `when`(catalog.collectionState()).thenReturn(PostgresqlTravelCatalog.CollectionState("HIDDEN", 1, null, null))

        assertThat(service.regions().items).isEmpty()
        assertThat(service.regions().fetchedAt).isNull()
    }

    @Test
    fun givenInitialCollectionFailure__whenRead__thenFailedNotEmptyRegion() {
        `when`(catalog.collectionState())
            .thenReturn(PostgresqlTravelCatalog.CollectionState("HIDDEN", 1, "COLLECTION_FAILED", null))

        assertThat(service.regions().availability).isEqualTo("FAILED")
    }

    @Test
    fun givenUnknownRegion__whenRead__thenBadRequest() {
        assertThatThrownBy { service.places("99") }
            .isInstanceOf(PetTravelException::class.java)
            .extracting("code").isEqualTo(PetTravelException.Code.INVALID_REQUEST)
        verify(catalog, never()).places(anyString(), anyInt())
    }

    @Test
    fun givenMalformedId__whenRead__thenNoDatabaseWork() {
        assertThatThrownBy { service.detail("../x") }.isInstanceOf(PetTravelException::class.java)
        verifyNoInteractions(catalog)
    }

    @Test
    fun givenHiddenOrMissingPlace__whenRead__thenNotFound() {
        assertThatThrownBy { service.detail("123") }
            .isInstanceOf(PetTravelException::class.java)
            .extracting("code").isEqualTo(PetTravelException.Code.NOT_FOUND)
    }

    @Test
    fun givenEmptyPetInformation__whenRead__thenUnknownNotAllowed() {
        detail(row(), mapOf())

        assertThat(service.detail("123").petInformationAvailable).isFalse()
        assertThat(service.detail("123").conditions!!.allowedAnimals).isNull()
        assertThat(service.detail("123").petInformationStatus).isEqualTo("READY")
        assertThat(service.detail("123").petInformationFetchedAt).isEqualTo(now)
    }

    @Test
    fun givenBasicDataOnly__whenDetailRead__thenPendingWithoutInventedFetchTime() {
        `when`(catalog.detail("123")).thenReturn(
            Optional.of(PostgresqlTravelCatalog.Place(row(), mapOf(), null, now, "PREPARING")),
        )

        val response = service.detail("123")

        assertThat(response.place!!.title).isEqualTo("공원")
        assertThat(response.petInformationStatus).isEqualTo("PREPARING")
        assertThat(response.petInformationFetchedAt).isNull()
        assertThat(response.fetchedAt).isEqualTo(now)
        assertThat(response.petInformationAvailable).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["Type1", "Type3"])
    fun givenPermittedPhoto__whenRead__thenHttpsOriginal(type: String) {
        val data = HashMap(row())
        data["cpyrhtDivCd"] = type
        data["firstimage"] = "http://tong.visitkorea.or.kr/cms/resource/1/test.jpg"
        detail(data, mapOf())

        assertThat(service.detail("123").place!!.imageUrl)
            .isEqualTo("https://tong.visitkorea.or.kr/cms/resource/1/test.jpg")
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["Type2", "Type4", "unknown"])
    fun givenUnpermittedPhotoType__whenRead__thenOmitPhoto(type: String?) {
        val data = HashMap(row())
        if (type != null) {
            data["cpyrhtDivCd"] = type
        }
        data["firstimage"] = "https://tong.visitkorea.or.kr/cms/resource/1/test.jpg"
        detail(data, mapOf())

        assertThat(service.detail("123").place!!.imageUrl).isNull()
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = [
            "javascript:alert(1)",
            "https://example.com/a.jpg",
            "https://tong.visitkorea.or.kr.evil.example/cms/resource/1/a.jpg",
            "https://user@tong.visitkorea.or.kr/cms/resource/1/a.jpg",
            "https://tong.visitkorea.or.kr:8443/cms/resource/1/a.jpg",
            "https://tong.visitkorea.or.kr/cms/resource/1/a.jpg?token=x",
            "https://tong.visitkorea.or.kr/cms/resource/1/a.jpg#fragment",
            "https://tong.visitkorea.or.kr/cms/resource/../a.jpg",
        ],
    )
    fun givenUnsafePhoto__whenRead__thenOmitPhoto(image: String?) {
        val data = HashMap(row())
        data["cpyrhtDivCd"] = "Type1"
        if (image != null) {
            data["firstimage"] = image
        }
        detail(data, mapOf())

        assertThat(service.detail("123").place!!.imageUrl).isNull()
    }

    @Test
    fun givenVisitDetails__whenRead__thenNormalizeUsefulFieldsAndExposeOnlyPermittedPhotos() {
        val common = HashMap(row())
        common["contenttypeid"] = "12"
        val intro = mapOf(
            "contentid" to "123",
            "contenttypeid" to "12",
            "infocenter" to "02-123-4567",
            "usetime" to "09:00~18:00",
            "restdate" to "월요일",
            "parking" to "가능",
            "expguide" to "해설 프로그램",
            "useseason" to "연중",
        )
        val information = listOf(
            mapOf("contentid" to "123", "contenttypeid" to "12", "infoname" to "입장료", "infotext" to "무료"),
        )
        val images = listOf(
            PostgresqlTravelCatalog.Image(
                "1", "전경", "https://tong.visitkorea.or.kr/cms/resource/1/a.jpg",
                "https://tong.visitkorea.or.kr/cms/resource/1/a-small.jpg", "Type1", 0,
            ),
            PostgresqlTravelCatalog.Image(
                "2", "제외", "https://tong.visitkorea.or.kr/cms/resource/1/b.jpg", null, "Type2", 1,
            ),
        )
        `when`(catalog.detail("123")).thenReturn(
            Optional.of(
                PostgresqlTravelCatalog.Place(
                    common, mapOf(), now, now, "READY",
                    intro, information, images, now, now, now, "READY", "READY",
                ),
            ),
        )

        val response = service.detail("123")

        assertThat(response.visitInformation!!.informationCenter).isEqualTo("02-123-4567")
        assertThat(response.visitInformation!!.usageHours).isEqualTo("09:00~18:00")
        assertThat(response.visitInformation!!.experienceGuide).isEqualTo("해설 프로그램")
        assertThat(response.visitInformation!!.usageSeason).isEqualTo("연중")
        assertThat(response.visitInformation!!.additionalItems)
            .containsExactly(PetTravelResponse.InformationItem("입장료", "무료"))
        assertThat(response.images.map { image -> image.name }).containsExactly("전경")
        assertThat(response.imagesStatus).isEqualTo("READY")
    }

    @Test
    fun givenMalformedDetail__whenRead__thenRejectBeforeEitherReaderTransactionStarts() {
        val reads = mock(TransactionOperations::class.java)
        val lists = mock(TransactionOperations::class.java)
        val guarded = PostgresqlPetTravel(catalog, reads, lists)

        assertThatThrownBy { guarded.detail("../x") }
            .isInstanceOf(PetTravelException::class.java)
            .extracting("code").isEqualTo(PetTravelException.Code.INVALID_REQUEST)

        verifyNoInteractions(catalog, reads, lists)
    }
}
