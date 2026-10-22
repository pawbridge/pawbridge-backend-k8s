package com.pawbridge.backend.store.internal.catalogwrite.web;

import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogCategories;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.S3ProductImages;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP body/status/dispatch only; Root must verify the real JWT/current-admin security boundary. */
class CatalogManagementControllerTest {
    private final JpaCatalogProducts products=mock(JpaCatalogProducts.class);
    private final JpaCatalogOptions options=mock(JpaCatalogOptions.class);
    private final S3ProductImages images=mock(S3ProductImages.class);
    private final JpaCatalogCategories categories=mock(JpaCatalogCategories.class);
    private MockMvc mvc;
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new ProductWriteController(products),
            new OptionManagementController(options),new ProductImageController(images),new CategoryManagementController(categories)).setControllerAdvice(new CatalogManagementExceptionHandler()).build();}

    @Test void givenProductBody__whenCreating__thenBindOriginalFieldsAndReturn201() throws Exception {
        when(products.createProduct(any())).thenReturn(ProductResponse.builder().id(10L).name("food").status("ACTIVE")
                .description("description").imageUrl("image.png").categoryId(1L).categoryName("food category")
                .skus(List.of(SkuDetailDto.builder().skuId(101L).skuCode("FOOD-S").price(100L).stockQuantity(3).options(java.util.Map.of("size","small")).build())).build());
        mvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"food","description":"description","imageUrl":"image.png","categoryId":1,
                 "skus":[{"skuCode":"FOOD-S","price":100,"stockQuantity":3,"optionValueIds":[2]}]}
                """)).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(10)).andExpect(jsonPath("$.categoryName").value("food category"))
                .andExpect(jsonPath("$.skus[0].skuId").value(101)).andExpect(jsonPath("$.skus[0].options.size").value("small"));
        var request=ArgumentCaptor.forClass(ProductCreateRequest.class);verify(products).createProduct(request.capture());
        assertThat(request.getValue().getCategoryId()).isEqualTo(1L);assertThat(request.getValue().getSkus().getFirst().getOptionValueIds()).containsExactly(2L);
    }

    @Test void givenProductPatchAndDelete__whenDispatched__thenKeepOriginalRoutesAndStatuses() throws Exception {
        when(products.updateProduct(eq(10L),any())).thenReturn(ProductResponse.builder().id(10L).name("new food").status("HIDDEN").skus(List.of()).build());
        mvc.perform(patch("/api/v1/products/10").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"new food\",\"status\":\"HIDDEN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HIDDEN"));
        var request=ArgumentCaptor.forClass(ProductUpdateRequest.class);verify(products).updateProduct(eq(10L),request.capture());assertThat(request.getValue().getName()).isEqualTo("new food");
        mvc.perform(delete("/api/v1/products/10")).andExpect(status().isNoContent()).andExpect(content().string(""));verify(products).deleteProduct(10L);
    }

    @Test void givenGroupWriteRequests__whenDispatched__thenKeepOriginalMethodsAndResponseShape() throws Exception {
        var response=OptionGroupResponse.builder().id(1L).name("size").values(List.of()).build();
        when(options.createOptionGroup(any())).thenReturn(response);when(options.updateOptionGroup(eq(1L),any())).thenReturn(response);
        mvc.perform(post("/api/v1/option-groups").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"size\",\"values\":[\"small\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(1)).andExpect(jsonPath("$.values").isArray());
        var request=ArgumentCaptor.forClass(OptionGroupRequest.class);verify(options).createOptionGroup(request.capture());assertThat(request.getValue().getValues()).containsExactly("small");
        mvc.perform(put("/api/v1/option-groups/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"size\"}")).andExpect(status().isOk());verify(options).updateOptionGroup(eq(1L),any());
        mvc.perform(delete("/api/v1/option-groups/1")).andExpect(status().isNoContent());verify(options).deleteOptionGroup(1L);
    }

    @Test void givenValueWriteRequests__whenDispatched__thenKeepOriginalMethodsAndGroupIdentity() throws Exception {
        var response=OptionValueResponse.builder().id(2L).name("small").groupId(1L).groupName("size").build();
        when(options.addOptionValue(eq(1L),any())).thenReturn(response);when(options.updateOptionValue(eq(2L),any())).thenReturn(response);
        mvc.perform(post("/api/v1/option-groups/1/values").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"small\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.groupId").value(1)).andExpect(jsonPath("$.groupName").value("size"));
        var request=ArgumentCaptor.forClass(OptionValueRequest.class);verify(options).addOptionValue(eq(1L),request.capture());assertThat(request.getValue().getName()).isEqualTo("small");
        mvc.perform(put("/api/v1/option-groups/values/2").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"small\"}")).andExpect(status().isOk());verify(options).updateOptionValue(eq(2L),any());
        mvc.perform(delete("/api/v1/option-groups/values/2")).andExpect(status().isNoContent());verify(options).deleteOptionValue(2L);
    }

    @Test void givenMultipartImage__whenUploaded__thenReturnOriginalImageUrlEnvelope() throws Exception {
        when(images.uploadImage(any())).thenReturn("https://assets.invalid/products/photo.png");
        var image=new MockMultipartFile("file","photo.png","image/png",new byte[]{1});
        mvc.perform(multipart("/api/v1/images").file(image)).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value("https://assets.invalid/products/photo.png"));
        verify(images).uploadImage(any());
    }

    @Test void givenCategoryWriteRequests__whenDispatched__thenKeepOriginalMethodsStatusesAndTreeShape() throws Exception {
        var child=CategoryResponse.builder().id(2L).name("dry").parentId(1L).children(List.of()).build();
        var response=CategoryResponse.builder().id(1L).name("food").description("food category").children(List.of(child)).build();
        when(categories.createCategory(any())).thenReturn(response);when(categories.updateCategory(eq(1L),any())).thenReturn(response);
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"food\",\"description\":\"food category\",\"parentId\":5}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(1)).andExpect(jsonPath("$.parentId").isEmpty())
                .andExpect(jsonPath("$.children[0].parentId").value(1)).andExpect(jsonPath("$.children[0].children").isEmpty());
        var created=ArgumentCaptor.forClass(CategoryCreateRequest.class);verify(categories).createCategory(created.capture());
        assertThat(created.getValue().getName()).isEqualTo("food");assertThat(created.getValue().getDescription()).isEqualTo("food category");assertThat(created.getValue().getParentId()).isEqualTo(5L);
        mvc.perform(put("/api/v1/categories/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"food\",\"parentId\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("food"));
        var updated=ArgumentCaptor.forClass(CategoryUpdateRequest.class);verify(categories).updateCategory(eq(1L),updated.capture());assertThat(updated.getValue().getParentId()).isNull();
        mvc.perform(delete("/api/v1/categories/1")).andExpect(status().isNoContent()).andExpect(content().string(""));verify(categories).deleteCategory(1L);
    }

    @Test void givenCategoryBusinessFailures__whenMapped__thenKeepOriginal400InputAnd500StateErrorCodes() throws Exception {
        when(categories.updateCategory(eq(1L),any())).thenThrow(new IllegalArgumentException("synthetic self parent"));
        mvc.perform(put("/api/v1/categories/1").contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C001")).andExpect(jsonPath("$.errors").isEmpty());
        doThrow(new IllegalStateException("synthetic child refusal")).when(categories).deleteCategory(1L);
        mvc.perform(delete("/api/v1/categories/1")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("C004"));
        mvc.perform(delete("/api/v1/categories/abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C005"));
    }

    @Test void givenOriginalBusinessFailures__whenMapped__thenKeep400InputAnd500StateErrorCodes() throws Exception {
        when(products.createProduct(any())).thenThrow(new IllegalArgumentException("synthetic invalid input"));
        mvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"food\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C001")).andExpect(jsonPath("$.errors").isEmpty());
        doThrow(new IllegalStateException("synthetic cart refusal")).when(products).deleteProduct(10L);
        mvc.perform(delete("/api/v1/products/10")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("C004"));
    }
}
