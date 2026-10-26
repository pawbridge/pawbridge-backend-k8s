package com.pawbridge.backend.animal.internal.travel.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

class PostgresqlTravelCatalogTest {
    private val jdbc: JdbcOperations = mockk<JdbcOperations>()
    private val catalog = PostgresqlTravelCatalog(jdbc, ObjectMapper())

    @Test
    fun `선택된 PostgreSQL 목록과 건수를 조회할 때__장소를 읽으면__같은 유형 필터와 바인딩된 지역과 Long 오프셋을 유지한다`() {
        // given
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } returns listOf<Any>()
        every { jdbc.queryForObject(any<String>(), any<Class<Long>>(), *anyVararg()) } returns 0L

        // when
        catalog.places("11", Integer.MAX_VALUE)
        val count = catalog.countPlaces("11")

        // then
        val listSql = slot<String>()
        val countSql = slot<String>()
        verify(exactly = 1) {
            jdbc.query(capture(listSql), any<RowMapper<Any>>(), PROVIDER, "11", 21474836470L)
        }
        assertThat(count).isZero()
        verify(exactly = 1) {
            jdbc.queryForObject(capture(countSql), Long::class.javaObjectType, PROVIDER, "11")
        }
        for (sql in listOf(listSql.captured, countSql.captured)) {
            assertThat(sql).contains(
                "FROM pawbridge_animal.pet_travel_targets t",
                "t.shown=TRUE",
                "t.area_code IS NOT NULL",
                "CASE WHEN t.basic_data IS NOT NULL",
                "(t.basic_data->>'contenttypeid')",
                "(p.common_data->>'contenttypeid')",
                "IN ('12','14','28')",
                "t.area_code=?",
            )
            assertThat(sql).doesNotContain("21474836470", "UPDATE ", "INSERT ", "DELETE ", "FOR UPDATE")
        }
        assertThat(listSql.captured)
            .endsWith("COALESCE((t.basic_data->>'title'),p.title),t.content_id LIMIT 10 OFFSET ?")
    }

    @ParameterizedTest
    @CsvSource(
        "LEGACY,false,false,,PREPARING",
        "LEGACY,true,false,,READY",
        "LEGACY,true,true,,STALE",
        "LEGACY,false,false,error,FAILED",
        "LEGACY,true,false,error,STALE",
        "BULK,true,false,future,READY",
        "BULK,true,false,past,STALE",
    )
    fun `저장된 행이 있을 때__상세를 읽으면__탐색 덮어쓰기와 사진 권리와 반려동물 스냅샷 상태를 유지한다`(
        source: String,
        fetched: Boolean,
        pending: Boolean,
        mode: String?,
        expected: String,
    ) {
        // given
        val row = mockk<ResultSet>()

        every { row.getString(any<String>()) } returns null
        every { row.getTimestamp(any<String>()) } returns null
        every { row.getString("common_data") } returns
            "{\"contentid\":\"123\",\"title\":\"old\",\"firstimage\":\"old-photo\",\"cpyrhtDivCd\":\"Type1\"}"
        every { row.getString("basic_data") } returns
            "{\"contentid\":\"123\",\"title\":\"current\",\"contenttypeid\":\"12\"}"
        every { row.getString("image_url") } returns "current-photo"
        every { row.getString("copyright_type") } returns "Type2"
        every { row.getString("pet_source") } returns source
        every { row.getBoolean("pending") } returns pending
        every { row.getString("detail_error") } returns if ("error" == mode) "DETAIL_FAILED" else null
        every { row.getTimestamp("pet_fetched_at") } returns
            if (fetched) Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")) else null
        every { row.getTimestamp("pet_valid_until") } returns
            if ("future" == mode) {
                Timestamp.from(Instant.parse("2100-01-01T00:00:00Z"))
            } else if ("past" == mode) {
                Timestamp.from(Instant.EPOCH)
            } else {
                null
            }
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val sql: String = firstArg()
            if (sql.contains("pet_travel_images")) {
                return@answers listOf<Any>()
            }
            assertThat(sql).doesNotContain("IN ('12','14','28')")
            val mapper: RowMapper<*> = secondArg()
            listOf(mapper.mapRow(row, 0))
        }

        // when
        val place = catalog.detail("123").orElseThrow()

        // then
        assertThat(place.common)
            .containsEntry("title", "current")
            .containsEntry("firstimage", "current-photo")
            .containsEntry("cpyrhtDivCd", "Type2")
        assertThat(place.detailStatus).isEqualTo(expected)
        assertThat(place.images).isEmpty()
        verify(exactly = 2) { jdbc.query(any<String>(), any<RowMapper<Any>>(), PROVIDER, "123") }
    }

    @Test
    fun `지역 목록과 빈 상세를 읽을 때__조회하면__숫자 순서를 쓰고 메타데이터 조회나 이미지 로드를 하지 않는다`() {
        // given
        every { jdbc.query(any<String>(), any<RowMapper<Any>>()) } returns listOf<Any>()
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } returns listOf<Any>()

        // when
        catalog.regions()
        val detail = catalog.detail("123")

        // then
        verify(exactly = 1) {
            jdbc.query(
                "SELECT * FROM pawbridge_animal.pet_travel_regions ORDER BY CAST(code AS INTEGER)",
                any<RowMapper<Any>>(),
            )
        }
        assertThat(detail).isEmpty()
        verify(exactly = 0) {
            jdbc.query(match<String> { it.startsWith("SELECT serial_number") }, any<RowMapper<Any>>(), *anyVararg())
        }
    }

    companion object {
        private const val PROVIDER = "KOREA_TOURISM_ORGANIZATION"
    }
}
