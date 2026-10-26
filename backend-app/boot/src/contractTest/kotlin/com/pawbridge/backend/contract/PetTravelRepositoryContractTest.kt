package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import com.pawbridge.backend.animal.internal.travel.infrastructure.PostgresqlPetTravel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * The three public pet travel reads (regions, places of a region, one place) on the real tables. The adapter is built
 * from its public constructor with the application's reader pool, as the animal travel configuration does.
 */
class PetTravelRepositoryContractTest : AnimalRepositoryContractBase() {
    @Autowired
    private lateinit var mapper: ObjectMapper

    private lateinit var travel: PostgresqlPetTravel

    @BeforeEach
    fun build() {
        val reads = TransactionTemplate(manager).apply { isReadOnly = true }
        val repeatable = TransactionTemplate(manager, reads).apply { isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ }
        travel = PostgresqlPetTravel(JdbcTemplate(source), mapper, reads, repeatable)
    }

    private fun region(code: String, name: String, completed: Boolean) {
        execute(
            "INSERT INTO pawbridge_animal.pet_travel_regions(code, name, fetched_at, completed_at) VALUES (?, ?, '2026-03-01 00:00:00', ?::timestamp)",
            code, name, if (completed) "2026-03-02 00:00:00" else null,
        )
    }

    private fun target(
        id: Long,
        area: String? = "20",
        title: String = "장소 $id",
        type: String = "12",
        shown: Boolean = true,
        image: String? = null,
        copyright: String? = null,
        modified: String = "20260301000000",
    ) {
        execute(
            "INSERT INTO pawbridge_animal.pet_travel_targets(provider, content_id, area_code, modified_time, shown, image_url, copyright_type, " +
                "generation, pending, observed_at, basic_data, basic_fetched_at) VALUES ('KOREA_TOURISM_ORGANIZATION', ?, ?, ?, ?, ?, ?, 1, FALSE, " +
                "'2026-03-01 00:00:00', ?::jsonb, '2026-03-01 00:00:00')",
            id.toString(), area, modified, shown, image, copyright,
            """{"contentid":"$id","title":"$title","addr1":"주소 $id","contenttypeid":"$type"}""",
        )
    }

    private fun collectionError(code: String?) {
        execute("UPDATE pawbridge_animal.pet_travel_collection_state SET error_code = ?", code)
    }

    @Test
    fun `지역 코드가 섞여 있을 때__지역을 읽으면__숫자 순서로 주고 가장 오래된 수집 시각을 준다`() {
        // given
        region("31", "경기", true)
        region("2", "부산", true)
        region("11", "서울", true)
        execute("UPDATE pawbridge_animal.pet_travel_regions SET fetched_at = '2026-02-01 00:00:00' WHERE code = '11'")

        // when
        val regions = travel.regions()

        // then
        assertThat(regions.items.map { it.code }).isEqualTo(listOf("2", "11", "31"))
        assertThat(regions.availability).isEqualTo("READY")
        assertThat(regions.fetchedAt.toString()).isEqualTo("2026-02-01T00:00:00Z")
    }

    @Test
    fun `지역이 없을 때__읽으면__수집 오류가 없으면 준비 중이고 있으면 실패로 알린다`() {
        // given
        val preparing = travel.regions()
        collectionError("TOUR_API")

        // when
        val failed = travel.regions()

        // then
        assertThat(preparing.items).isEmpty()
        assertThat(preparing.availability).isEqualTo("PREPARING")
        assertThat(failed.availability).isEqualTo("FAILED")
    }

    @Test
    fun `표시 여부와 콘텐츠 유형이 다른 장소들이 있을 때__지역의 장소를 읽으면__보이는 장소만 제목 순으로 열 개씩 준다`() {
        // given
        region("20", "강원", completed = false)
        for (id in 1L..12L) {
            target(id, title = "장소 %02d".format(id), type = listOf("12", "14", "28")[(id % 3).toInt()])
        }
        target(90, shown = false, title = "숨김")
        target(91, type = "39", title = "식당")
        target(92, area = "30", title = "다른 지역")

        // when
        val first = travel.places("20", 0)
        val second = travel.places("20", 1)
        val beyond = travel.places("20", 5)

        // then
        assertThat(first.items.map { it.contentId }).isEqualTo((1L..10L).map { it.toString() })
        assertThat(first.totalElements).isEqualTo(12)
        assertThat(first.totalPages).isEqualTo(2)
        assertThat(first.availability).`as`("Rows exist but the region is not complete").isEqualTo("PARTIAL")
        assertThat(second.items.map { it.contentId }).isEqualTo(listOf("11", "12"))
        assertThat(beyond.items).isEmpty()
        assertThat(beyond.totalElements).isEqualTo(12)
    }

    @Test
    fun `지역 수집 상태가 다를 때__장소를 읽으면__준비 중 실패 부분 최신 오래됨을 구분한다`() {
        // given
        region("20", "강원", completed = false)
        region("30", "충남", completed = true)
        target(1, area = "30")

        // when
        val preparing = travel.places("20", 0)
        collectionError("COLLECTION_FAILED")
        val failed = travel.places("20", 0)
        val stale = travel.places("30", 0)
        collectionError(null)
        val ready = travel.places("30", 0)

        // then
        assertThat(listOf(preparing.availability, failed.availability, stale.availability, ready.availability))
            .isEqualTo(listOf("PREPARING", "FAILED", "STALE", "READY"))
    }

    @Test
    fun `잘못된 지역 코드나 없는 지역이나 음수 쪽일 때__장소를 읽으면__입력 오류이다`() {
        // given
        region("20", "강원", completed = true)

        // when
        // then
        for ((area, page) in listOf("2" to 0, "abc" to 0, "99" to 0, "20" to -1, null to 0)) {
            val failure = catchThrowableOf { travel.places(area, page) }
            assertThat((failure as PetTravelException).code).isEqualTo(PetTravelException.Code.INVALID_REQUEST)
        }
    }

    @Test
    fun `사진 권리가 다른 장소들이 있을 때__장소와 상세를 읽으면__허용된 공급자 사진만 https로 올려 주고 상세에 사진을 붙인다`() {
        // given
        region("20", "강원", completed = true)
        target(1, image = "http://tong.visitkorea.or.kr/cms/resource/20/MAIN.jpg", copyright = "Type1")
        target(2, image = "http://tong.visitkorea.or.kr/cms/resource/20/MAIN.jpg", copyright = "Type2")
        target(3, image = "http://evil.invalid/cms/resource/20/MAIN.jpg", copyright = "Type1")
        execute(
            "INSERT INTO pawbridge_animal.pet_travel_images(provider, content_id, serial_number, image_name, original_url, thumbnail_url, " +
                "copyright_type, display_order, fetched_at) VALUES ('KOREA_TOURISM_ORGANIZATION', '1', 's2', '둘째', " +
                "'http://tong.visitkorea.or.kr/cms/resource/1/two.jpg', NULL, 'Type1', 2, '2026-03-01 00:00:00'), " +
                "('KOREA_TOURISM_ORGANIZATION', '1', 's1', '첫째', 'http://tong.visitkorea.or.kr/cms/resource/1/one.jpg', NULL, 'Type3', 1, " +
                "'2026-03-01 00:00:00')",
        )

        // when
        val places = travel.places("20", 0)
        val detail = travel.detail("1")

        // then
        assertThat(places.items.associate { it.contentId to it.imageUrl })
            .isEqualTo(mapOf("1" to "https://tong.visitkorea.or.kr/cms/resource/20/MAIN.jpg", "2" to null, "3" to null))
        assertThat(detail.images.map { it.name }).isEqualTo(listOf("첫째", "둘째"))
        assertThat(detail.images.first().originalUrl).isEqualTo("https://tong.visitkorea.or.kr/cms/resource/1/one.jpg")
        assertThat(detail.place?.title).isEqualTo("장소 1")
    }

    @Test
    fun `반려동물 정보와 방문 정보가 있을 때__상세를 읽으면__최신 정보만 조건과 방문 정보로 준다`() {
        // given
        region("20", "강원", completed = true)
        target(1)
        execute("UPDATE pawbridge_animal.pet_travel_targets SET pet_valid_after = '2026-03-05 00:00:00'")
        execute(
            "INSERT INTO pawbridge_animal.pet_travel_pet_details(provider, content_id, pet_data, source, fetched_at) " +
                "VALUES ('KOREA_TOURISM_ORGANIZATION', '1', '{\"acmpyTypeCd\":\"실외\"}'::jsonb, 'DETAIL', '2026-03-01 00:00:00')",
        )
        val stale = travel.detail("1")
        execute("UPDATE pawbridge_animal.pet_travel_pet_details SET fetched_at = '2026-03-06 00:00:00'")
        execute(
            "INSERT INTO pawbridge_animal.pet_travel_visit_details(provider, content_id, content_type_id, intro_data, " +
                "intro_source_modified_time, intro_fetched_at, image_source_modified_time, image_fetched_at) " +
                "VALUES ('KOREA_TOURISM_ORGANIZATION', '1', '12', '{\"usetime\":\"09~18\"}'::jsonb, '20260301000000', '2026-03-06 00:00:00', " +
                "'20260301000000', '2026-03-06 00:00:00')",
        )

        // when
        val fresh = travel.detail("1")

        // then
        assertThat(stale.petInformationAvailable).`as`("A pet row older than pet_valid_after is ignored").isFalse()
        assertThat(fresh.petInformationAvailable).isTrue()
        assertThat(fresh.conditions?.areas).isEqualTo("실외")
        assertThat(fresh.visitInformation?.usageHours).isEqualTo("09~18")
        assertThat(fresh.imagesStatus).isEqualTo("READY")
    }

    @Test
    fun `없는 장소나 숨긴 장소이거나 형식이 틀린 번호일 때__상세를 읽으면__없음 또는 입력 오류이다`() {
        // given
        region("20", "강원", completed = true)
        target(1, shown = false)

        // when
        // then
        assertThat((catchThrowableOf { travel.detail("1") } as PetTravelException).code).isEqualTo(PetTravelException.Code.NOT_FOUND)
        assertThat((catchThrowableOf { travel.detail("999") } as PetTravelException).code).isEqualTo(PetTravelException.Code.NOT_FOUND)
        for (bad in listOf("abc", "", "1; DROP TABLE x", null)) {
            assertThat((catchThrowableOf { travel.detail(bad) } as PetTravelException).code).isEqualTo(PetTravelException.Code.INVALID_REQUEST)
        }
        assertThatThrownBy { travel.detail("1") }.isInstanceOf(PetTravelException::class.java)
    }

    private fun catchThrowableOf(action: () -> Unit): Throwable? = runCatching(action).exceptionOrNull()
}
