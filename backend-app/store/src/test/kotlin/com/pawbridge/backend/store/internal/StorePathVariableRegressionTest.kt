package com.pawbridge.backend.store.internal

import com.pawbridge.backend.store.anyNonNull
import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogCategories
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts
import com.pawbridge.backend.store.internal.catalogwrite.web.CatalogManagementExceptionHandler
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryManagementController
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryUpdateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionGroupRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionManagementController
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionValueRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductUpdateRequest
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductWriteController
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.MissingPathVariableException
import java.nio.charset.StandardCharsets

/**
 * Locks in the Java behaviour of store path variables after the Java to Kotlin conversion.
 *
 * A Kotlin `@PathVariable id: Long?` makes Spring treat the variable as optional, so a blank
 * segment (`/%20`) reached the service as null. The Java original declared a required boxed
 * `Long`, which fails with [MissingPathVariableException] before the handler runs. Every
 * path variable of the controllers below must keep that behaviour.
 *
 * Standalone MockMvc with the module exception handlers and mocked services only. It does not cover the
 * servlet container, the security filter chain or error dispatch.
 */
class StorePathVariableRegressionTest {
    private val categories: JpaCatalogCategories = mock(JpaCatalogCategories::class.java)
    private val options: JpaCatalogOptions = mock(JpaCatalogOptions::class.java)
    private val products: JpaCatalogProducts = mock(JpaCatalogProducts::class.java)
    private val payments: StorePaymentEvents = mock(StorePaymentEvents::class.java)

    private lateinit var catalogMvc: MockMvc
    private lateinit var paymentMvc: MockMvc

    @BeforeEach
    fun setUp() {
        catalogMvc = MockMvcBuilders
            .standaloneSetup(
                CategoryManagementController(categories),
                OptionManagementController(options),
                ProductWriteController(products),
            )
            .setControllerAdvice(CatalogManagementExceptionHandler())
            .build()
        paymentMvc = MockMvcBuilders
            .standaloneSetup(MockOrderPaymentController(payments))
            .setControllerAdvice(MockOrderPaymentExceptionHandler())
            .build()
    }

    // CategoryManagementController

    @Test
    fun givenBlankCategoryId__whenUpdatingCategory__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = performJson(catalogMvc, put("/api/v1/categories/{categoryId}", BLANK_SEGMENT))

        assertJavaOriginalServerError(result)
        verifyNoInteractions(categories)
    }

    @Test
    fun givenNumericCategoryId__whenUpdatingCategory__thenPassTheIdToService() {
        performJson(catalogMvc, put("/api/v1/categories/{categoryId}", ID))

        verify(categories).updateCategory(eq(ID), anyNonNull<CategoryUpdateRequest>())
    }

    @Test
    fun givenBlankCategoryId__whenDeletingCategory__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = catalogMvc.perform(delete("/api/v1/categories/{categoryId}", BLANK_SEGMENT)).andReturn()

        assertJavaOriginalServerError(result)
        verifyNoInteractions(categories)
    }

    @Test
    fun givenNumericCategoryId__whenDeletingCategory__thenPassTheIdToService() {
        catalogMvc.perform(delete("/api/v1/categories/{categoryId}", ID))

        verify(categories).deleteCategory(ID)
    }

    // OptionManagementController: option groups

    @Test
    fun givenBlankOptionGroupId__whenUpdatingOptionGroup__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = performJson(catalogMvc, put("/api/v1/option-groups/{groupId}", BLANK_SEGMENT))

        assertJavaOriginalServerError(result)
        verifyNoInteractions(options)
    }

    @Test
    fun givenNumericOptionGroupId__whenUpdatingOptionGroup__thenPassTheIdToService() {
        performJson(catalogMvc, put("/api/v1/option-groups/{groupId}", ID))

        verify(options).updateOptionGroup(eq(ID), anyNonNull<OptionGroupRequest>())
    }

    @Test
    fun givenBlankOptionGroupId__whenDeletingOptionGroup__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = catalogMvc.perform(delete("/api/v1/option-groups/{groupId}", BLANK_SEGMENT)).andReturn()

        assertJavaOriginalServerError(result)
        verifyNoInteractions(options)
    }

    @Test
    fun givenNumericOptionGroupId__whenDeletingOptionGroup__thenPassTheIdToService() {
        catalogMvc.perform(delete("/api/v1/option-groups/{groupId}", ID))

        verify(options).deleteOptionGroup(ID)
    }

    @Test
    fun givenBlankOptionGroupId__whenAddingOptionValue__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = performJson(catalogMvc, post("/api/v1/option-groups/{groupId}/values", BLANK_SEGMENT))

        assertJavaOriginalServerError(result)
        verifyNoInteractions(options)
    }

    @Test
    fun givenNumericOptionGroupId__whenAddingOptionValue__thenPassTheIdToService() {
        performJson(catalogMvc, post("/api/v1/option-groups/{groupId}/values", ID))

        verify(options).addOptionValue(eq(ID), anyNonNull<OptionValueRequest>())
    }

    // OptionManagementController: option values

    @Test
    fun givenBlankOptionValueId__whenUpdatingOptionValue__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = performJson(catalogMvc, put("/api/v1/option-groups/values/{valueId}", BLANK_SEGMENT))

        assertJavaOriginalServerError(result)
        verifyNoInteractions(options)
    }

    @Test
    fun givenNumericOptionValueId__whenUpdatingOptionValue__thenPassTheIdToService() {
        performJson(catalogMvc, put("/api/v1/option-groups/values/{valueId}", ID))

        verify(options).updateOptionValue(eq(ID), anyNonNull<OptionValueRequest>())
    }

    @Test
    fun givenBlankOptionValueId__whenDeletingOptionValue__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = catalogMvc.perform(delete("/api/v1/option-groups/values/{valueId}", BLANK_SEGMENT))
            .andReturn()

        assertJavaOriginalServerError(result)
        verifyNoInteractions(options)
    }

    @Test
    fun givenNumericOptionValueId__whenDeletingOptionValue__thenPassTheIdToService() {
        catalogMvc.perform(delete("/api/v1/option-groups/values/{valueId}", ID))

        verify(options).deleteOptionValue(ID)
    }

    // ProductWriteController

    @Test
    fun givenBlankProductId__whenUpdatingProduct__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = performJson(catalogMvc, patch("/api/v1/products/{productId}", BLANK_SEGMENT))

        assertJavaOriginalServerError(result)
        verifyNoInteractions(products)
    }

    @Test
    fun givenNumericProductId__whenUpdatingProduct__thenPassTheIdToService() {
        performJson(catalogMvc, patch("/api/v1/products/{productId}", ID))

        verify(products).updateProduct(eq(ID), anyNonNull<ProductUpdateRequest>())
    }

    @Test
    fun givenBlankProductId__whenDeletingProduct__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = catalogMvc.perform(delete("/api/v1/products/{productId}", BLANK_SEGMENT)).andReturn()

        assertJavaOriginalServerError(result)
        verifyNoInteractions(products)
    }

    @Test
    fun givenNumericProductId__whenDeletingProduct__thenPassTheIdToService() {
        catalogMvc.perform(delete("/api/v1/products/{productId}", ID))

        verify(products).deleteProduct(ID)
    }

    // MockOrderPaymentController

    @Test
    fun givenBlankOrderId__whenProcessingMockPayment__thenFailAsMissingPathVariableWithoutCallingService() {
        val result = paymentMvc.perform(post("/api/v1/orders/{orderId}/payment", BLANK_SEGMENT)).andReturn()

        assertJavaOriginalServerError(result)
        verifyNoInteractions(payments)
    }

    @Test
    fun givenNumericOrderId__whenProcessingMockPayment__thenPassTheIdToService() {
        paymentMvc.perform(post("/api/v1/orders/{orderId}/payment", ID))

        verify(payments).processPayment(ID)
    }

    companion object {
        /** Expanded into the URI template, so it reaches Spring as the blank segment `%20`. */
        private const val BLANK_SEGMENT = " "
        private const val ID = 10L
        private const val CATALOG_BODY = "{\"name\":\"x\"}"

        /** Body written by [CatalogManagementExceptionHandler] for the Java original. */
        private const val CATALOG_SERVER_ERROR_BODY =
            "{\"message\":\"Server Error\",\"code\":\"C004\",\"errors\":[]}"

        // helpers

        private fun performJson(mvc: MockMvc, request: MockHttpServletRequestBuilder): MvcResult {
            return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(CATALOG_BODY)).andReturn()
        }

        /** Java original: 500, the handler's C004 body, and MissingPathVariableException as the resolved exception. */
        private fun assertJavaOriginalServerError(result: MvcResult) {
            assertThat(result.response.status).isEqualTo(500)
            assertThat(result.response.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(CATALOG_SERVER_ERROR_BODY)
            assertThat(result.resolvedException).isInstanceOf(MissingPathVariableException::class.java)
        }
    }
}
