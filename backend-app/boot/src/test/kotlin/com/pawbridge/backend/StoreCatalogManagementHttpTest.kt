package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.internal.catalog.application.CatalogSearchUnavailableException
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogController
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogExceptionHandler
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogCategories
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.S3ProductImages
import com.pawbridge.backend.store.internal.catalogwrite.web.CatalogManagementExceptionHandler
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryCreateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryManagementController
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryResponse
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryUpdateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionGroupRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionGroupResponse
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionManagementController
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionValueRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionValueResponse
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductCreateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductImageController
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductResponse
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductUpdateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductWriteController
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.support.argThatValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.stream.Stream

/** Actual ReadSecurity/JWT filter and scoped advice; only Store business collaborators are mocked. */
@WebMvcTest(
    properties = ["pawbridge.store-catalog-management.enabled=true", "pawbridge.store-catalog.enabled=false"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        ProductWriteController::class,
        OptionManagementController::class,
        ProductImageController::class,
        CategoryManagementController::class,
        CatalogManagementExceptionHandler::class,
        StoreCatalogController::class,
        StoreCatalogExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreCatalogManagementHttpTest {
    /** Keeps the `Route[...]` rendering of the former Java record, which appears in the parameterized test names. */
    data class Route(val method: String, val path: String, val status: Int, val body: String) {
        override fun toString(): String = "Route[method=$method, path=$path, status=$status, body=$body]"
    }

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var products: JpaCatalogProducts

    @MockitoBean
    lateinit var options: JpaCatalogOptions

    @MockitoBean
    lateinit var images: S3ProductImages

    @MockitoBean
    lateinit var catalog: StoreCatalog

    @MockitoBean
    lateinit var categories: JpaCatalogCategories

    @AfterEach
    fun given__request_finished__when__security_scope_checked__then__retain_no_principal() {
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("writes")
    fun given__enabled_management_and_signed_admin__when__using_each_canonical_write__then__reach_only_its_original_handler(
        route: Route,
    ) {
        stubWriteResponses(products, options, images, categories)
        val result = mvc.perform(
            command(route)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
                .header("X-User-Id", "99")
                .header("X-User-Role", "ROLE_USER"),
        )
            .andExpect(status().`is`(route.status))
            .andExpect(header().doesNotExist("Set-Cookie"))
        when (route.method + " " + route.path) {
            "POST /api/v1/products" -> {
                result.andExpect(jsonPath("$.id").value(8)).andExpect(jsonPath("$.status").value("ACTIVE"))
                verify(products).createProduct(argThatValue<ProductCreateRequest> { body -> "food" == body.name })
            }
            "PATCH /api/v1/products/8" -> {
                result.andExpect(jsonPath("$.name").value("patched"))
                verify(products).updateProduct(
                    eq(8L),
                    argThatValue<ProductUpdateRequest> { body -> "patched" == body.name },
                )
            }
            "DELETE /api/v1/products/8" -> {
                result.andExpect(content().string(""))
                verify(products).deleteProduct(8L)
            }
            "POST /api/v1/option-groups" -> {
                result.andExpect(jsonPath("$.id").value(2))
                verify(options).createOptionGroup(
                    argThatValue<OptionGroupRequest> { body -> "size" == body.name && body.values == listOf("small") },
                )
            }
            "PUT /api/v1/option-groups/2" -> {
                result.andExpect(jsonPath("$.name").value("size"))
                verify(options).updateOptionGroup(eq(2L), argThatValue<OptionGroupRequest> { body -> "size" == body.name })
            }
            "DELETE /api/v1/option-groups/2" -> verify(options).deleteOptionGroup(2L)
            "POST /api/v1/option-groups/2/values" -> {
                result.andExpect(jsonPath("$.groupId").value(2))
                verify(options).addOptionValue(eq(2L), argThatValue<OptionValueRequest> { body -> "small" == body.name })
            }
            "PUT /api/v1/option-groups/values/3" -> {
                result.andExpect(jsonPath("$.id").value(3))
                verify(options).updateOptionValue(eq(3L), argThatValue<OptionValueRequest> { body -> "small" == body.name })
            }
            "DELETE /api/v1/option-groups/values/3" -> verify(options).deleteOptionValue(3L)
            "POST /api/v1/categories" -> {
                result.andExpect(jsonPath("$.id").value(4)).andExpect(jsonPath("$.children").isArray())
                verify(categories).createCategory(
                    argThatValue<CategoryCreateRequest> { body -> "food" == body.name && 1L == body.parentId },
                )
            }
            "PUT /api/v1/categories/4" -> {
                result.andExpect(jsonPath("$.name").value("dry"))
                verify(categories).updateCategory(
                    eq(4L),
                    argThatValue<CategoryUpdateRequest> { body -> "dry" == body.name && body.parentId == null },
                )
            }
            "DELETE /api/v1/categories/4" -> {
                result.andExpect(content().string(""))
                verify(categories).deleteCategory(4L)
            }
            "POST /api/v1/images" -> {
                result.andExpect(jsonPath("$.imageUrl").value("https://fixture.invalid/products/upload.png"))
                verify(images).uploadImage(
                    argThatValue<org.springframework.web.multipart.MultipartFile> { file ->
                        "photo.png" == file.originalFilename && file.size == 3L
                    },
                )
            }
            else -> throw AssertionError("Unknown catalog write contract")
        }
        assertThat(result.andReturn().request.getSession(false)).isNull()
        verifyNoMoreInteractions(products, options, images, catalog, categories)
    }

    @ParameterizedTest
    @MethodSource("deniedCredentials")
    fun given__user_or_missing_invalid_jwt_with_forged_admin_headers__when__using_each_write__then__deny_before_store_business(
        route: Route,
        credentials: String,
    ) {
        val call = command(route).header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN")
        if (credentials == "ROLE_USER") {
            call.header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        } else if (credentials == "invalid") {
            call.header("Authorization", "Bearer invalid-token")
        }
        mvc.perform(call)
            .andExpect(status().`is`(if (credentials == "ROLE_USER") 403 else 401))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verifyNoInteractions(products, options, images, catalog, categories)
    }

    @ParameterizedTest
    @CsvSource(
        "POST,/api/products", "PATCH,/api/products/8", "DELETE,/api/products/8", "POST,/api/images",
        "POST,/api/option-groups", "PUT,/api/option-groups/2", "DELETE,/api/option-groups/2",
        "POST,/api/option-groups/2/values", "PUT,/api/option-groups/values/3", "DELETE,/api/option-groups/values/3",
        "PUT,/api/v1/products/8", "POST,/api/v1/products/8", "POST,/api/v1/products/8/skus",
        "PATCH,/api/v1/option-groups/2", "DELETE,/api/v1/option-groups", "PUT,/api/v1/images",
        "GET,/api/v1/images", "POST,/api/v1/products/8/extra",
        "POST,/api/categories", "PUT,/api/categories/4", "DELETE,/api/categories/4",
        "PATCH,/api/v1/categories/4", "POST,/api/v1/categories/4", "PUT,/api/v1/categories", "DELETE,/api/v1/categories",
        "POST,/api/v1/categories/4/extra",
    )
    fun given__signed_admin__when__using_gateway_alias_or_unowned_method_neighbor__then__retain_default_deny(
        method: String,
        path: String,
    ) {
        mvc.perform(
            request(HttpMethod.valueOf(method), path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(products, options, images, catalog, categories)
    }

    @ParameterizedTest
    @MethodSource("reads")
    fun given__management_enabled_but_public_catalog_disabled__when__admin_reads_catalog__then__do_not_widen_public_get_scope(
        path: String,
    ) {
        mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
            .andExpect(status().isForbidden())
        verifyNoInteractions(products, options, images, catalog, categories)
    }

    @Test
    fun given__authorized_business_refusal__when__advice_handles_it__then__preserve_original_store_errors_outside_authentication() {
        whenever(products.createProduct(anyNonNull<ProductCreateRequest>()))
            .thenThrow(IllegalArgumentException("synthetic input refusal"))
        mvc.perform(command(WRITES.first()).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("C001"))
        doThrow(IllegalStateException("synthetic cart refusal")).`when`(products).deleteProduct(8L)
        mvc.perform(command(WRITES[2]).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("C004"))
        doThrow(IllegalStateException("synthetic child refusal")).`when`(categories).deleteCategory(4L)
        mvc.perform(command(WRITES.last()).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("C004"))
        verify(products).createProduct(anyNonNull<ProductCreateRequest>())
        verify(products).deleteProduct(8L)
        verify(categories).deleteCategory(4L)
        verifyNoMoreInteractions(products, options, images, catalog, categories)
    }

    companion object {
        val WRITES: List<Route> = listOf(
            Route("POST", "/api/v1/products", 201, "{\"name\":\"food\",\"skus\":[]}"),
            Route("PATCH", "/api/v1/products/8", 200, "{\"name\":\"patched\",\"status\":\"HIDDEN\"}"),
            Route("DELETE", "/api/v1/products/8", 204, "{}"),
            Route("POST", "/api/v1/option-groups", 201, "{\"name\":\"size\",\"values\":[\"small\"]}"),
            Route("PUT", "/api/v1/option-groups/2", 200, "{\"name\":\"size\"}"),
            Route("DELETE", "/api/v1/option-groups/2", 204, "{}"),
            Route("POST", "/api/v1/option-groups/2/values", 201, "{\"name\":\"small\"}"),
            Route("PUT", "/api/v1/option-groups/values/3", 200, "{\"name\":\"small\"}"),
            Route("DELETE", "/api/v1/option-groups/values/3", 204, "{}"),
            Route("POST", "/api/v1/images", 200, ""),
            Route("POST", "/api/v1/categories", 201, "{\"name\":\"food\",\"parentId\":1}"),
            Route("PUT", "/api/v1/categories/4", 200, "{\"name\":\"dry\",\"parentId\":null}"),
            Route("DELETE", "/api/v1/categories/4", 204, "{}"),
        )
        val READS: List<String> = listOf(
            "/api/v1/products",
            "/api/v1/products/8",
            "/api/v1/categories",
            "/api/v1/option-groups",
            "/api/v1/option-groups/2",
        )

        @JvmStatic
        fun writes(): Stream<Route> = WRITES.stream()

        @JvmStatic
        fun reads(): Stream<String> = READS.stream()

        @JvmStatic
        fun deniedCredentials(): Stream<Arguments> {
            return WRITES.stream().flatMap { route ->
                Stream.of("ROLE_USER", "missing", "invalid").map { role -> Arguments.of(route, role) }
            }
        }

        fun command(route: Route): MockHttpServletRequestBuilder {
            if (route.path == "/api/v1/images") {
                return multipart(route.path).file(MockMultipartFile("file", "photo.png", "image/png", byteArrayOf(1, 2, 3)))
            }
            return request(HttpMethod.valueOf(route.method), route.path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(route.body)
        }

        fun stubWriteResponses(
            products: JpaCatalogProducts,
            options: JpaCatalogOptions,
            images: S3ProductImages,
            categories: JpaCatalogCategories,
        ) {
            whenever(products.createProduct(anyNonNull<ProductCreateRequest>())).thenReturn(
                ProductResponse.builder().id(8L).name("food").status("ACTIVE").skus(listOf()).build(),
            )
            whenever(products.updateProduct(eq(8L), anyNonNull<ProductUpdateRequest>())).thenReturn(
                ProductResponse.builder().id(8L).name("patched").status("HIDDEN").skus(listOf()).build(),
            )
            val group = OptionGroupResponse.builder().id(2L).name("size").values(listOf()).build()
            whenever(options.createOptionGroup(anyNonNull<OptionGroupRequest>())).thenReturn(group)
            whenever(options.updateOptionGroup(eq(2L), anyNonNull<OptionGroupRequest>())).thenReturn(group)
            val value = OptionValueResponse.builder().id(3L).name("small").groupId(2L).groupName("size").build()
            whenever(options.addOptionValue(eq(2L), anyNonNull<OptionValueRequest>())).thenReturn(value)
            whenever(options.updateOptionValue(eq(3L), anyNonNull<OptionValueRequest>())).thenReturn(value)
            whenever(images.uploadImage(anyNonNull<org.springframework.web.multipart.MultipartFile>()))
                .thenReturn("https://fixture.invalid/products/upload.png")
            val category = CategoryResponse.builder().id(4L).name("dry").parentId(1L).children(listOf()).build()
            whenever(categories.createCategory(anyNonNull<CategoryCreateRequest>())).thenReturn(
                CategoryResponse.builder().id(4L).name("food").parentId(1L).children(listOf()).build(),
            )
            whenever(categories.updateCategory(eq(4L), anyNonNull<CategoryUpdateRequest>())).thenReturn(category)
        }
    }
}

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [
        ProductWriteController::class,
        OptionManagementController::class,
        ProductImageController::class,
        CategoryManagementController::class,
        CatalogManagementExceptionHandler::class,
        StoreCatalogController::class,
        StoreCatalogExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreCatalogManagementDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var products: JpaCatalogProducts

    @MockitoBean
    lateinit var options: JpaCatalogOptions

    @MockitoBean
    lateinit var images: S3ProductImages

    @MockitoBean
    lateinit var catalog: StoreCatalog

    @MockitoBean
    lateinit var categories: JpaCatalogCategories

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.StoreCatalogManagementHttpTest#writes")
    fun given__management_default_off__when__signed_admin_uses_each_canonical_write__then__deny_before_business(
        route: StoreCatalogManagementHttpTest.Route,
    ) {
        mvc.perform(
            StoreCatalogManagementHttpTest.command(route)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(products, options, images, catalog, categories)
    }
}

@WebMvcTest(
    properties = ["pawbridge.store-catalog-management.enabled=on"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        ProductWriteController::class,
        OptionManagementController::class,
        ProductImageController::class,
        CategoryManagementController::class,
        CatalogManagementExceptionHandler::class,
        StoreCatalogController::class,
        StoreCatalogExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreCatalogManagementAliasDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var products: JpaCatalogProducts

    @MockitoBean
    lateinit var options: JpaCatalogOptions

    @MockitoBean
    lateinit var images: S3ProductImages

    @MockitoBean
    lateinit var catalog: StoreCatalog

    @MockitoBean
    lateinit var categories: JpaCatalogCategories

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.StoreCatalogManagementHttpTest#writes")
    fun given__boolean_alias_instead_of_literal_true__when__admin_uses_each_write__then__keep_management_closed(
        route: StoreCatalogManagementHttpTest.Route,
    ) {
        mvc.perform(
            StoreCatalogManagementHttpTest.command(route)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(products, options, images, catalog, categories)
    }
}

@WebMvcTest(
    properties = ["pawbridge.store-catalog.enabled=true", "pawbridge.store-catalog-management.enabled=false"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        ProductWriteController::class,
        OptionManagementController::class,
        ProductImageController::class,
        CategoryManagementController::class,
        CatalogManagementExceptionHandler::class,
        StoreCatalogController::class,
        StoreCatalogExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreCatalogManagementReadOnlyHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var products: JpaCatalogProducts

    @MockitoBean
    lateinit var options: JpaCatalogOptions

    @MockitoBean
    lateinit var images: S3ProductImages

    @MockitoBean
    lateinit var catalog: StoreCatalog

    @MockitoBean
    lateinit var categories: JpaCatalogCategories

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.StoreCatalogManagementHttpTest#writes")
    fun given__only_public_catalog_enabled__when__admin_attempts_each_management_write__then__deny_before_business(
        route: StoreCatalogManagementHttpTest.Route,
    ) {
        mvc.perform(
            StoreCatalogManagementHttpTest.command(route)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(products, options, images, catalog, categories)
    }

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.StoreCatalogManagementHttpTest#reads")
    fun given__public_catalog_without_management__when__anonymous_reads_with_unrelated_bad_bearer__then__keep_public_get_and_existing_read_advice(
        path: String,
    ) {
        whenever(catalog.search(anyNonNull<StoreCatalog.Search>()))
            .thenReturn(StoreCatalog.SearchResult(listOf(), 0L, 0, 0, false))
        whenever(catalog.product(8)).thenReturn(
            StoreCatalog.Product(8L, "food", null, null, "ACTIVE", null, null, 0L, null, null, listOf()),
        )
        whenever(catalog.categories()).thenReturn(listOf())
        whenever(catalog.optionGroups()).thenReturn(listOf())
        whenever(catalog.optionGroup(2)).thenReturn(StoreCatalog.OptionGroup(2L, "size", mutableListOf()))
        mvc.perform(get(path).header("Authorization", "Bearer unrelated-invalid-token").header("X-User-Role", "ROLE_ADMIN"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
        when (path) {
            "/api/v1/products" -> verify(catalog)
                .search(StoreCatalog.Search(null, null, null, null, false, null, "desc", 0, 20))
            "/api/v1/products/8" -> verify(catalog).product(8)
            "/api/v1/categories" -> verify(catalog).categories()
            "/api/v1/option-groups" -> verify(catalog).optionGroups()
            "/api/v1/option-groups/2" -> verify(catalog).optionGroup(2)
            else -> throw AssertionError("Unknown catalog read contract")
        }
        verifyNoMoreInteractions(catalog)
        verifyNoInteractions(products, options, images, categories)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun given__catalog_search_unavailable__when__public_get_fails__then__use_read_advice_without_management_error_capture() {
        whenever(catalog.search(anyNonNull<StoreCatalog.Search>())).thenThrow(CatalogSearchUnavailableException())
        mvc.perform(get("/api/v1/products"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("P006"))
        verify(catalog).search(anyNonNull<StoreCatalog.Search>())
        verifyNoMoreInteractions(catalog)
        verifyNoInteractions(products, options, images, categories)
    }
}
