package com.pawbridge.communityservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostgresqlSearchSelectionTest {
    @Test void postgres_does_not_start_es_index_creation_consumer_handler_or_scheduler() {
        new ApplicationContextRunner().withPropertyValues("pawbridge.search.backend=postgresql")
            .withUserConfiguration(PostgresqlSearchService.class,SearchServiceImpl.class,
                com.pawbridge.communityservice.config.ElasticsearchConfig.class,
                com.pawbridge.communityservice.kafka.PostEventConsumer.class,
                com.pawbridge.communityservice.kafka.PostEventHandler.class,
                com.pawbridge.communityservice.scheduler.SyncScheduler.class)
            .withBean(DataSource.class,()->mock(DataSource.class))
            .withBean(com.pawbridge.communityservice.search.KoreanSearchTerms.class)
            .withBean(com.pawbridge.communityservice.client.UserServiceClient.class,()->mock(com.pawbridge.communityservice.client.UserServiceClient.class))
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(SearchService.class).hasSingleBean(PostgresqlSearchService.class);
                assertThat(context).doesNotHaveBean(SearchServiceImpl.class)
                    .doesNotHaveBean(com.pawbridge.communityservice.config.ElasticsearchConfig.class)
                    .doesNotHaveBean(com.pawbridge.communityservice.kafka.PostEventConsumer.class)
                    .doesNotHaveBean(com.pawbridge.communityservice.kafka.PostEventHandler.class)
                    .doesNotHaveBean(com.pawbridge.communityservice.scheduler.SyncScheduler.class);
            });
    }
    @Test void search_unavailability_and_invalid_input_have_distinct_http_statuses() throws Exception {
        SearchService search=mock(SearchService.class);
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new com.pawbridge.communityservice.controller.SearchController(search))
            .setControllerAdvice(new com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice()).build();
        when(search.searchPosts("준비중")).thenThrow(new com.pawbridge.communityservice.exception.SearchServiceUnavailableException());
        when(search.searchPosts("잘못된입력")).thenThrow(new com.pawbridge.communityservice.search.InvalidSearchInputException("invalid"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/posts/search").param("keyword","준비중"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/posts/search").param("keyword","잘못된입력"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
}
