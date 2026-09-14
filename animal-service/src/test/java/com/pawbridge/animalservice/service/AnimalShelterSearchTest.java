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
            assertThat(q.isBool()).isTrue();
            assertThat(q.bool().minimumShouldMatch()).isEqualTo("1");
            assertThat(q.bool().should()).anySatisfy(evidence -> {
                assertThat(evidence.isMatchPhrase()).isTrue();
                assertThat(evidence.matchPhrase().field()).isEqualTo("special_mark");
                assertThat(evidence.matchPhrase().boost()).isEqualTo(9.0f);
            });
            assertThat(q.bool().should()).anySatisfy(evidence -> {
                assertThat(evidence.isMultiMatch()).isTrue();
                assertThat(evidence.multiMatch().query()).isEqualTo("푸들");
                assertThat(evidence.multiMatch().type())
                        .isEqualTo(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.MostFields);
                assertThat(evidence.multiMatch().fields()).contains("description^4", "color^5");
                assertThat(evidence.multiMatch().minimumShouldMatch()).isEqualTo("70%");
            });
            assertThat(q.bool().should())
                    .filteredOn(evidence -> evidence.isMultiMatch())
                    .allSatisfy(evidence -> assertThat(evidence.multiMatch().minimumShouldMatch()).isEqualTo("70%"));
            assertThat(q.bool().should()).anySatisfy(evidence -> {
                assertThat(evidence.isMultiMatch()).isTrue();
                assertThat(evidence.multiMatch().fuzziness()).isEqualTo("AUTO");
                assertThat(evidence.multiMatch().boost()).isEqualTo(0.5f);
            });
            assertThat(q.bool().should())
                    .noneMatch(evidence -> evidence.isTerm()
                            && (evidence.term().field().equals("apms_notice_no")
                            || evidence.term().field().equals("apms_desertion_no")));
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
    void givenNoticeNumber__whenSearch__thenRequireExactNoticeNumberWithoutTextScoring() throws Exception {
        emptyResult();
        AnimalFacade facade = mock(AnimalFacade.class);
        when(facade.searchAnimals(any(), any())).thenAnswer(a -> service.searchAnimals(a.getArgument(0), a.getArgument(1)));
        var mvc = MockMvcBuilders.standaloneSetup(new AnimalController(facade))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();

        mvc.perform(get("/api/v1/animals").param("noticeNo", "  경남-사천-2026-00027  "))
                .andExpect(status().isOk());

        var capture = ArgumentCaptor.forClass(NativeQuery.class);
        verify(operations).search(capture.capture(), eq(AnimalDocument.class));
        var query = capture.getValue().getQuery().bool();
        assertThat(query.must()).isEmpty();
        assertThat(query.filter()).noneMatch(filter -> filter.isTerms()
                && filter.terms().field().equals("status"));
        assertThat(query.filter()).anySatisfy(filter -> {
            assertThat(filter.isTerm()).isTrue();
            assertThat(filter.term().field()).isEqualTo("apms_notice_no");
            assertThat(filter.term().value().stringValue()).isEqualTo("경남-사천-2026-00027");
        });
    }

    @Test
    void givenBreedTypoAndRelevanceSort__whenSearch__thenPreferExactBreedAndKeepFuzzyFallback() {
        emptyResult();

        service.searchAnimals(AnimalSearchRequest.builder().breed("마티즈").build(),
                PageRequest.of(0, 21, Sort.by(Sort.Direction.DESC, "relevance")));

        var capture = ArgumentCaptor.forClass(NativeQuery.class);
        verify(operations).search(capture.capture(), eq(AnimalDocument.class));
        var breedQuery = capture.getValue().getQuery().bool().must().get(0).bool();
        assertThat(breedQuery.minimumShouldMatch()).isEqualTo("1");
        assertThat(breedQuery.should()).anySatisfy(evidence -> {
            assertThat(evidence.isTerm()).isTrue();
            assertThat(evidence.term().field()).isEqualTo("breed.keyword");
            assertThat(evidence.term().boost()).isEqualTo(10.0f);
        });
        assertThat(breedQuery.should()).anySatisfy(evidence -> {
            assertThat(evidence.isMatch()).isTrue();
            assertThat(evidence.match().field()).isEqualTo("breed");
            assertThat(evidence.match().fuzziness()).isEqualTo("AUTO");
            assertThat(evidence.match().boost()).isEqualTo(0.5f);
        });
        assertThat(capture.getValue().getSortOptions().get(0).isScore()).isTrue();
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
    void givenKeywordAndRelevanceSort__whenSearch__thenOrderByScoreRecencyAndStableId() {
        emptyResult();

        service.searchAnimals(AnimalSearchRequest.builder().keyword("흰색 말티즈 오른쪽 귀 검정").build(),
                PageRequest.of(0, 21, Sort.by(Sort.Direction.DESC, "relevance")));

        var capture = ArgumentCaptor.forClass(NativeQuery.class);
        verify(operations).search(capture.capture(), eq(AnimalDocument.class));
        var sortOptions = capture.getValue().getSortOptions();
        assertThat(sortOptions).hasSize(3);
        assertThat(sortOptions.get(0).isScore()).isTrue();
        assertThat(sortOptions.get(0).score().order()).isEqualTo(co.elastic.clients.elasticsearch._types.SortOrder.Desc);
        assertThat(sortOptions.get(1).field().field()).isEqualTo("created_at");
        assertThat(sortOptions.get(2).field().field()).isEqualTo("id");
    }

    @Test
    void givenNoKeywordAndRelevanceSort__whenSearch__thenRejectBeforeElasticsearch() {
        assertThatThrownBy(() -> service.searchAnimals(AnimalSearchRequest.builder().build(),
                PageRequest.of(0, 21, Sort.by(Sort.Direction.DESC, "relevance"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("키워드나 품종");
        verifyNoInteractions(operations);
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
