package com.pawbridge.backend;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.store.internal.catalog.application.CatalogSearchUnavailableException;
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog;
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogController;
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogExceptionHandler;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogOptions;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.JpaCatalogProducts;
import com.pawbridge.backend.store.internal.catalogwrite.infrastructure.S3ProductImages;
import com.pawbridge.backend.store.internal.catalogwrite.web.*;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual ReadSecurity/JWT filter and scoped advice; only Store business collaborators are mocked. */
@WebMvcTest(properties={"pawbridge.store-catalog-management.enabled=true","pawbridge.store-catalog.enabled=false"},
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ProductWriteController.class,OptionManagementController.class,ProductImageController.class,
        CatalogManagementExceptionHandler.class,StoreCatalogController.class,StoreCatalogExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreCatalogManagementHttpTest {
    record Route(String method,String path,int status,String body){}
    static final List<Route> WRITES=List.of(
            new Route("POST","/api/v1/products",201,"{\"name\":\"food\",\"skus\":[]}"),
            new Route("PATCH","/api/v1/products/8",200,"{\"name\":\"patched\",\"status\":\"HIDDEN\"}"),
            new Route("DELETE","/api/v1/products/8",204,"{}"),
            new Route("POST","/api/v1/option-groups",201,"{\"name\":\"size\",\"values\":[\"small\"]}"),
            new Route("PUT","/api/v1/option-groups/2",200,"{\"name\":\"size\"}"),
            new Route("DELETE","/api/v1/option-groups/2",204,"{}"),
            new Route("POST","/api/v1/option-groups/2/values",201,"{\"name\":\"small\"}"),
            new Route("PUT","/api/v1/option-groups/values/3",200,"{\"name\":\"small\"}"),
            new Route("DELETE","/api/v1/option-groups/values/3",204,"{}"),
            new Route("POST","/api/v1/images",200,""));
    static final List<String> READS=List.of("/api/v1/products","/api/v1/products/8","/api/v1/categories",
            "/api/v1/option-groups","/api/v1/option-groups/2");
    @Autowired MockMvc mvc;
    @MockitoBean JpaCatalogProducts products;
    @MockitoBean JpaCatalogOptions options;
    @MockitoBean S3ProductImages images;
    @MockitoBean StoreCatalog catalog;

    static Stream<Route> writes(){return WRITES.stream();}
    static Stream<String> reads(){return READS.stream();}
    static Stream<Arguments> deniedCredentials(){return WRITES.stream().flatMap(route->Stream.of("ROLE_USER","missing","invalid")
            .map(role->Arguments.of(route,role)));}

    @AfterEach void given__request_finished__when__security_scope_checked__then__retain_no_principal(){
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @ParameterizedTest @MethodSource("writes")
    void given__enabled_management_and_signed_admin__when__using_each_canonical_write__then__reach_only_its_original_handler(Route route) throws Exception {
        stubWriteResponses(products,options,images);
        var result=mvc.perform(command(route).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN"))
                        .header("X-User-Id","99").header("X-User-Role","ROLE_USER"))
                .andExpect(status().is(route.status())).andExpect(header().doesNotExist("Set-Cookie"));
        switch(route.method()+" "+route.path()) {
            case "POST /api/v1/products"->{result.andExpect(jsonPath("$.id").value(8)).andExpect(jsonPath("$.status").value("ACTIVE"));verify(products).createProduct(argThat(body->"food".equals(body.getName())));}
            case "PATCH /api/v1/products/8"->{result.andExpect(jsonPath("$.name").value("patched"));verify(products).updateProduct(eq(8L),argThat(body->"patched".equals(body.getName())));}
            case "DELETE /api/v1/products/8"->{result.andExpect(content().string(""));verify(products).deleteProduct(8L);}
            case "POST /api/v1/option-groups"->{result.andExpect(jsonPath("$.id").value(2));verify(options).createOptionGroup(argThat(body->"size".equals(body.getName())&&body.getValues().equals(List.of("small"))));}
            case "PUT /api/v1/option-groups/2"->{result.andExpect(jsonPath("$.name").value("size"));verify(options).updateOptionGroup(eq(2L),argThat(body->"size".equals(body.getName())));}
            case "DELETE /api/v1/option-groups/2"->verify(options).deleteOptionGroup(2L);
            case "POST /api/v1/option-groups/2/values"->{result.andExpect(jsonPath("$.groupId").value(2));verify(options).addOptionValue(eq(2L),argThat(body->"small".equals(body.getName())));}
            case "PUT /api/v1/option-groups/values/3"->{result.andExpect(jsonPath("$.id").value(3));verify(options).updateOptionValue(eq(3L),argThat(body->"small".equals(body.getName())));}
            case "DELETE /api/v1/option-groups/values/3"->verify(options).deleteOptionValue(3L);
            case "POST /api/v1/images"->{result.andExpect(jsonPath("$.imageUrl").value("https://fixture.invalid/products/upload.png"));verify(images).uploadImage(argThat(file->"photo.png".equals(file.getOriginalFilename())&&file.getSize()==3));}
            default->throw new AssertionError("Unknown catalog write contract");
        }
        assertThat(result.andReturn().getRequest().getSession(false)).isNull();verifyNoMoreInteractions(products,options,images,catalog);
    }

    @ParameterizedTest @MethodSource("deniedCredentials")
    void given__user_or_missing_invalid_jwt_with_forged_admin_headers__when__using_each_write__then__deny_before_store_business(Route route,String credentials) throws Exception {
        var call=command(route).header("X-User-Id","99").header("X-User-Role","ROLE_ADMIN");
        if(credentials.equals("ROLE_USER"))call.header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_USER"));
        else if(credentials.equals("invalid"))call.header("Authorization","Bearer invalid-token");
        mvc.perform(call).andExpect(status().is(credentials.equals("ROLE_USER")?403:401)).andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(products,options,images,catalog);
    }

    @ParameterizedTest @CsvSource({
            "POST,/api/products","PATCH,/api/products/8","DELETE,/api/products/8","POST,/api/images",
            "POST,/api/option-groups","PUT,/api/option-groups/2","DELETE,/api/option-groups/2",
            "POST,/api/option-groups/2/values","PUT,/api/option-groups/values/3","DELETE,/api/option-groups/values/3",
            "PUT,/api/v1/products/8","POST,/api/v1/products/8","POST,/api/v1/products/8/skus",
            "PATCH,/api/v1/option-groups/2","DELETE,/api/v1/option-groups","PUT,/api/v1/images",
            "GET,/api/v1/images","POST,/api/v1/categories","POST,/api/v1/products/8/extra"})
    void given__signed_admin__when__using_gateway_alias_or_unowned_method_neighbor__then__retain_default_deny(String method,String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method),path).contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());verifyNoInteractions(products,options,images,catalog);
    }

    @ParameterizedTest @MethodSource("reads")
    void given__management_enabled_but_public_catalog_disabled__when__admin_reads_catalog__then__do_not_widen_public_get_scope(String path) throws Exception {
        mvc.perform(get(path).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());verifyNoInteractions(products,options,images,catalog);
    }

    @Test void given__authorized_business_refusal__when__advice_handles_it__then__preserve_original_store_errors_outside_authentication() throws Exception {
        when(products.createProduct(any())).thenThrow(new IllegalArgumentException("synthetic input refusal"));
        mvc.perform(command(WRITES.getFirst()).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("C001"));
        doThrow(new IllegalStateException("synthetic cart refusal")).when(products).deleteProduct(8L);
        mvc.perform(command(WRITES.get(2)).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("C004"));
        verify(products).createProduct(any());verify(products).deleteProduct(8L);verifyNoMoreInteractions(products,options,images,catalog);
    }

    static MockHttpServletRequestBuilder command(Route route){
        if(route.path().equals("/api/v1/images"))return multipart(route.path()).file(new MockMultipartFile("file","photo.png","image/png",new byte[]{1,2,3}));
        return request(HttpMethod.valueOf(route.method()),route.path()).contentType(MediaType.APPLICATION_JSON).content(route.body());
    }
    static void stubWriteResponses(JpaCatalogProducts products,JpaCatalogOptions options,S3ProductImages images){
        when(products.createProduct(any())).thenReturn(ProductResponse.builder().id(8L).name("food").status("ACTIVE").skus(List.of()).build());
        when(products.updateProduct(eq(8L),any())).thenReturn(ProductResponse.builder().id(8L).name("patched").status("HIDDEN").skus(List.of()).build());
        var group=OptionGroupResponse.builder().id(2L).name("size").values(List.of()).build();
        when(options.createOptionGroup(any())).thenReturn(group);when(options.updateOptionGroup(eq(2L),any())).thenReturn(group);
        var value=OptionValueResponse.builder().id(3L).name("small").groupId(2L).groupName("size").build();
        when(options.addOptionValue(eq(2L),any())).thenReturn(value);when(options.updateOptionValue(eq(3L),any())).thenReturn(value);
        when(images.uploadImage(any())).thenReturn("https://fixture.invalid/products/upload.png");
    }
}

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ProductWriteController.class,OptionManagementController.class,ProductImageController.class,
        CatalogManagementExceptionHandler.class,StoreCatalogController.class,StoreCatalogExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreCatalogManagementDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean JpaCatalogProducts products;@MockitoBean JpaCatalogOptions options;@MockitoBean S3ProductImages images;@MockitoBean StoreCatalog catalog;
    @ParameterizedTest @MethodSource("com.pawbridge.backend.StoreCatalogManagementHttpTest#writes")
    void given__management_default_off__when__signed_admin_uses_each_canonical_write__then__deny_before_business(StoreCatalogManagementHttpTest.Route route) throws Exception {
        mvc.perform(StoreCatalogManagementHttpTest.command(route).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());verifyNoInteractions(products,options,images,catalog);
    }
}

@WebMvcTest(properties="pawbridge.store-catalog-management.enabled=on",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ProductWriteController.class,OptionManagementController.class,ProductImageController.class,
        CatalogManagementExceptionHandler.class,StoreCatalogController.class,StoreCatalogExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreCatalogManagementAliasDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean JpaCatalogProducts products;@MockitoBean JpaCatalogOptions options;@MockitoBean S3ProductImages images;@MockitoBean StoreCatalog catalog;
    @ParameterizedTest @MethodSource("com.pawbridge.backend.StoreCatalogManagementHttpTest#writes")
    void given__boolean_alias_instead_of_literal_true__when__admin_uses_each_write__then__keep_management_closed(StoreCatalogManagementHttpTest.Route route) throws Exception {
        mvc.perform(StoreCatalogManagementHttpTest.command(route).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());verifyNoInteractions(products,options,images,catalog);
    }
}

@WebMvcTest(properties={"pawbridge.store-catalog.enabled=true","pawbridge.store-catalog-management.enabled=false"},
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={ProductWriteController.class,OptionManagementController.class,ProductImageController.class,
        CatalogManagementExceptionHandler.class,StoreCatalogController.class,StoreCatalogExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreCatalogManagementReadOnlyHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean JpaCatalogProducts products;@MockitoBean JpaCatalogOptions options;@MockitoBean S3ProductImages images;@MockitoBean StoreCatalog catalog;
    @ParameterizedTest @MethodSource("com.pawbridge.backend.StoreCatalogManagementHttpTest#writes")
    void given__only_public_catalog_enabled__when__admin_attempts_each_management_write__then__deny_before_business(StoreCatalogManagementHttpTest.Route route) throws Exception {
        mvc.perform(StoreCatalogManagementHttpTest.command(route).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());verifyNoInteractions(products,options,images,catalog);
    }

    @ParameterizedTest @MethodSource("com.pawbridge.backend.StoreCatalogManagementHttpTest#reads")
    void given__public_catalog_without_management__when__anonymous_reads_with_unrelated_bad_bearer__then__keep_public_get_and_existing_read_advice(String path) throws Exception {
        when(catalog.search(any())).thenReturn(new StoreCatalog.SearchResult(List.of(),0L,0,0,false));
        when(catalog.product(8)).thenReturn(new StoreCatalog.Product(8L,"food",null,null,"ACTIVE",null,null,0L,null,null,List.of()));
        when(catalog.categories()).thenReturn(List.of());when(catalog.optionGroups()).thenReturn(List.of());
        when(catalog.optionGroup(2)).thenReturn(new StoreCatalog.OptionGroup(2L,"size",List.of()));
        mvc.perform(get(path).header("Authorization","Bearer unrelated-invalid-token").header("X-User-Role","ROLE_ADMIN"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        switch(path){
            case "/api/v1/products"->verify(catalog).search(new StoreCatalog.Search(null,null,null,null,false,null,"desc",0,20));
            case "/api/v1/products/8"->verify(catalog).product(8);
            case "/api/v1/categories"->verify(catalog).categories();
            case "/api/v1/option-groups"->verify(catalog).optionGroups();
            case "/api/v1/option-groups/2"->verify(catalog).optionGroup(2);
            default->throw new AssertionError("Unknown catalog read contract");
        }
        verifyNoMoreInteractions(catalog);verifyNoInteractions(products,options,images);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void given__catalog_search_unavailable__when__public_get_fails__then__use_read_advice_without_management_error_capture() throws Exception {
        when(catalog.search(any())).thenThrow(new CatalogSearchUnavailableException());
        mvc.perform(get("/api/v1/products")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("P006"));
        verify(catalog).search(any());verifyNoMoreInteractions(catalog);verifyNoInteractions(products,options,images);
    }
}
