package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.controller.AnimalController;
import com.pawbridge.animalservice.document.AnimalDocument;
import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.exception.GlobalExceptionHandler;
import com.pawbridge.animalservice.facade.AnimalFacade;
import com.pawbridge.animalservice.mapper.AnimalDocumentMapper;
import com.pawbridge.animalservice.repository.AnimalDocumentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnimalShelterSearchTest {
    private final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
    private final AnimalElasticsearchService service = new AnimalElasticsearchService(operations,
            mock(AnimalDocumentRepository.class), mock(AnimalDocumentMapper.class));

    @SuppressWarnings("unchecked")
    private void emptyResult() {
        SearchHits<AnimalDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of());
        when(operations.search(any(NativeQuery.class), eq(AnimalDocument.class))).thenReturn(hits);
    }

    @Test
    void givenShelterIdInHttpQuery__whenSearch__thenUseKeywordForScoringAndExactConditionsAsFilters() throws Exception {
        emptyResult();
        AnimalFacade facade = mock(AnimalFacade.class);
        when(facade.searchAnimals(any(), any())).thenAnswer(a -> service.searchAnimals(a.getArgument(0), a.getArgument(1)));
        var mvc = MockMvcBuilders.standaloneSetup(new AnimalController(facade))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();

        mvc.perform(get("/api/v1/animals").param("shelterId", "71").param("species", "DOG")
                .param("keyword", "푸들")).andExpect(status().isOk());

        var capture = ArgumentCaptor.forClass(NativeQuery.class);
        verify(operations).search(capture.capture(), eq(AnimalDocument.class));
        var query = capture.getValue().getQuery().bool();
        assertThat(query.must()).singleElement().satisfies(q -> {
            assertThat(q.isMultiMatch()).isTrue();
            assertThat(q.multiMatch().query()).isEqualTo("푸들");
        });
        assertThat(query.filter()).anySatisfy(q -> {
            assertThat(q.isTerm()).isTrue();
            assertThat(q.term().field()).isEqualTo("shelter_id");
            assertThat(q.term().value().longValue()).isEqualTo(71L);
        });
        assertThat(query.filter()).anySatisfy(q -> {
            assertThat(q.isTerm()).isTrue();
            assertThat(q.term().field()).isEqualTo("species");
            assertThat(q.term().value().stringValue()).isEqualTo("DOG");
        });
    }

    @Test
    void givenNoShelterId__whenSearch__thenDoNotRestrictToShelter() {
        emptyResult();

        service.searchAnimals(AnimalSearchRequest.builder().build(), PageRequest.of(0, 12));

        var capture = ArgumentCaptor.forClass(NativeQuery.class);
        verify(operations).search(capture.capture(), eq(AnimalDocument.class));
        assertThat(capture.getValue().getQuery().bool().filter())
                .noneMatch(q -> q.isTerm() && q.term().field().equals("shelter_id"));
    }

    @Test
    void givenAgeAscending__whenSearch__thenSortByNewestBirthYearFirstWithStableId() {
        emptyResult();

        service.searchAnimals(AnimalSearchRequest.builder().build(),
                PageRequest.of(0, 21, Sort.by(Sort.Direction.ASC, "age")));

        var capture = ArgumentCaptor.forClass(NativeQuery.class);
        verify(operations).search(capture.capture(), eq(AnimalDocument.class));
        assertThat(capture.getValue().getPageable().getSort().toList())
                .containsExactly(
                        new Sort.Order(Sort.Direction.DESC, "birth_year"),
                        new Sort.Order(Sort.Direction.ASC, "id")
                );
    }

    @Test
    void givenUnsupportedSortInHttpQuery__whenSearch__thenReturnBadRequestWithoutCallingElasticsearch() throws Exception {
        AnimalFacade facade = mock(AnimalFacade.class);
        when(facade.searchAnimals(any(), any())).thenAnswer(a -> service.searchAnimals(a.getArgument(0), a.getArgument(1)));
        var mvc = MockMvcBuilders.standaloneSetup(new AnimalController(facade))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();

        mvc.perform(get("/api/v1/animals").param("sort", "unknown,asc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(operations);
    }

    @Test
    void givenUnsupportedSort__whenSearch__thenRejectBeforeElasticsearch() {
        assertThatThrownBy(() -> service.searchAnimals(AnimalSearchRequest.builder().build(),
                PageRequest.of(0, 21, Sort.by("unknown"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 정렬 기준");
        verifyNoInteractions(operations);
    }

    @Test
    void givenPageBeyondElasticsearchWindow__whenSearch__thenRejectBeforeElasticsearch() {
        assertThatThrownBy(() -> service.searchAnimals(AnimalSearchRequest.builder().build(),
                PageRequest.of(476, 21, Sort.by(Sort.Direction.DESC, "createdAt"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10,000건");
        verifyNoInteractions(operations);
    }

    @Test
    void givenLastPageInsideElasticsearchWindow__whenSearch__thenExecuteSearch() {
        emptyResult();

        service.searchAnimals(AnimalSearchRequest.builder().build(),
                PageRequest.of(475, 21, Sort.by(Sort.Direction.DESC, "createdAt")));

        verify(operations).search(any(NativeQuery.class), eq(AnimalDocument.class));
    }

    @Test
    void givenOversizedPage__whenSearch__thenRejectBeforeElasticsearch() {
        assertThatThrownBy(() -> service.searchAnimals(AnimalSearchRequest.builder().build(),
                PageRequest.of(0, 101, Sort.by(Sort.Direction.DESC, "createdAt"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100 이하");
        verifyNoInteractions(operations);
    }
}
