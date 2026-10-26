package com.pawbridge.backend.animal.internal.travel.infrastructure

import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import com.pawbridge.backend.animal.internal.travel.application.PetTravelResponse
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.transaction.support.TransactionOperations
import java.time.Instant
import java.util.Optional

class PetTravelServiceTest {
    private val catalog: PostgresqlTravelCatalog = mockk<PostgresqlTravelCatalog>()
    private val service = PostgresqlPetTravel(
        catalog,
        TransactionOperations.withoutTransaction(),
        TransactionOperations.withoutTransaction(),
    )
    private val now: Instant = Instant.parse("2026-09-12T00:00:00Z")

    private fun row(): Map<String, String> {
        return mapOf("contentid" to "123", "title" to "공원", "addr1" to "서울")
    }

    @Test
    fun `두 번째 페이지를 읽을 때__조회하면__저장된 페이지와 전체 건수를 돌려준다`() {
        // given
        every { catalog.regions() } returns listOf(PostgresqlTravelCatalog.Region("11", "서울", now, now))
        every { catalog.collectionState() } returns PostgresqlTravelCatalog.CollectionState("HIDDEN", 1, null, now)
        every { catalog.countPlaces(any()) } returns 21L
        every { catalog.places(any(), any()) } returns listOf(PostgresqlTravelCatalog.Place(row(), mapOf(), now))

        // when
        val response = service.places("11", 1)

        // then
        assertThat(response.page).isEqualTo(1)
        assertThat(response.size).isEqualTo(10)
        assertThat(response.totalElements).isEqualTo(21)
        assertThat(response.totalPages).isEqualTo(3)
        assertThat(response.items.map { place -> place.contentId }).containsExactly("123")
        verify(exactly = 1) { catalog.countPlaces("11") }
        verify(exactly = 1) { catalog.places("11", 1) }
    }

    @ParameterizedTest
    @ValueSource(ints = [3, Int.MAX_VALUE])
    fun `재고 범위를 넘는 페이지일 때__조회하면__빈 페이지가 부분 수집 상태를 유지한다`(page: Int) {
        // given
        every { catalog.regions() } returns listOf(PostgresqlTravelCatalog.Region("11", "서울", now, null))
        every { catalog.collectionState() } returns PostgresqlTravelCatalog.CollectionState("SHOWN", 1, null, null)
        every { catalog.countPlaces(any()) } returns 21L

        // when
        val response = service.places("11", page)

        // then
        assertThat(response.items).isEmpty()
        assertThat(response.totalElements).isEqualTo(21)
        assertThat(response.availability).isEqualTo("PARTIAL")
        verify(exactly = 0) { catalog.places(any(), any()) }
    }

    @Test
    fun `음수 페이지일 때__조회하면__데이터베이스 전에 거부한다`() {
        // given

        // when
        val failure = catchThrowable { service.places("11", -1) }

        // then
        assertThat(failure)
            .isInstanceOf(PetTravelException::class.java)
            .extracting("code").isEqualTo(PetTravelException.Code.INVALID_REQUEST)
        confirmVerified(catalog)
    }

    @Test
    fun `저장된 데이터가 있을 때__조회하면__키나 Redis 의존 없이 목록과 상세를 돌려준다`() {
        // given
        every { catalog.regions() } returns listOf(PostgresqlTravelCatalog.Region("11", "서울", now, now))
        every { catalog.collectionState() } returns PostgresqlTravelCatalog.CollectionState("HIDDEN", 1, null, now)
        every { catalog.countPlaces(any()) } returns 1L
        every { catalog.places(any(), any()) } returns listOf(PostgresqlTravelCatalog.Place(row(), mapOf(), now))
        every { catalog.detail(any()) } returns Optional.of(
            PostgresqlTravelCatalog.Place(
                row(),
                mapOf("acmpyPsblCpam" to "시각 장애인 안내견", "acmpyNeedMtr" to "목줄"),
                now,
            ),
        )

        // when
        val places = service.places("11")
        val detail = service.detail("123")

        // then
        assertThat(places.items).containsExactly(PetTravelResponse.Place("123", "공원", "서울", null))
        assertThat(places.availability).isEqualTo("READY")
        assertThat(detail.conditions!!.allowedAnimals).isEqualTo("시각 장애인 안내견")
        assertThat(detail.petInformationAvailable).isTrue()
        verify(exactly = 1) { catalog.places("11", 0) }
        verify(exactly = 1) { catalog.detail("123") }
    }

    @ParameterizedTest
    @CsvSource("false,false,PREPARING", "false,true,FAILED", "true,false,READY", "true,true,STALE")
    fun `수집 상태가 주어졌을 때__조회하면__가용성을 구분한다`(completed: Boolean, failed: Boolean, expected: String) {
        // given
        every { catalog.regions() } returns
            listOf(PostgresqlTravelCatalog.Region("11", "서울", now, if (completed) now else null))
        every { catalog.collectionState() } returns PostgresqlTravelCatalog.CollectionState(
            "DETAILS",
            1,
            if (failed) "COLLECTION_FAILED" else null,
            if (completed) now else null,
        )
        every { catalog.countPlaces(any()) } returns 0L

        // when
        val response = service.places("11")

        // then
        assertThat(response.availability).isEqualTo(expected)
    }

    @Test
    fun `지역이 없을 때__조회하면__빈 준비 중 응답을 돌려준다`() {
        // given
        every { catalog.regions() } returns listOf()
        every { catalog.collectionState() } returns PostgresqlTravelCatalog.CollectionState("HIDDEN", 1, null, null)

        // when
        val response = service.regions()

        // then
        assertThat(response.items).isEmpty()
        assertThat(response.fetchedAt).isNull()
    }

    @Test
    fun `최초 수집이 실패했을 때__조회하면__빈 지역이 아니라 실패로 알린다`() {
        // given
        every { catalog.regions() } returns listOf()
        every { catalog.collectionState() } returns
            PostgresqlTravelCatalog.CollectionState("HIDDEN", 1, "COLLECTION_FAILED", null)

        // when
        val response = service.regions()

        // then
        assertThat(response.availability).isEqualTo("FAILED")
    }

    @Test
    fun `알 수 없는 지역일 때__조회하면__잘못된 요청으로 거부한다`() {
        // given
        every { catalog.regions() } returns listOf()

        // when
        val failure = catchThrowable { service.places("99") }

        // then
        assertThat(failure)
            .isInstanceOf(PetTravelException::class.java)
            .extracting("code").isEqualTo(PetTravelException.Code.INVALID_REQUEST)
        verify(exactly = 0) { catalog.places(any(), any()) }
    }

    @Test
    fun `형식이 잘못된 ID일 때__조회하면__데이터베이스 작업을 하지 않는다`() {
        // given

        // when
        val failure = catchThrowable { service.detail("../x") }

        // then
        assertThat(failure).isInstanceOf(PetTravelException::class.java)
        confirmVerified(catalog)
    }

    @Test
    fun `숨겨졌거나 없는 장소일 때__조회하면__찾을 수 없음으로 처리한다`() {
        // given
        every { catalog.detail(any()) } returns Optional.empty()

        // when
        val failure = catchThrowable { service.detail("123") }

        // then
        assertThat(failure)
            .isInstanceOf(PetTravelException::class.java)
            .extracting("code").isEqualTo(PetTravelException.Code.NOT_FOUND)
    }

    @Test
    fun `반려동물 정보가 비어 있을 때__조회하면__허용이 아니라 알 수 없음으로 둔다`() {
        // given
        every { catalog.detail(any()) } returns Optional.of(PostgresqlTravelCatalog.Place(row(), mapOf(), now))

        // when
        val response = service.detail("123")

        // then
        assertThat(response.petInformationAvailable).isFalse()
        assertThat(response.conditions!!.allowedAnimals).isNull()
        assertThat(response.petInformationStatus).isEqualTo("READY")
        assertThat(response.petInformationFetchedAt).isEqualTo(now)
    }

    @Test
    fun `기본 데이터만 있을 때__상세를 조회하면__가짜 조회 시각 없이 준비 중으로 둔다`() {
        // given
        every { catalog.detail(any()) } returns
            Optional.of(PostgresqlTravelCatalog.Place(row(), mapOf(), null, now, "PREPARING"))

        // when
        val response = service.detail("123")

        // then
        assertThat(response.place!!.title).isEqualTo("공원")
        assertThat(response.petInformationStatus).isEqualTo("PREPARING")
        assertThat(response.petInformationFetchedAt).isNull()
        assertThat(response.fetchedAt).isEqualTo(now)
        assertThat(response.petInformationAvailable).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["Type1", "Type3"])
    fun `허용된 사진 유형일 때__조회하면__HTTPS 원본 주소를 돌려준다`(type: String) {
        // given
        val data = HashMap(row())
        data["cpyrhtDivCd"] = type
        data["firstimage"] = "http://tong.visitkorea.or.kr/cms/resource/1/test.jpg"

        every { catalog.detail(any()) } returns Optional.of(PostgresqlTravelCatalog.Place(data, mapOf(), now))

        // when
        val response = service.detail("123")

        // then
        assertThat(response.place!!.imageUrl)
            .isEqualTo("https://tong.visitkorea.or.kr/cms/resource/1/test.jpg")
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["Type2", "Type4", "unknown"])
    fun `허용되지 않은 사진 유형일 때__조회하면__사진을 생략한다`(type: String?) {
        // given
        val data = HashMap(row())
        if (type != null) {
            data["cpyrhtDivCd"] = type
        }
        data["firstimage"] = "https://tong.visitkorea.or.kr/cms/resource/1/test.jpg"

        every { catalog.detail(any()) } returns Optional.of(PostgresqlTravelCatalog.Place(data, mapOf(), now))

        // when
        val response = service.detail("123")

        // then
        assertThat(response.place!!.imageUrl).isNull()
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
    fun `안전하지 않은 사진 주소일 때__조회하면__사진을 생략한다`(image: String?) {
        // given
        val data = HashMap(row())
        data["cpyrhtDivCd"] = "Type1"
        if (image != null) {
            data["firstimage"] = image
        }

        every { catalog.detail(any()) } returns Optional.of(PostgresqlTravelCatalog.Place(data, mapOf(), now))

        // when
        val response = service.detail("123")

        // then
        assertThat(response.place!!.imageUrl).isNull()
    }

    @Test
    fun `방문 상세가 있을 때__조회하면__유용한 필드를 정규화하고 허용된 사진만 노출한다`() {
        // given
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

        every { catalog.detail(any()) } returns Optional.of(
            PostgresqlTravelCatalog.Place(
                common, mapOf(), now, now, "READY",
                intro, information, images, now, now, now, "READY", "READY",
            ),
        )

        // when
        val response = service.detail("123")

        // then
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
    fun `형식이 잘못된 상세 ID일 때__조회하면__어느 읽기 트랜잭션도 시작하기 전에 거부한다`() {
        // given
        val reads = mockk<TransactionOperations>()
        val lists = mockk<TransactionOperations>()
        val guarded = PostgresqlPetTravel(catalog, reads, lists)

        // when
        val failure = catchThrowable { guarded.detail("../x") }

        // then
        assertThat(failure)
            .isInstanceOf(PetTravelException::class.java)
            .extracting("code").isEqualTo(PetTravelException.Code.INVALID_REQUEST)
        confirmVerified(catalog, reads, lists)
    }
}
