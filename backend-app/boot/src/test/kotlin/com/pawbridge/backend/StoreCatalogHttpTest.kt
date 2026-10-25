package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogController
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogExceptionHandler
import com.pawbridge.backend.support.anyNonNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var catalog: StoreCatalog

    @ParameterizedTest
    @MethodSource("routesAndRoles")
    fun given__catalog_enabled__when__reading_each_route__then__preserve_public_get_for_anonymous_user_admin_and_unrelated_bad_bearer(
        path: String,
        role: String,
    ) {
        whenever(catalog.search(anyNonNull<StoreCatalog.Search>()))
            .thenReturn(StoreCatalog.SearchResult(listOf(), 0L, 0, 0, false))
        whenever(catalog.product(8)).thenReturn(
            StoreCatalog.Product(8L, "synthetic", null, null, "ACTIVE", null, null, 0L, null, null, listOf()),
        )
        whenever(catalog.categories()).thenReturn(listOf())
        whenever(catalog.optionGroups()).thenReturn(listOf())
        whenever(catalog.optionGroup(8)).thenReturn(StoreCatalog.OptionGroup(8L, "synthetic", mutableListOf()))
        val request = get(path).header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN")
        if (role == "badBearer") {
            request.header("Authorization", "Bearer unrelated-invalid-token")
        } else if (role != "missing") {
            request.header("Authorization", "Bearer " + F3TestInputs.token(7, role))
        }
        mvc.perform(request).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"))
        when (path) {
            "/api/v1/products" -> verify(catalog)
                .search(StoreCatalog.Search(null, null, null, null, false, null, "desc", 0, 20))
            "/api/v1/products/8" -> verify(catalog).product(8)
            "/api/v1/categories" -> verify(catalog).categories()
            "/api/v1/option-groups" -> verify(catalog).optionGroups()
            "/api/v1/option-groups/8" -> verify(catalog).optionGroup(8)
            else -> throw AssertionError("Unknown synthetic route")
        }
        verifyNoMoreInteractions(catalog)
    }

    @ParameterizedTest
    @CsvSource(
        "POST,/api/v1/products",
        "PUT,/api/v1/products/8",
        "POST,/api/v1/categories",
        "POST,/api/v1/option-groups",
        "DELETE,/api/v1/option-groups/8",
    )
    fun given__verified_admin__when__attempting_unported_writes__then__deny_without_catalog_call(
        method: String,
        path: String,
    ) {
        mvc.perform(
            request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(catalog)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "badBearer"])
    fun given__an_existing_protected_user_route__when__jwt_is_missing_or_invalid__then__keep_unauthorized_before_service(
        credentials: String,
    ) {
        val request = get("/api/v1/users/me").header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN")
        if (credentials == "badBearer") {
            request.header("Authorization", "Bearer unrelated-invalid-token")
        }
        mvc.perform(request).andExpect(status().isUnauthorized())
        verifyNoInteractions(catalog)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["/api/v1/products/8/extra", "/api/v1/categories/8", "/api/v1/option-groups/8/extra", "/api/products"],
    )
    fun given__verified_admin__when__requesting_unexposed_neighbors_or_gateway_alias__then__deny_without_catalog_call(
        path: String,
    ) {
        mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
            .andExpect(status().isForbidden())
        verifyNoInteractions(catalog)
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

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [
        StoreCatalogController::class,
        StoreCatalogExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreCatalogDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var catalog: StoreCatalog

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/api/v1/products", "/api/v1/products/8", "/api/v1/categories", "/api/v1/option-groups",
            "/api/v1/option-groups/8",
        ],
    )
    fun given__no_explicit_enablement__when__verified_admin_reads_each_route__then__deny_before_service(path: String) {
        mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
            .andExpect(status().isForbidden())
        verifyNoInteractions(catalog)
    }
}
