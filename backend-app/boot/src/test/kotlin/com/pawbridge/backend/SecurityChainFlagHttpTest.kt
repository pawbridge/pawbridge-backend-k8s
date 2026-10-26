package com.pawbridge.backend

import com.pawbridge.backend.SecurityChainFixture.Companion.bearer
import com.pawbridge.backend.SecurityChainFixture.Companion.send
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Feature flags decide which routes exist at all. For every flag combination in [SecurityRoutes]:
 * with only that combination switched on its own routes are reachable and every other flagged route is
 * refused with 403 before any handler, even for an administrator; and a value that is only an alias
 * (`on`) must not open a route whose flag the chain compares with the literal `true`.
 */
class SecurityChainFlagHttpTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("contexts")
    fun `기능 플래그 조합이 주어졌을 때__모든 경로를 요청하면__켜진 경로만 열리고 나머지는 핸들러 전에 403이다`(
        name: String,
        properties: Map<String, String>,
    ) {
        // given
        // when
        val results = outcomesIn(properties)

        // then
        assertThat(name).isNotBlank()
        results.forEach { (route, open, outcomes) ->
            outcomes.forEach { outcome ->
                if (open) {
                    assertThat(outcome.status).describedAs("%s", route).isEqualTo(200)
                    assertThat(outcome.reached).describedAs("%s", route).isEqualTo(1)
                } else {
                    assertThat(outcome.status).describedAs("%s", route).isEqualTo(403)
                    assertThat(outcome.reached).describedAs("%s", route).isZero()
                }
            }
        }
        assertThat(results.count { it.open }).describedAs(name).isEqualTo(
            SecurityRoutes.routes.count { route ->
                SecurityRoutes.isOpen(properties, route.method, route.example)
            },
        )
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("strictGroups")
    fun `엄격한 플래그가 불리언 별칭일 때__모든 경로를 요청하면__true일 때만 열리는 경로는 닫힌 채로 유지한다`(
        group: List<Requirement>,
        name: String,
    ) {
        // given
        val properties = SecurityRoutes.propertiesOf(group) + group.filter { !it.lenient && it.property != Requirement.BACKEND }
            .associate { it.property to "on" }

        // when
        val results = outcomesIn(properties)

        // then
        assertThat(name).isNotBlank()
        val closedByAlias = results.filter { (route, open, _) ->
            !open && route.requires.any { !it.lenient && it.property != Requirement.BACKEND && it in group }
        }
        assertThat(closedByAlias).isNotEmpty()
        results.forEach { (route, open, outcomes) ->
            outcomes.forEach { outcome ->
                if (open) {
                    assertThat(outcome.status).describedAs("%s", route).isEqualTo(200)
                } else {
                    assertThat(outcome.status).describedAs("%s", route).isEqualTo(403)
                    assertThat(outcome.reached).describedAs("%s", route).isZero()
                }
            }
        }
    }

    private data class RouteOutcome(val route: Route, val open: Boolean, val outcomes: List<SecurityChainFixture.Outcome>)

    private fun outcomesIn(properties: Map<String, String>): List<RouteOutcome> {
        val chain = SecurityChainFixture.with(properties)
        return SecurityRoutes.routes.map { route ->
            val serving = SecurityRoutes.routes.filter { it.isOpenIn(properties) && it.matches(route.method, route.example) }
            val outcomes = if (serving.isEmpty()) {
                listOf(
                    chain.call(send(route.method, route.example, bearer(7, "ROLE_ADMIN"))),
                    chain.call(send(route.method, route.example, null)),
                )
            } else {
                val authorization = when (serving.first().access) {
                    Access.PUBLIC -> null
                    Access.MEMBER -> bearer(7, "ROLE_USER")
                    else -> bearer(7, "ROLE_ADMIN")
                }
                listOf(chain.call(send(route.method, route.example, authorization)))
            }
            RouteOutcome(route, serving.isNotEmpty(), outcomes)
        }
    }

    companion object {
        /**
         * Each feature switched on alone, and, for features that need two properties, each property missing in turn
         * (a flag on without the postgresql backend, the backend without the flag).
         */
        @JvmStatic
        fun contexts(): List<Array<Any>> = SecurityRoutes.flagGroups.flatMap { group ->
            val full = listOf(arrayOf<Any>("${SecurityRoutes.describe(group)} 켜짐", SecurityRoutes.propertiesOf(group)))
            val missingOne = if (group.size > 1) {
                group.map { missing ->
                    arrayOf<Any>(
                        "${SecurityRoutes.describe(group)} 중 ${missing.property.removePrefix("pawbridge.")} 없음",
                        SecurityRoutes.propertiesOf(group - missing),
                    )
                }
            } else {
                emptyList()
            }
            full + missingOne
        }

        @JvmStatic
        fun strictGroups(): List<Array<Any>> = SecurityRoutes.flagGroups
            .filter { group -> group.any { !it.lenient && it.property != Requirement.BACKEND } }
            .map { arrayOf(it, SecurityRoutes.describe(it)) }
    }
}
