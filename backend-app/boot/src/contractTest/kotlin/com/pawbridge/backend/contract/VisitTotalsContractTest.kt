package com.pawbridge.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Today's unique visitor total, read only (replaces the enabled phase of the packaged F16 smoke). */
class VisitTotalsContractTest : ApiContractTestBase() {
    @Test
    fun `어제와 오늘과 내일의 합계가 있을 때__오늘을 조회하면__서울 오늘의 정확한 long 합계를 캐시 없이 준다`() {
        // given
        val today = seoulToday()
        val counts = mapOf(today.minusDays(1) to 7L, today to 9_007_199_254_740_993L, today.plusDays(1) to 11L)
        for ((day, count) in counts) {
            execute("INSERT INTO pawbridge_community.daily_visit_count(visit_date, unique_visitors) VALUES (?::date, ?)", day.toString(), count)
        }

        // when
        val plain = visit(counts)
        val malformedBearer = visit(counts, token = "definitely-not-a-jwt")

        // then
        assertThat(plain.get("count").isLong).`as`("The total is an exact long, beyond the JavaScript safe range").isTrue()
        assertThat(malformedBearer.get("count")).isEqualTo(plain.get("count"))
    }

    @Test
    fun `합계 행이 없을 때__오늘을 조회하면__0이다`() {
        // given
        // when
        val data = visit(emptyMap())

        // then
        assertThat(data.get("count").asLong()).isZero()
    }

    @Test
    fun `합계 열 권한이 없을 때__오늘을 조회하면__안전한 503이고 쿠키와 캐시가 없으며 복구되면 조회된다`() {
        // given
        val today = seoulToday()
        execute("INSERT INTO pawbridge_community.daily_visit_count(visit_date, unique_visitors) VALUES (?::date, 9)", today.toString())

        // when
        val denied = ContractPostgres.withRevoked("SELECT(unique_visitors)", "pawbridge_community.daily_visit_count") {
            assertNoSideEffects { today(FORGED) }
        }
        val recovered = visit(mapOf(today to 9L))

        // then
        assertThat(denied.status).isEqualTo(503)
        assertThat(denied.text).isEqualTo("""{"code":503,"message":"방문 통계를 불러올 수 없습니다.","data":null}""")
        assertThat(denied.headers.firstValue("Cache-Control").orElse("")).isEqualTo("no-store")
        assertThat(denied.headers.firstValue("Set-Cookie")).isEmpty
        assertThat(recovered.get("count").asLong()).isEqualTo(9L)
    }

    /** One successful GET: exactly {date, count}, no cookie, no-store, no write, no storage call; returns the data. */
    private fun visit(counts: Map<LocalDate, Long>, token: String? = null): JsonNode {
        val began = seoulToday()
        val reply = assertNoSideEffects { today(FORGED, token) }
        val ended = seoulToday()
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.headers.firstValue("Set-Cookie")).`as`("A visit read sets no cookie").isEmpty
        assertThat(reply.headers.firstValue("Cache-Control").orElse("")).isEqualTo("no-store")
        assertThat(reply.code).isEqualTo(200)
        assertThat(reply.message).isEqualTo("요청이 성공적으로 처리되었습니다.")
        val data = reply.data
        assertThat(data.fieldNames().asSequence().toSet()).isEqualTo(setOf("date", "count"))
        val day = LocalDate.parse(data.get("date").asText())
        assertThat(day).`as`("The request may cross Seoul midnight").isIn(began, ended)
        assertThat(data.get("count").asLong()).isEqualTo(counts[day] ?: 0L)
        return data
    }

    private fun today(headers: Map<String, String>, token: String? = null): Reply =
        get("/api/v1/visits/today?date=1900-01-01&count=1", token, headers)

    private fun seoulToday(): LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul"))

    private companion object {
        val FORGED = mapOf(
            "X-User-Id" to "9999999", "X-User-Role" to "ROLE_ADMIN", "Origin" to "https://foreign.invalid",
            "Cookie" to "__Host-pb_visit=forged", "User-Agent" to "synthetic-bot",
        )
    }
}
