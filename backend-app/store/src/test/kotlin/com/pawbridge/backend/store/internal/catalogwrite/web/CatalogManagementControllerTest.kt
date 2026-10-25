package com.pawbridge.backend.store.internal.catalogwrite.web

import com.pawbridge.backend.store.anyNonNull
import com.pawbridge.backend.store.captureNonNull
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogCategories
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.S3ProductImages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.multipart.MultipartFile

/** HTTP body/status/dispatch only; Root must verify the real JWT/current-admin security boundary. */
class CatalogManagementControllerTest {
    private val products: JpaCatalogProducts = mock(JpaCatalogProducts::class.java)
    private val options: JpaCatalogOptions = mock(JpaCatalogOptions::class.java)
    private val images: S3ProductImages = mock(S3ProductImages::class.java)
    private val categories: JpaCatalogCategories = mock(JpaCatalogCategories::class.java)
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        mvc = MockMvcBuilders.standaloneSetup(
            ProductWriteController(products),
            OptionManagementController(options),
            ProductImageController(images),
            CategoryManagementController(categories),
        ).setControllerAdvice(CatalogManagementExceptionHandler()).build()
    }

    @Test
    fun givenProductBody__whenCreating__thenBindOriginalFieldsAndReturn201() {
        `when`(products.createProduct(anyNonNull<ProductCreateRequest>())).thenReturn(
            ProductResponse.builder().id(10L).name("food").status("ACTIVE")
                .description("description").imageUrl("image.png").categoryId(1L).categoryName("food category")
                .skus(
                    listOf(
                        SkuDetailDto.builder().skuId(101L).skuCode("FOOD-S").price(100L).stockQuantity(3)
                            .options(mapOf("size" to "small")).build(),
                    ),
                ).build(),
        )

        mvc.perform(
            post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content(
                """
                {"name":"food","description":"description","imageUrl":"image.png","categoryId":1,
                 "skus":[{"skuCode":"FOOD-S","price":100,"stockQuantity":3,"optionValueIds":[2]}]}
                """.trimIndent() + "\n",
            ),
        ).andExpect(status().isCreated())
            .andExpect(jsonPath("\$.id").value(10))
            .andExpect(jsonPath("\$.categoryName").value("food category"))
            .andExpect(jsonPath("\$.skus[0].skuId").value(101))
            .andExpect(jsonPath("\$.skus[0].options.size").value("small"))

        val request: ArgumentCaptor<ProductCreateRequest> = ArgumentCaptor.forClass(ProductCreateRequest::class.java)
        verify(products).createProduct(captureNonNull(request))
        assertThat(request.value.categoryId).isEqualTo(1L)
        assertThat(request.value.skus!!.first().optionValueIds).containsExactly(2L)
    }

    @Test
    fun givenProductPatchAndDelete__whenDispatched__thenKeepOriginalRoutesAndStatuses() {
        `when`(products.updateProduct(eq(10L), anyNonNull<ProductUpdateRequest>())).thenReturn(
            ProductResponse.builder().id(10L).name("new food").status("HIDDEN").skus(listOf()).build(),
        )

        mvc.perform(
            patch("/api/v1/products/10").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new food\",\"status\":\"HIDDEN\"}"),
        ).andExpect(status().isOk()).andExpect(jsonPath("\$.status").value("HIDDEN"))

        val request: ArgumentCaptor<ProductUpdateRequest> = ArgumentCaptor.forClass(ProductUpdateRequest::class.java)
        verify(products).updateProduct(eq(10L), captureNonNull(request))
        assertThat(request.value.name).isEqualTo("new food")
        mvc.perform(delete("/api/v1/products/10")).andExpect(status().isNoContent()).andExpect(content().string(""))
        verify(products).deleteProduct(10L)
    }

    @Test
    fun givenGroupWriteRequests__whenDispatched__thenKeepOriginalMethodsAndResponseShape() {
        val response = OptionGroupResponse.builder().id(1L).name("size").values(listOf()).build()
        `when`(options.createOptionGroup(anyNonNull<OptionGroupRequest>())).thenReturn(response)
        `when`(options.updateOptionGroup(eq(1L), anyNonNull<OptionGroupRequest>())).thenReturn(response)

        mvc.perform(
            post("/api/v1/option-groups").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"size\",\"values\":[\"small\"]}"),
        ).andExpect(status().isCreated()).andExpect(jsonPath("\$.id").value(1)).andExpect(jsonPath("\$.values").isArray())

        val request: ArgumentCaptor<OptionGroupRequest> = ArgumentCaptor.forClass(OptionGroupRequest::class.java)
        verify(options).createOptionGroup(captureNonNull(request))
        assertThat(request.value.values).containsExactly("small")
        mvc.perform(
            put("/api/v1/option-groups/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"size\"}"),
        ).andExpect(status().isOk())
        verify(options).updateOptionGroup(eq(1L), anyNonNull<OptionGroupRequest>())
        mvc.perform(delete("/api/v1/option-groups/1")).andExpect(status().isNoContent())
        verify(options).deleteOptionGroup(1L)
    }

    @Test
    fun givenValueWriteRequests__whenDispatched__thenKeepOriginalMethodsAndGroupIdentity() {
        val response = OptionValueResponse.builder().id(2L).name("small").groupId(1L).groupName("size").build()
        `when`(options.addOptionValue(eq(1L), anyNonNull<OptionValueRequest>())).thenReturn(response)
        `when`(options.updateOptionValue(eq(2L), anyNonNull<OptionValueRequest>())).thenReturn(response)

        mvc.perform(
            post("/api/v1/option-groups/1/values").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"small\"}"),
        ).andExpect(status().isCreated())
            .andExpect(jsonPath("\$.groupId").value(1))
            .andExpect(jsonPath("\$.groupName").value("size"))

        val request: ArgumentCaptor<OptionValueRequest> = ArgumentCaptor.forClass(OptionValueRequest::class.java)
        verify(options).addOptionValue(eq(1L), captureNonNull(request))
        assertThat(request.value.name).isEqualTo("small")
        mvc.perform(
            put("/api/v1/option-groups/values/2").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"small\"}"),
        ).andExpect(status().isOk())
        verify(options).updateOptionValue(eq(2L), anyNonNull<OptionValueRequest>())
        mvc.perform(delete("/api/v1/option-groups/values/2")).andExpect(status().isNoContent())
        verify(options).deleteOptionValue(2L)
    }

    @Test
    fun givenMultipartImage__whenUploaded__thenReturnOriginalImageUrlEnvelope() {
        `when`(images.uploadImage(anyNonNull<MultipartFile>())).thenReturn("https://assets.invalid/products/photo.png")
        val image = MockMultipartFile("file", "photo.png", "image/png", byteArrayOf(1))

        mvc.perform(multipart("/api/v1/images").file(image))
            .andExpect(status().isOk())
            .andExpect(jsonPath("\$.imageUrl").value("https://assets.invalid/products/photo.png"))

        verify(images).uploadImage(anyNonNull<MultipartFile>())
    }

    @Test
    fun givenCategoryWriteRequests__whenDispatched__thenKeepOriginalMethodsStatusesAndTreeShape() {
        val child = CategoryResponse.builder().id(2L).name("dry").parentId(1L).children(listOf()).build()
        val response = CategoryResponse.builder().id(1L).name("food").description("food category")
            .children(listOf(child)).build()
        `when`(categories.createCategory(anyNonNull<CategoryCreateRequest>())).thenReturn(response)
        `when`(categories.updateCategory(eq(1L), anyNonNull<CategoryUpdateRequest>())).thenReturn(response)

        mvc.perform(
            post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"food\",\"description\":\"food category\",\"parentId\":5}"),
        ).andExpect(status().isCreated())
            .andExpect(jsonPath("\$.id").value(1))
            .andExpect(jsonPath("\$.parentId").isEmpty())
            .andExpect(jsonPath("\$.children[0].parentId").value(1))
            .andExpect(jsonPath("\$.children[0].children").isEmpty())

        val created: ArgumentCaptor<CategoryCreateRequest> = ArgumentCaptor.forClass(CategoryCreateRequest::class.java)
        verify(categories).createCategory(captureNonNull(created))
        assertThat(created.value.name).isEqualTo("food")
        assertThat(created.value.description).isEqualTo("food category")
        assertThat(created.value.parentId).isEqualTo(5L)
        mvc.perform(
            put("/api/v1/categories/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"food\",\"parentId\":null}"),
        ).andExpect(status().isOk()).andExpect(jsonPath("\$.name").value("food"))
        val updated: ArgumentCaptor<CategoryUpdateRequest> = ArgumentCaptor.forClass(CategoryUpdateRequest::class.java)
        verify(categories).updateCategory(eq(1L), captureNonNull(updated))
        assertThat(updated.value.parentId).isNull()
        mvc.perform(delete("/api/v1/categories/1")).andExpect(status().isNoContent()).andExpect(content().string(""))
        verify(categories).deleteCategory(1L)
    }

    @Test
    fun givenCategoryBusinessFailures__whenMapped__thenKeepOriginal400InputAnd500StateErrorCodes() {
        `when`(categories.updateCategory(eq(1L), anyNonNull<CategoryUpdateRequest>()))
            .thenThrow(IllegalArgumentException("synthetic self parent"))

        mvc.perform(
            put("/api/v1/categories/1").contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":1}"),
        ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C001"))
            .andExpect(jsonPath("\$.errors").isEmpty())
        doThrow(IllegalStateException("synthetic child refusal")).`when`(categories).deleteCategory(1L)
        mvc.perform(delete("/api/v1/categories/1"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))
        mvc.perform(delete("/api/v1/categories/abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C005"))
    }

    @Test
    fun givenOriginalBusinessFailures__whenMapped__thenKeep400InputAnd500StateErrorCodes() {
        `when`(products.createProduct(anyNonNull<ProductCreateRequest>()))
            .thenThrow(IllegalArgumentException("synthetic invalid input"))

        mvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"food\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("\$.code").value("C001"))
            .andExpect(jsonPath("\$.errors").isEmpty())
        doThrow(IllegalStateException("synthetic cart refusal")).`when`(products).deleteProduct(10L)
        mvc.perform(delete("/api/v1/products/10"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("\$.code").value("C004"))
    }
}
