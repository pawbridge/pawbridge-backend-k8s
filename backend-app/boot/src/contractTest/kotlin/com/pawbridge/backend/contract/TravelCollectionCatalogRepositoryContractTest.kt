package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.travel.infrastructure.collection.PostgresqlTravelCollectionCatalog
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The pet travel collection store on the real tables: the request budget, run bookkeeping, region inventory, discovery
 * observations with their generation checks, and publication. Ported from the former `OwnedPostgresqlTravelCollectionTest`.
 */
class TravelCollectionCatalogRepositoryContractTest : AnimalRepositoryContractBase() {
    @Autowired
    private lateinit var mapper: ObjectMapper

    private val now = Instant.parse("2026-03-10T03:00:00Z")
    private lateinit var catalog: PostgresqlTravelCollectionCatalog

    @BeforeEach
    fun build() {
        catalog = PostgresqlTravelCollectionCatalog(JdbcTemplate(writeSource), mapper, writeManager)
    }

    private fun target(id: String): String? = scalar(
        "SELECT generation || '|' || pending || '|' || shown || '|' || modified_time || '|' || coalesce(area_code, '-') " +
            "FROM pawbridge_animal.pet_travel_targets WHERE content_id = ?",
        id,
    )

    @Test
    fun `하루 한도가 2인 작업일 때__요청을 예약하면__두 번까지 허용하고 다른 날짜와 다른 작업은 따로 센다`() {
        // given
        val day = LocalDate.parse("2026-03-10")

        // when
        val results = listOf(
            catalog.reserveRequest("LIST", day, 2), catalog.reserveRequest("LIST", day, 2), catalog.reserveRequest("LIST", day, 2),
            catalog.reserveRequest("LIST", day.plusDays(1), 2), catalog.reserveRequest("DETAIL", day, 2),
        )

        // then
        assertThat(results).isEqualTo(listOf(true, true, false, true, true))
        assertThat(count("SELECT used FROM pawbridge_animal.pet_travel_request_budgets WHERE operation = 'LIST' AND request_day = ?::date", day.toString())).isEqualTo(2)
    }

    @Test
    fun `실행 중인 수집이 없을 때__실행을 시작하고 끝내면__상태를 남기고 이미 실행 중이면 고아 예외다`() {
        // given
        catalog.startRun("run-1", now)

        // when
        assertThatThrownBy { catalog.startRun("run-2", now) }.hasMessage("TRAVEL_ORPHAN_RUN")
        catalog.finishRun("run-1", "FAILED", "TOUR_API", 5, 4, 3, now.plusSeconds(60))
        catalog.startRun("run-2", now.plusSeconds(120))

        // then
        assertThat(scalar("SELECT status || '|' || error_code || '|' || requests || '|' || discovered || '|' || published FROM pawbridge_animal.pet_travel_collection_runs WHERE id = 'run-1'"))
            .isEqualTo("FAILED|TOUR_API|5|4|3")
        assertThat(scalar("SELECT error_code FROM pawbridge_animal.pet_travel_collection_state")).isEqualTo("TOUR_API")
        assertThat(scalar("SELECT status FROM pawbridge_animal.pet_travel_collection_runs WHERE id = 'run-2'")).isEqualTo("RUNNING")
    }

    @Test
    fun `지역 코드가 섞여 있을 때__지역을 저장하면__이름을 갱신하고 숫자 순서로 읽으며 잘못된 코드는 거부하고 주기 완료가 완료 시각을 쓴다`() {
        // given
        catalog.saveRegions(mapOf("11" to "서울", "2" .padStart(2, '0') to "부산", "31" to "경기"), now)

        // when
        catalog.saveRegions(mapOf("11" to "서울특별시"), now.plusSeconds(1))
        catalog.checkpoint("DETAILS", 7)
        catalog.completeCycle(now.plusSeconds(10))

        // then
        assertThat(catalog.regions().map { it.code to it.name }).isEqualTo(listOf("02" to "부산", "11" to "서울특별시", "31" to "경기"))
        assertThat(catalog.regions().all { it.completedAt != null }).isTrue()
        assertThat(catalog.collectionState().let { it.phase to it.nextPage }).isEqualTo("HIDDEN" to 1)
        assertThat(catalog.collectionState().completedAt).isNotNull()
        assertThatThrownBy { catalog.saveRegions(mapOf("x" to "잘못"), now) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { catalog.saveRegions(mapOf("11" to " "), now) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `같은 장소를 다시 관찰할 때__관찰하면__수정 시각이 바뀌면 세대를 올려 대기로 두고 더 오래된 수정 시각은 무시한다`() {
        // given
        catalog.observe("100", "11", "20260301000000", true, "https://fixture.invalid/a.jpg", "Type1", now, false)
        val first = target("100")

        // when
        catalog.observe("100", "11", "20260301000000", true, null, null, now.plusSeconds(1), false)
        val same = target("100")
        catalog.observe("100", "11", "20260201000000", true, null, null, now.plusSeconds(2), false)
        val older = target("100")
        catalog.observe("100", "31", "20260305000000", true, null, null, now.plusSeconds(3), false)
        val moved = target("100")

        // then
        assertThat(first).isEqualTo("1|true|true|20260301000000|11")
        assertThat(same).`as`("Every accepted observation raises the generation").isEqualTo("2|true|true|20260301000000|11")
        assertThat(older).`as`("An older revision is ignored").isEqualTo(same)
        assertThat(moved).isEqualTo("3|true|true|20260305000000|31")
        assertThat(scalar("SELECT image_url FROM pawbridge_animal.pet_travel_targets WHERE content_id = '100'")).isNull()
        assertThatThrownBy { catalog.observe("x", "11", "20260301000000", true, null, null, now, false) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { catalog.observe("101", "11", "bad", true, null, null, now, false) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `대기 중인 장소들일 때__대기 목록을 읽으면__보이고 지역이 있는 것만 시도 안 한 순서와 관찰 순서로 주고 한도를 검사한다`() {
        // given
        catalog.observe("100", "11", "20260301000000", true, null, null, now.plusSeconds(3), false)
        catalog.observe("101", "11", "20260301000000", true, null, null, now.plusSeconds(1), false)
        catalog.observe("102", null, "20260301000000", true, null, null, now, false)
        catalog.observe("103", "11", "20260301000000", false, null, null, now, false)
        catalog.observe("104", "11", "20260301000000", true, null, null, now.plusSeconds(2), false)
        catalog.detailFailed(catalog.pending(10).first { it.contentId == "101" }, now.plusSeconds(10))

        // when
        val pending = catalog.pending(10)

        // then
        assertThat(pending.map { it.contentId }).`as`("Failed attempts go last").isEqualTo(listOf("104", "100", "101"))
        assertThatThrownBy { catalog.pending(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { catalog.pending(101) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `관찰 뒤 세대가 그대로일 때__게시하면__장소와 반려 정보를 쓰고 대기를 끄며 그 사이 다시 관찰되었으면 거절한다`() {
        // given
        catalog.observe("100", "11", "20260301000000", true, "https://fixture.invalid/a.jpg", "Type1", now, false)
        val expected = catalog.pending(10).single()
        val common = mapOf("contentid" to "100", "title" to "합성 장소", "contenttypeid" to "12")

        // when
        catalog.observe("100", "11", "20260302000000", true, null, null, now.plusSeconds(1), false)
        val stale = catalog.publish(expected, common, mapOf("contentid" to "100", "acmpyTypeCd" to "가능"), now.plusSeconds(2))
        val fresh = catalog.publish(catalog.pending(10).single(), common, mapOf("contentid" to "100", "acmpyTypeCd" to "가능"), now.plusSeconds(3))
        val again = catalog.publish(catalog.pending(10).firstOrNull() ?: expected, common, emptyMap(), now.plusSeconds(4))

        // then
        assertThat(stale).isFalse()
        assertThat(fresh).isTrue()
        assertThat(again).`as`("Nothing is pending any more").isFalse()
        assertThat(scalar("SELECT title || '|' || visible FROM pawbridge_animal.pet_travel_places WHERE content_id = '100'")).isEqualTo("합성 장소|true")
        assertThat(scalar("SELECT pet_data->>'acmpyTypeCd' FROM pawbridge_animal.pet_travel_pet_details WHERE content_id = '100'")).isEqualTo("가능")
        assertThat(scalar("SELECT pending::text FROM pawbridge_animal.pet_travel_targets WHERE content_id = '100'")).isEqualTo("false")
        assertThatThrownBy { catalog.publish(expected, mapOf("contentid" to "999", "title" to "x"), emptyMap(), now) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `게시된 장소가 숨김으로 관찰될 때__관찰하면__공개 행을 숨기고 다시 보이게 되어도 게시 전에는 숨긴 채 둔다`() {
        // given
        catalog.observe("100", "11", "20260301000000", true, null, null, now, false)
        catalog.publish(catalog.pending(10).single(), mapOf("contentid" to "100", "title" to "합성 장소"), emptyMap(), now.plusSeconds(1))

        // when
        catalog.observe("100", "11", "20260301000000", false, null, null, now.plusSeconds(2), false)
        val hidden = scalar("SELECT visible::text FROM pawbridge_animal.pet_travel_places WHERE content_id = '100'")
        catalog.observe("100", "11", "20260301000000", true, null, null, now.plusSeconds(3), false)
        val reappeared = scalar("SELECT visible::text FROM pawbridge_animal.pet_travel_places WHERE content_id = '100'")
        catalog.publish(catalog.pending(10).single(), mapOf("contentid" to "100", "title" to "합성 장소"), emptyMap(), now.plusSeconds(4))

        // then
        assertThat(hidden).isEqualTo("false")
        assertThat(reappeared).isEqualTo("false")
        assertThat(scalar("SELECT visible::text FROM pawbridge_animal.pet_travel_places WHERE content_id = '100'")).isEqualTo("true")
    }

    @Test
    fun `기본 정보 행일 때__기본 관찰을 저장하면__표시 장소는 제목이 필요하고 기본 데이터는 같은 수정 시각에만 쓴다`() {
        // given
        val row = mapOf("contentid" to "100", "title" to "기본 제목", "showflag" to "1", "modifiedtime" to "20260301000000", "contenttypeid" to "12")

        // when
        catalog.observeBasic(row, "11", now)
        val stored = scalar("SELECT basic_data->>'title' FROM pawbridge_animal.pet_travel_targets WHERE content_id = '100'")
        catalog.observeBasic(row - "title" + ("showflag" to "0"), "11", now.plusSeconds(1))

        // then
        assertThat(stored).isEqualTo("기본 제목")
        assertThat(target("100")).startsWith("2|false|false")
        assertThatThrownBy { catalog.observeBasic(row + ("title" to " "), "11", now) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
