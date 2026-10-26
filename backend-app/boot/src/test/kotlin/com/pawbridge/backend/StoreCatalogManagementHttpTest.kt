package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
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

    @MockkBean
    lateinit var products: JpaCatalogProducts

    @MockkBean
    lateinit var options: JpaCatalogOptions

    @MockkBean
    lateinit var images: S3ProductImages

    @MockkBean
    lateinit var catalog: StoreCatalog

    @MockkBean
    lateinit var categories: JpaCatalogCategories

    @AfterEach
    fun `요청이 끝났을 때__보안 범위를 확인하면__주체를 남기지 않는다`() {
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @MethodSource("writes")
    fun `관리가 켜졌고 서명된 관리자일 때__정식 쓰기를 각각 쓰면__원래 핸들러에만 도달한다`(
        route: Route,
    ) {
        // given
        val request = command(route)
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
            .header("X-User-Id", "99")
            .header("X-User-Role", "ROLE_USER")

        every { products.createProduct(any()) } returns
            ProductResponse.builder().id(8L).name("food").status("ACTIVE").skus(listOf()).build()
        every { products.updateProduct(any(), any()) } returns
            ProductResponse.builder().id(8L).name("patched").status("HIDDEN").skus(listOf()).build()
        justRun { products.deleteProduct(any()) }
        val group = OptionGroupResponse.builder().id(2L).name("size").values(listOf()).build()
        every { options.createOptionGroup(any()) } returns group
        every { options.updateOptionGroup(any(), any()) } returns group
        justRun { options.deleteOptionGroup(any()) }
        val value = OptionValueResponse.builder().id(3L).name("small").groupId(2L).groupName("size").build()
        every { options.addOptionValue(any(), any()) } returns value
        every { options.updateOptionValue(any(), any()) } returns value
        justRun { options.deleteOptionValue(any()) }
        every { images.uploadImage(any()) } returns "https://fixture.invalid/products/upload.png"
        every { categories.createCategory(any()) } returns
            CategoryResponse.builder().id(4L).name("food").parentId(1L).children(listOf()).build()
        every { categories.updateCategory(any(), any()) } returns
            CategoryResponse.builder().id(4L).name("dry").parentId(1L).children(listOf()).build()
        justRun { categories.deleteCategory(any()) }

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().`is`(route.status))
            .andExpect(header().doesNotExist("Set-Cookie"))
        when (route.method + " " + route.path) {
            "POST /api/v1/products" -> {
                result.andExpect(jsonPath("$.id").value(8)).andExpect(jsonPath("$.status").value("ACTIVE"))
                verify(exactly = 1) { products.createProduct(match<ProductCreateRequest> { body -> "food" == body.name }) }
            }
            "PATCH /api/v1/products/8" -> {
                result.andExpect(jsonPath("$.name").value("patched"))
                verify(exactly = 1) {
                    products.updateProduct(8L, match<ProductUpdateRequest> { body -> "patched" == body.name })
                }
            }
            "DELETE /api/v1/products/8" -> {
                result.andExpect(content().string(""))
                verify(exactly = 1) { products.deleteProduct(8L) }
            }
            "POST /api/v1/option-groups" -> {
                result.andExpect(jsonPath("$.id").value(2))
                verify(exactly = 1) {
                    options.createOptionGroup(
                        match<OptionGroupRequest> { body -> "size" == body.name && body.values == listOf("small") },
                    )
                }
            }
            "PUT /api/v1/option-groups/2" -> {
                result.andExpect(jsonPath("$.name").value("size"))
                verify(exactly = 1) {
                    options.updateOptionGroup(2L, match<OptionGroupRequest> { body -> "size" == body.name })
                }
            }
            "DELETE /api/v1/option-groups/2" -> verify(exactly = 1) { options.deleteOptionGroup(2L) }
            "POST /api/v1/option-groups/2/values" -> {
                result.andExpect(jsonPath("$.groupId").value(2))
                verify(exactly = 1) {
                    options.addOptionValue(2L, match<OptionValueRequest> { body -> "small" == body.name })
                }
            }
            "PUT /api/v1/option-groups/values/3" -> {
                result.andExpect(jsonPath("$.id").value(3))
                verify(exactly = 1) {
                    options.updateOptionValue(3L, match<OptionValueRequest> { body -> "small" == body.name })
                }
            }
            "DELETE /api/v1/option-groups/values/3" -> verify(exactly = 1) { options.deleteOptionValue(3L) }
            "POST /api/v1/categories" -> {
                result.andExpect(jsonPath("$.id").value(4)).andExpect(jsonPath("$.children").isArray())
                verify(exactly = 1) {
                    categories.createCategory(
                        match<CategoryCreateRequest> { body -> "food" == body.name && 1L == body.parentId },
                    )
                }
            }
            "PUT /api/v1/categories/4" -> {
                result.andExpect(jsonPath("$.name").value("dry"))
                verify(exactly = 1) {
                    categories.updateCategory(
                        4L,
                        match<CategoryUpdateRequest> { body -> "dry" == body.name && body.parentId == null },
                    )
                }
            }
            "DELETE /api/v1/categories/4" -> {
                result.andExpect(content().string(""))
                verify(exactly = 1) { categories.deleteCategory(4L) }
            }
            "POST /api/v1/images" -> {
                result.andExpect(jsonPath("$.imageUrl").value("https://fixture.invalid/products/upload.png"))
                verify(exactly = 1) {
                    images.uploadImage(
                        match<org.springframework.web.multipart.MultipartFile> { file ->
                            "photo.png" == file.originalFilename && file.size == 3L
                        },
                    )
                }
            }
            else -> throw AssertionError("Unknown catalog write contract")
        }
        assertThat(result.andReturn().request.getSession(false)).isNull()
        confirmVerified(products, options, images, catalog, categories)
    }

    @ParameterizedTest
    @MethodSource("deniedCredentials")
    fun `사용자 또는 무효 JWT와 위조 관리자 헤더일 때__각 쓰기를 호출하면__스토어 비즈니스 전에 거부한다`(
        route: Route,
        credentials: String,
    ) {
        // given
        val call = command(route).header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN")
        if (credentials == "ROLE_USER") {
            call.header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        } else if (credentials == "invalid") {
            call.header("Authorization", "Bearer invalid-token")
        }

        // when
        val result = mvc.perform(call)

        // then
        result
            .andExpect(status().`is`(if (credentials == "ROLE_USER") 403 else 401))
            .andExpect(header().doesNotExist("Set-Cookie"))
        confirmVerified(products, options, images, catalog, categories)
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
    fun `서명된 관리자일 때__게이트웨이 별칭이나 소유하지 않은 메서드 이웃을 쓰면__기본 거부를 유지한다`(
        method: String,
        path: String,
    ) {
        // given
        val request = request(HttpMethod.valueOf(method), path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(products, options, images, catalog, categories)
    }

    @ParameterizedTest
    @MethodSource("reads")
    fun `관리는 켜졌지만 공개 카탈로그가 꺼졌을 때__관리자가 카탈로그를 읽으면__공개 GET 범위를 넓히지 않는다`(
        path: String,
    ) {
        // given
        val request = get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(products, options, images, catalog, categories)
    }

    @Test
    fun `승인된 비즈니스 거절이 있을 때__조언이 처리하면__인증 밖에서 기존 스토어 오류를 보존한다`() {
        // given
        val adminToken = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        every { products.createProduct(any()) } throws IllegalArgumentException("synthetic input refusal")
        every { products.deleteProduct(any()) } throws IllegalStateException("synthetic cart refusal")
        every { categories.deleteCategory(any()) } throws IllegalStateException("synthetic child refusal")

        // when
        val created = mvc.perform(command(WRITES.first()).header("Authorization", adminToken))
        val deleted = mvc.perform(command(WRITES[2]).header("Authorization", adminToken))
        val categoryDeleted = mvc.perform(command(WRITES.last()).header("Authorization", adminToken))

        // then
        created
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("C001"))
        deleted
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("C004"))
        categoryDeleted
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("C004"))
        verify(exactly = 1) { products.createProduct(any()) }
        verify(exactly = 1) { products.deleteProduct(8L) }
        verify(exactly = 1) { categories.deleteCategory(4L) }
        confirmVerified(products, options, images, catalog, categories)
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

    @MockkBean
    lateinit var products: JpaCatalogProducts

    @MockkBean
    lateinit var options: JpaCatalogOptions

    @MockkBean
    lateinit var images: S3ProductImages

    @MockkBean
    lateinit var catalog: StoreCatalog

    @MockkBean
    lateinit var categories: JpaCatalogCategories

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.StoreCatalogManagementHttpTest#writes")
    fun `관리가 기본 꺼짐일 때__서명된 관리자가 정식 쓰기를 각각 쓰면__비즈니스 전에 거부한다`(
        route: StoreCatalogManagementHttpTest.Route,
    ) {
        // given
        val request = StoreCatalogManagementHttpTest.command(route)
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(products, options, images, catalog, categories)
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

    @MockkBean
    lateinit var products: JpaCatalogProducts

    @MockkBean
    lateinit var options: JpaCatalogOptions

    @MockkBean
    lateinit var images: S3ProductImages

    @MockkBean
    lateinit var catalog: StoreCatalog

    @MockkBean
    lateinit var categories: JpaCatalogCategories

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.StoreCatalogManagementHttpTest#writes")
    fun `리터럴 true 대신 불리언 별칭일 때__관리자가 각 쓰기를 쓰면__관리를 닫은 채 유지한다`(
        route: StoreCatalogManagementHttpTest.Route,
    ) {
        // given
        val request = StoreCatalogManagementHttpTest.command(route)
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(products, options, images, catalog, categories)
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

    @MockkBean
    lateinit var products: JpaCatalogProducts

    @MockkBean
    lateinit var options: JpaCatalogOptions

    @MockkBean
    lateinit var images: S3ProductImages

    @MockkBean
    lateinit var catalog: StoreCatalog

    @MockkBean
    lateinit var categories: JpaCatalogCategories

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.StoreCatalogManagementHttpTest#writes")
    fun `공개 카탈로그만 켜졌을 때__관리자가 각 관리 쓰기를 시도하면__비즈니스 전에 거부한다`(
        route: StoreCatalogManagementHttpTest.Route,
    ) {
        // given
        val request = StoreCatalogManagementHttpTest.command(route)
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isForbidden())
        confirmVerified(products, options, images, catalog, categories)
    }

    @ParameterizedTest
    @MethodSource("com.pawbridge.backend.StoreCatalogManagementHttpTest#reads")
    fun `관리 없이 공개 카탈로그만 켜졌을 때__무관한 잘못된 베어러로 읽으면__공개 GET과 기존 읽기 조언을 유지한다`(
        path: String,
    ) {
        // given
        val request = get(path)
            .header("Authorization", "Bearer unrelated-invalid-token")
            .header("X-User-Role", "ROLE_ADMIN")

        every { catalog.search(any()) } returns StoreCatalog.SearchResult(listOf(), 0L, 0, 0, false)
        every { catalog.product(any()) } returns
            StoreCatalog.Product(8L, "food", null, null, "ACTIVE", null, null, 0L, null, null, listOf())
        every { catalog.categories() } returns listOf()
        every { catalog.optionGroups() } returns listOf()
        every { catalog.optionGroup(any()) } returns StoreCatalog.OptionGroup(2L, "size", mutableListOf())

        // when
        val result = mvc.perform(request)

        // then
        result
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
        when (path) {
            "/api/v1/products" -> verify(exactly = 1) {
                catalog.search(StoreCatalog.Search(null, null, null, null, false, null, "desc", 0, 20))
            }
            "/api/v1/products/8" -> verify(exactly = 1) { catalog.product(8) }
            "/api/v1/categories" -> verify(exactly = 1) { catalog.categories() }
            "/api/v1/option-groups" -> verify(exactly = 1) { catalog.optionGroups() }
            "/api/v1/option-groups/2" -> verify(exactly = 1) { catalog.optionGroup(2) }
            else -> throw AssertionError("Unknown catalog read contract")
        }
        confirmVerified(catalog)
        confirmVerified(products, options, images, categories)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun `카탈로그 검색을 쓸 수 없을 때__공개 GET이 실패하면__관리 오류 캡처 없이 읽기 조언을 쓴다`() {
        // given
        every { catalog.search(any()) } throws CatalogSearchUnavailableException()

        // when
        val result = mvc.perform(get("/api/v1/products"))

        // then
        result
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("P006"))
        verify(exactly = 1) { catalog.search(any()) }
        confirmVerified(catalog)
        confirmVerified(products, options, images, categories)
    }
}
