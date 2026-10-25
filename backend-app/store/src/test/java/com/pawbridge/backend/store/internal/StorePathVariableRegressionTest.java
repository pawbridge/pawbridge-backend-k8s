package com.pawbridge.backend.store.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.pawbridge.backend.store.api.StorePaymentEvents;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogCategories;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts;
import com.pawbridge.backend.store.internal.catalogwrite.web.CatalogManagementExceptionHandler;
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryManagementController;
import com.pawbridge.backend.store.internal.catalogwrite.web.CategoryUpdateRequest;
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionGroupRequest;
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionManagementController;
import com.pawbridge.backend.store.internal.catalogwrite.web.OptionValueRequest;
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductUpdateRequest;
import com.pawbridge.backend.store.internal.catalogwrite.web.ProductWriteController;
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController;
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentExceptionHandler;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingPathVariableException;

/**
 * Locks in the Java behaviour of store path variables after the Java to Kotlin conversion.
 *
 * <p>A Kotlin {@code @PathVariable id: Long?} makes Spring treat the variable as optional, so a blank
 * segment ({@code /%20}) reached the service as null. The Java original declared a required boxed
 * {@code Long}, which fails with {@link MissingPathVariableException} before the handler runs. Every
 * path variable of the controllers below must keep that behaviour.
 *
 * <p>Standalone MockMvc with the module exception handlers and mocked services only. It does not cover the
 * servlet container, the security filter chain or error dispatch.
 */
class StorePathVariableRegressionTest {

    /** Expanded into the URI template, so it reaches Spring as the blank segment {@code %20}. */
    private static final String BLANK_SEGMENT = " ";

    private static final long ID = 10L;

    private static final String CATALOG_BODY = "{\"name\":\"x\"}";

    /** Body written by {@link CatalogManagementExceptionHandler} for the Java original. */
    private static final String CATALOG_SERVER_ERROR_BODY =
            "{\"message\":\"Server Error\",\"code\":\"C004\",\"errors\":[]}";

    private final JpaCatalogCategories categories = mock(JpaCatalogCategories.class);
    private final JpaCatalogOptions options = mock(JpaCatalogOptions.class);
    private final JpaCatalogProducts products = mock(JpaCatalogProducts.class);
    private final StorePaymentEvents payments = mock(StorePaymentEvents.class);

    private MockMvc catalogMvc;
    private MockMvc paymentMvc;

    @BeforeEach
    void setUp() {
        catalogMvc = MockMvcBuilders
                .standaloneSetup(
                        new CategoryManagementController(categories),
                        new OptionManagementController(options),
                        new ProductWriteController(products))
                .setControllerAdvice(new CatalogManagementExceptionHandler())
                .build();
        paymentMvc = MockMvcBuilders
                .standaloneSetup(new MockOrderPaymentController(payments))
                .setControllerAdvice(new MockOrderPaymentExceptionHandler())
                .build();
    }

    // CategoryManagementController

    @Test
    void givenBlankCategoryId__whenUpdatingCategory__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = performJson(catalogMvc, put("/api/v1/categories/{categoryId}", BLANK_SEGMENT));

        assertJavaOriginalServerError(result);
        verifyNoInteractions(categories);
    }

    @Test
    void givenNumericCategoryId__whenUpdatingCategory__thenPassTheIdToService() throws Exception {
        performJson(catalogMvc, put("/api/v1/categories/{categoryId}", ID));

        verify(categories).updateCategory(eq(ID), any(CategoryUpdateRequest.class));
    }

    @Test
    void givenBlankCategoryId__whenDeletingCategory__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = catalogMvc.perform(delete("/api/v1/categories/{categoryId}", BLANK_SEGMENT)).andReturn();

        assertJavaOriginalServerError(result);
        verifyNoInteractions(categories);
    }

    @Test
    void givenNumericCategoryId__whenDeletingCategory__thenPassTheIdToService() throws Exception {
        catalogMvc.perform(delete("/api/v1/categories/{categoryId}", ID));

        verify(categories).deleteCategory(ID);
    }

    // OptionManagementController: option groups

    @Test
    void givenBlankOptionGroupId__whenUpdatingOptionGroup__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = performJson(catalogMvc, put("/api/v1/option-groups/{groupId}", BLANK_SEGMENT));

        assertJavaOriginalServerError(result);
        verifyNoInteractions(options);
    }

    @Test
    void givenNumericOptionGroupId__whenUpdatingOptionGroup__thenPassTheIdToService() throws Exception {
        performJson(catalogMvc, put("/api/v1/option-groups/{groupId}", ID));

        verify(options).updateOptionGroup(eq(ID), any(OptionGroupRequest.class));
    }

    @Test
    void givenBlankOptionGroupId__whenDeletingOptionGroup__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = catalogMvc.perform(delete("/api/v1/option-groups/{groupId}", BLANK_SEGMENT)).andReturn();

        assertJavaOriginalServerError(result);
        verifyNoInteractions(options);
    }

    @Test
    void givenNumericOptionGroupId__whenDeletingOptionGroup__thenPassTheIdToService() throws Exception {
        catalogMvc.perform(delete("/api/v1/option-groups/{groupId}", ID));

        verify(options).deleteOptionGroup(ID);
    }

    @Test
    void givenBlankOptionGroupId__whenAddingOptionValue__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = performJson(catalogMvc, post("/api/v1/option-groups/{groupId}/values", BLANK_SEGMENT));

        assertJavaOriginalServerError(result);
        verifyNoInteractions(options);
    }

    @Test
    void givenNumericOptionGroupId__whenAddingOptionValue__thenPassTheIdToService() throws Exception {
        performJson(catalogMvc, post("/api/v1/option-groups/{groupId}/values", ID));

        verify(options).addOptionValue(eq(ID), any(OptionValueRequest.class));
    }

    // OptionManagementController: option values

    @Test
    void givenBlankOptionValueId__whenUpdatingOptionValue__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = performJson(catalogMvc, put("/api/v1/option-groups/values/{valueId}", BLANK_SEGMENT));

        assertJavaOriginalServerError(result);
        verifyNoInteractions(options);
    }

    @Test
    void givenNumericOptionValueId__whenUpdatingOptionValue__thenPassTheIdToService() throws Exception {
        performJson(catalogMvc, put("/api/v1/option-groups/values/{valueId}", ID));

        verify(options).updateOptionValue(eq(ID), any(OptionValueRequest.class));
    }

    @Test
    void givenBlankOptionValueId__whenDeletingOptionValue__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = catalogMvc.perform(delete("/api/v1/option-groups/values/{valueId}", BLANK_SEGMENT))
                .andReturn();

        assertJavaOriginalServerError(result);
        verifyNoInteractions(options);
    }

    @Test
    void givenNumericOptionValueId__whenDeletingOptionValue__thenPassTheIdToService() throws Exception {
        catalogMvc.perform(delete("/api/v1/option-groups/values/{valueId}", ID));

        verify(options).deleteOptionValue(ID);
    }

    // ProductWriteController

    @Test
    void givenBlankProductId__whenUpdatingProduct__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = performJson(catalogMvc, patch("/api/v1/products/{productId}", BLANK_SEGMENT));

        assertJavaOriginalServerError(result);
        verifyNoInteractions(products);
    }

    @Test
    void givenNumericProductId__whenUpdatingProduct__thenPassTheIdToService() throws Exception {
        performJson(catalogMvc, patch("/api/v1/products/{productId}", ID));

        verify(products).updateProduct(eq(ID), any(ProductUpdateRequest.class));
    }

    @Test
    void givenBlankProductId__whenDeletingProduct__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = catalogMvc.perform(delete("/api/v1/products/{productId}", BLANK_SEGMENT)).andReturn();

        assertJavaOriginalServerError(result);
        verifyNoInteractions(products);
    }

    @Test
    void givenNumericProductId__whenDeletingProduct__thenPassTheIdToService() throws Exception {
        catalogMvc.perform(delete("/api/v1/products/{productId}", ID));

        verify(products).deleteProduct(ID);
    }

    // MockOrderPaymentController

    @Test
    void givenBlankOrderId__whenProcessingMockPayment__thenFailAsMissingPathVariableWithoutCallingService() throws Exception {
        MvcResult result = paymentMvc.perform(post("/api/v1/orders/{orderId}/payment", BLANK_SEGMENT)).andReturn();

        assertJavaOriginalServerError(result);
        verifyNoInteractions(payments);
    }

    @Test
    void givenNumericOrderId__whenProcessingMockPayment__thenPassTheIdToService() throws Exception {
        paymentMvc.perform(post("/api/v1/orders/{orderId}/payment", ID));

        verify(payments).processPayment(ID);
    }

    // helpers

    private static MvcResult performJson(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(CATALOG_BODY)).andReturn();
    }

    /** Java original: 500, the handler's C004 body, and MissingPathVariableException as the resolved exception. */
    private static void assertJavaOriginalServerError(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(CATALOG_SERVER_ERROR_BODY);
        assertThat(result.getResolvedException()).isInstanceOf(MissingPathVariableException.class);
    }
}
