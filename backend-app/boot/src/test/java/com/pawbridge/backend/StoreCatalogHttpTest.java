package com.pawbridge.backend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration;
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog;
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogController;
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogExceptionHandler;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties="pawbridge.store-catalog.enabled=true",
        excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={StoreCatalogController.class,StoreCatalogExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreCatalogHttpTest {
    private static final List<String> PATHS=List.of("/api/v1/products","/api/v1/products/8","/api/v1/categories",
            "/api/v1/option-groups","/api/v1/option-groups/8");
    @Autowired MockMvc mvc;
    @MockitoBean StoreCatalog catalog;

    static Stream<Arguments> routesAndRoles() {
        return PATHS.stream().flatMap(path -> Stream.of(Arguments.of(path,"missing"),
                Arguments.of(path,"ROLE_USER"),Arguments.of(path,"ROLE_ADMIN"),Arguments.of(path,"badBearer")));
    }

    @ParameterizedTest @MethodSource("routesAndRoles")
    void given__catalog_enabled__when__reading_each_route__then__preserve_public_get_for_anonymous_user_admin_and_unrelated_bad_bearer(String path,String role) throws Exception {
        when(catalog.search(any())).thenReturn(new StoreCatalog.SearchResult(List.of(),0L,0,0,false));
        when(catalog.product(8)).thenReturn(new StoreCatalog.Product(8L,"synthetic",null,null,"ACTIVE",null,null,0L,null,null,List.of()));
        when(catalog.categories()).thenReturn(List.of());
        when(catalog.optionGroups()).thenReturn(List.of());
        when(catalog.optionGroup(8)).thenReturn(new StoreCatalog.OptionGroup(8L,"synthetic",List.of()));
        var request=get(path).header("X-User-Id","99").header("X-User-Role","ROLE_ADMIN");
        if (role.equals("badBearer")) request.header("Authorization","Bearer unrelated-invalid-token");
        else if (!role.equals("missing")) request.header("Authorization","Bearer "+F3TestInputs.token(7,role));
        mvc.perform(request).andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        {
            switch(path) {
                case "/api/v1/products" -> verify(catalog).search(new StoreCatalog.Search(null,null,null,null,false,null,"desc",0,20));
                case "/api/v1/products/8" -> verify(catalog).product(8);
                case "/api/v1/categories" -> verify(catalog).categories();
                case "/api/v1/option-groups" -> verify(catalog).optionGroups();
                case "/api/v1/option-groups/8" -> verify(catalog).optionGroup(8);
                default -> throw new AssertionError("Unknown synthetic route");
            }
            verifyNoMoreInteractions(catalog);
        }
    }

    @ParameterizedTest @CsvSource({"POST,/api/v1/products","PUT,/api/v1/products/8","POST,/api/v1/categories",
            "POST,/api/v1/option-groups","DELETE,/api/v1/option-groups/8"})
    void given__verified_admin__when__attempting_unported_writes__then__deny_without_catalog_call(String method,String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method),path).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(catalog);
    }

    @ParameterizedTest @ValueSource(strings={"missing","badBearer"})
    void given__an_existing_protected_user_route__when__jwt_is_missing_or_invalid__then__keep_unauthorized_before_service(String credentials) throws Exception {
        var request=get("/api/v1/users/me").header("X-User-Id","99").header("X-User-Role","ROLE_ADMIN");
        if (credentials.equals("badBearer")) request.header("Authorization","Bearer unrelated-invalid-token");
        mvc.perform(request).andExpect(status().isUnauthorized());
        verifyNoInteractions(catalog);
    }

    @ParameterizedTest @ValueSource(strings={"/api/v1/products/8/extra","/api/v1/categories/8","/api/v1/option-groups/8/extra","/api/products"})
    void given__verified_admin__when__requesting_unexposed_neighbors_or_gateway_alias__then__deny_without_catalog_call(String path) throws Exception {
        mvc.perform(get(path).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(catalog);
    }
}

@WebMvcTest(excludeAutoConfiguration=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes={StoreCatalogController.class,StoreCatalogExceptionHandler.class,
        ReadSecurityConfiguration.class,F3TestInputs.SecurityInputs.class})
class StoreCatalogDisabledHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean StoreCatalog catalog;
    @ParameterizedTest @ValueSource(strings={"/api/v1/products","/api/v1/products/8","/api/v1/categories","/api/v1/option-groups","/api/v1/option-groups/8"})
    void given__no_explicit_enablement__when__verified_admin_reads_each_route__then__deny_before_service(String path) throws Exception {
        mvc.perform(get(path).header("Authorization","Bearer "+F3TestInputs.token(7,"ROLE_ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(catalog);
    }
}
