package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogController
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.stream.Stream

@WebMvcTest(
    properties = ["pawbridge.store-catalog.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        StoreCatalogController::class,
        StoreCatalogExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreCatalogHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var catalog: StoreCatalog

    @ParameterizedTest
    @MethodSource("routesAndRoles")
    fun `카탈로그가 켜져 있을 때__각 경로를 읽으면__익명과 사용자와 관리자와 무관한 잘못된 토큰에도 공개 GET을 유지한다`(
        path: String,
        role: String,
    ) {
        // given
        val request = get(path).header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN")
        if (role == "badBearer") {
            request.header("Authorization", "Bearer unrelated-invalid-token")
        } else if (role != "missing") {
            request.header("Authorization", "Bearer " + F3TestInputs.token(7, role))
        }

        every { catalog.search(any()) } returns StoreCatalog.SearchResult(listOf(), 0L, 0, 0, false)
        every { catalog.product(any()) } returns
            StoreCatalog.Product(8L, "synthetic", null, null, "ACTIVE", null, null, 0L, null, null, listOf())
        every { catalog.categories() } returns listOf()
        every { catalog.optionGroups() } returns listOf()
        every { catalog.optionGroup(any()) } returns StoreCatalog.OptionGroup(8L, "synthetic", mutableListOf())

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
        when (path) {
            "/api/v1/products" -> verify(exactly = 1) {
                catalog.search(StoreCatalog.Search(null, null, null, null, false, null, "desc", 0, 20))
            }
            "/api/v1/products/8" -> verify(exactly = 1) { catalog.product(8) }
            "/api/v1/categories" -> verify(exactly = 1) { catalog.categories() }
            "/api/v1/option-groups" -> verify(exactly = 1) { catalog.optionGroups() }
            "/api/v1/option-groups/8" -> verify(exactly = 1) { catalog.optionGroup(8) }
            else -> throw AssertionError("Unknown synthetic route")
        }
        confirmVerified(catalog)
    }

    companion object {
        private val PATHS = listOf(
            "/api/v1/products",
            "/api/v1/products/8",
            "/api/v1/categories",
            "/api/v1/option-groups",
            "/api/v1/option-groups/8",
        )

        @JvmStatic
        fun routesAndRoles(): Stream<Arguments> {
            return PATHS.stream().flatMap { path ->
                Stream.of(
                    Arguments.of(path, "missing"),
                    Arguments.of(path, "ROLE_USER"),
                    Arguments.of(path, "ROLE_ADMIN"),
                    Arguments.of(path, "badBearer"),
                )
            }
        }
    }
}
