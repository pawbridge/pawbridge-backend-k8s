package com.pawbridge.storeservice.domain.product.service;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgresqlSearchSelectionTest {
    @Test void postgres_selects_only_the_database_search_adapter() {
        new ApplicationContextRunner().withPropertyValues("pawbridge.search.backend=postgresql")
            .withUserConfiguration(PostgresqlProductSearchService.class,ProductSearchService.class)
            .withBean(javax.sql.DataSource.class,()->mock(javax.sql.DataSource.class))
            .withBean(com.pawbridge.storeservice.search.KoreanSearchTerms.class)
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ProductSearch.class)
                .hasSingleBean(PostgresqlProductSearchService.class).doesNotHaveBean(ProductSearchService.class));
    }
    @Test void unavailable_search_returns_503_instead_of_an_empty_success() throws Exception {
        ProductSearch search=mock(ProductSearch.class);
        when(search.searchProducts(any())).thenThrow(new com.pawbridge.storeservice.search.SearchUnavailableException());
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new com.pawbridge.storeservice.domain.product.controller.ProductController(mock(ProductService.class),search,
                mock(com.pawbridge.storeservice.domain.product.facade.ProductFacade.class)))
            .setControllerAdvice(new com.pawbridge.storeservice.common.exception.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/products").param("keyword","사료"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("P006"));
    }
}
