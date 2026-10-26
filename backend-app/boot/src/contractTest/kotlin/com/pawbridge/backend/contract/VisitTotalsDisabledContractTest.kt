package com.pawbridge.backend.contract

import com.pawbridge.backend.PawBridgeApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

/**
 * The visit total route when the feature flag is off (replaces the disabled phase of the packaged F16 smoke).
 *
 * This is the one contract with its own Spring context on purpose: `pawbridge.visits.enabled` is read when the
 * application starts, so the shared context (flag on) cannot show the route as absent. Everything else is the
 * same application, database and storage; the properties below repeat the shared ones except the flag.
 */
@Tag("contract")
@SpringBootTest(
    classes = [PawBridgeApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.port=0",
        "pawbridge.auth.sessions-enabled=true",
        "jwt.access-token-expiration=120999",
        "jwt.refresh-token-expiration=600999",
        "pawbridge.cors.allowed-origin-patterns=https://contract.example.test",
        "pawbridge.visits.enabled=false",
    ],
)
@ContextConfiguration(classes = [PawBridgeApplication::class], initializers = [ContractApplicationInitializer::class])
class VisitTotalsDisabledContractTest : ApiContractTestBase() {
    @Test
    fun `방문 기능이 꺼져 있을 때__오늘을 조회하면__404이고 쿠키도 DB 변경도 저장소 접촉도 없다`() {
        // given
        execute("INSERT INTO pawbridge_community.daily_visit_count(visit_date, unique_visitors) VALUES (CURRENT_DATE, 5)")

        // when
        val reply = assertNoSideEffects {
            get(
                "/api/v1/visits/today?date=1900-01-01&count=1",
                "definitely-not-a-jwt",
                mapOf("X-User-Id" to "9999999", "X-User-Role" to "ROLE_ADMIN", "Origin" to "https://foreign.invalid", "Cookie" to "__Host-pb_visit=forged"),
            )
        }

        // then
        assertThat(reply.status).isEqualTo(404)
        assertThat(reply.headers.firstValue("Set-Cookie")).isEmpty
    }
}
