package com.pawbridge.backend.store.internal.catalog.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.store.anyNonNull
import com.pawbridge.backend.store.internal.catalog.application.CatalogSearchUnavailableException
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime
import java.util.stream.Stream

class StoreCatalogControllerTest {
    private val catalog: StoreCatalog = mock(StoreCatalog::class.java)
    private val json: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        mvc = MockMvcBuilders.standaloneSetup(StoreCatalogController(catalog))
            .setControllerAdvice(StoreCatalogExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(json)).build()
    }

    @Test
    fun given__no_search_parameters__when__get_products__then__bind_original_defaults_and_return_raw_result() {
        `when`(catalog.search(anyNonNull<StoreCatalog.Search>()))
            .thenReturn(StoreCatalog.SearchResult(listOf(), 0L, 0, 0, false))

        mvc.perform(get("/api/v1/products")).andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"items\":[],\"totalCount\":0,\"currentPage\":0,\"totalPages\":0,\"hasNext\":false}",
                    true,
                ),
            )

        verify(catalog).search(StoreCatalog.Search(null, null, null, null, false, null, "desc", 0, 20))
    }

    @Test
    fun given__all_search_parameters__when__get_products__then__forward_typed_values() {
        `when`(catalog.search(anyNonNull<StoreCatalog.Search>())).thenReturn(
            StoreCatalog.SearchResult(
                listOf(
                    StoreCatalog.Item(
                        7L, 17L, "사료", "색상: 파랑", "사료", null, 9007199254740993L, 0, "ACTIVE",
                        LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123456000), null,
                    ),
                ),
                201L,
                2,
                3,
                false,
            ),
        )

        val body = mvc.perform(
            get("/api/v1/products").param("keyword", "사료").param("minPrice", "0")
                .param("maxPrice", "9007199254740993")
                .param("categoryId", "8").param("inStockOnly", "true").param("sortBy", "minPrice")
                .param("sortOrder", "asc")
                .param("page", "2").param("size", "100"),
        ).andExpect(status().isOk()).andReturn().response.contentAsByteArray

        val item = json.readTree(body).get("items").get(0)
        assertEquals(7L, item.get("id").longValue())
        assertEquals(17L, item.get("skuId").longValue())
        assertEquals("색상: 파랑", item.get("optionName").asText())
        assertEquals("사료", item.get("description").asText())
        assertEquals(9007199254740993L, item.get("price").longValue())
        assertEquals(0, item.get("totalStock").intValue())
        assertEquals("2026-10-20 01:02:03", item.get("createdAt").asText())
        assertTrue(item.get("updatedAt").isNull())
        verify(catalog).search(StoreCatalog.Search("사료", 0L, 9007199254740993L, 8L, true, "minPrice", "asc", 2, 100))
    }

    @Test
    fun given__full_deleted_product__when__getting_detail__then__keep_raw_fields_nulls_long_money_and_legacy_time() {
        `when`(catalog.product(9)).thenReturn(
            StoreCatalog.Product(
                9L, "상품", null, null, "DELETED", null, null, 0L,
                LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123456000), null,
                listOf(StoreCatalog.Sku(31L, "A", 9007199254740993L, 0, mutableMapOf("색상" to "파랑"))),
            ),
        )

        val body = mvc.perform(get("/api/v1/products/9")).andExpect(status().isOk()).andReturn().response.contentAsString

        val node = json.readTree(body)
        assertEquals(11, node.size())
        assertFalse(node.has("data"))
        assertEquals("DELETED", node.get("status").asText())
        assertTrue(node.get("categoryId").isNull())
        assertTrue(node.get("description").isNull())
        assertTrue(node.get("imageUrl").isNull())
        assertTrue(node.get("updatedAt").isNull())
        assertEquals("2026-10-20 01:02:03", node.get("createdAt").asText())
        assertEquals(9007199254740993L, node.get("skus").get(0).get("price").longValue())
        assertEquals(0, node.get("skus").get(0).get("stockQuantity").intValue())
    }

    @Test
    fun given__categories_and_option_metadata__when__reading__then__preserve_tree_and_group_wire() {
        `when`(catalog.categories()).thenReturn(
            listOf(
                StoreCatalog.Category(
                    1L, "루트", null, null,
                    listOf(StoreCatalog.Category(2L, "자식", "설명", 1L, listOf())),
                ),
            ),
        )
        `when`(catalog.optionGroups()).thenReturn(listOf(StoreCatalog.OptionGroup(3L, "색상", mutableListOf())))
        `when`(catalog.optionGroup(3)).thenReturn(
            StoreCatalog.OptionGroup(3L, "색상", mutableListOf(StoreCatalog.OptionValue(4L, "파랑", 3L, "색상"))),
        )

        mvc.perform(get("/api/v1/categories")).andExpect(status().isOk())
            .andExpect(jsonPath("\$[0].children[0].parentId").value(1))
        mvc.perform(get("/api/v1/option-groups")).andExpect(status().isOk())
            .andExpect(jsonPath("\$[0].values").isEmpty())
        mvc.perform(get("/api/v1/option-groups/3")).andExpect(status().isOk())
            .andExpect(jsonPath("\$.values[0].groupName").value("색상"))
    }

    @ParameterizedTest
    @MethodSource("errors")
    fun given__catalog_failure__when__reading__then__retain_original_status_and_error_without_private_detail(
        path: String,
        failure: RuntimeException,
        statusCode: Int,
        code: String,
        message: String,
    ) {
        if (path == "/api/v1/products") {
            `when`(catalog.search(anyNonNull<StoreCatalog.Search>())).thenThrow(failure)
        } else if (path.contains("option-groups")) {
            `when`(catalog.optionGroup(9)).thenThrow(failure)
        } else {
            `when`(catalog.product(9)).thenThrow(failure)
        }

        mvc.perform(get(path)).andExpect(status().`is`(statusCode))
            .andExpect(
                content().json(
                    json.writeValueAsString(StoreCatalogExceptionHandler.Error(message, code, listOf())),
                    true,
                ),
            )
    }

    @ParameterizedTest
    @MethodSource("wrongTypes")
    fun given__wrong_path_or_query_type__when__reading__then__return_original_field_error_before_application(
        path: String,
        field: String,
        value: String,
    ) {
        mvc.perform(get(path)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
            .andExpect(jsonPath("\$.errors[0].field").value(field))
            .andExpect(jsonPath("\$.errors[0].value").value(value))
            .andExpect(jsonPath("\$.errors[0].reason").value("typeMismatch"))

        verifyNoInteractions(catalog)
    }

    @Test
    fun given__read_only_controller__when__posting_product__then__no_write_handler() {
        mvc.perform(post("/api/v1/products")).andExpect(status().isMethodNotAllowed())

        verifyNoInteractions(catalog)
    }

    companion object {
        @JvmStatic
        fun errors(): Stream<Arguments> = Stream.of(
            Arguments.of("/api/v1/products", IllegalArgumentException("private validation"), 400, "C001", "Invalid Input Value"),
            Arguments.of("/api/v1/products/9", IllegalArgumentException("Product not found: 9"), 400, "C001", "Invalid Input Value"),
            Arguments.of(
                "/api/v1/option-groups/9",
                IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: 9"),
                400,
                "C001",
                "Invalid Input Value",
            ),
            Arguments.of(
                "/api/v1/products",
                CatalogSearchUnavailableException(RuntimeException("private SQL")),
                503,
                "P006",
                "Search temporarily unavailable",
            ),
            Arguments.of("/api/v1/products/9", DataAccessResourceFailureException("private SQL"), 500, "C004", "Server Error"),
        )

        @JvmStatic
        fun wrongTypes(): Stream<Arguments> = Stream.of(
            Arguments.of("/api/v1/products/abc", "productId", "abc"),
            Arguments.of("/api/v1/products?size=x", "size", "x"),
            Arguments.of("/api/v1/option-groups/abc", "groupId", "abc"),
        )
    }
}
