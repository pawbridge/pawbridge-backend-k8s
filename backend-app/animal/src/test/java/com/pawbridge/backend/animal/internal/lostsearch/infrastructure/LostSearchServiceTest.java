package com.pawbridge.backend.animal.internal.lostsearch.infrastructure;

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter;
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus;
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species;
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.form.FormData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LostSearchServiceTest {
    static final String KEY="synthetic-lost-search-private-key";
    static final String EVIDENCE="REGISTERED_DESCRIPTION_TEXT_MATCH";
    final PythonLostSearchClient client=mock(PythonLostSearchClient.class);
    final AnimalRepository animals=mock(AnimalRepository.class);
    final JpaTransactionManager reader=mock(JpaTransactionManager.class);
    final TransactionStatus transaction=mock(TransactionStatus.class);
    final AnimalMapper mapper=new AnimalMapper();
    LostSearchService service;

    @BeforeEach void setUp() {
        when(reader.getTransaction(any())).thenAnswer(invocation->{
            assertThat(((TransactionDefinition)invocation.getArgument(0)).isReadOnly()).isTrue();
            return transaction;
        });
        when(client.search(anyString(),any(),anyString(),nullable(String.class),nullable(String.class),nullable(String.class),anyBoolean()))
                .thenReturn(new PythonLostSearchResponse(List.of()));
        service=new LostSearchService(client,animals,mapper,KEY,reader);
    }

    LostSearchRequest request() {
        var request=new LostSearchRequest();request.setSpecies(Species.DOG);
        request.setImage(new MockMultipartFile("image","untrusted-name.png","image/png","image-bytes".getBytes(StandardCharsets.UTF_8)));
        return request;
    }
    PythonLostSearchResponse.Candidate candidate(long id) { return new PythonLostSearchResponse.Candidate(id,0.85,List.of(EVIDENCE)); }
    Animal animal(long id,Species species,AnimalStatus status) {
        var shelter=Shelter.builder().id(70L).name("보호소").careRegNo("care-70").phone("02-0000-0000").build();
        return Animal.builder().id(id).species(species).status(status).shelter(shelter).description("현재 설명").build();
    }
    void respond(PythonLostSearchResponse response) {
        doReturn(response).when(client).search(anyString(),any(),anyString(),nullable(String.class),nullable(String.class),nullable(String.class),anyBoolean());
    }
    void expectStatus(int status,org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,error->{
            assertThat(error.getStatusCode().value()).isEqualTo(status);
            assertThat(error.getCause()).isNull();assertThat(error.toString()).doesNotContain(KEY);
        });
    }

    @Test void remote_call_precedes_reader_transaction_and_preserves_rank_dedup_first_evidence_and_current_data() {
        var request=request();request.setRegion("  서울  ");request.setDescription("  귀가 접힘  ");request.setLostDate(LocalDate.of(2026,10,20));
        when(client.search(anyString(),any(),anyString(),nullable(String.class),nullable(String.class),nullable(String.class),anyBoolean()))
                .thenAnswer(invocation->{
                    verifyNoInteractions(reader,animals);
                    assertThat(invocation.<String>getArgument(0)).isEqualTo(KEY);
                    FormData image=invocation.getArgument(1);assertThat(image.getFileName()).isEqualTo("photo");
                    assertThat(image.getContentType()).isEqualTo("application/octet-stream");
                    assertThat(image.getData()).isEqualTo("image-bytes".getBytes(StandardCharsets.UTF_8));
                    assertThat(invocation.<String>getArgument(3)).isEqualTo("2026-10-20");
                    assertThat(invocation.<String>getArgument(4)).isEqualTo("서울");
                    assertThat(invocation.<String>getArgument(5)).isEqualTo("귀가 접힘");
                    return new PythonLostSearchResponse(List.of(candidate(2),candidate(1),new PythonLostSearchResponse.Candidate(2L,.9,List.of("DISCOVERY_PLACE_TEXT_MATCH")),candidate(3),candidate(4),candidate(5)));
                });
        when(animals.findWithShelterByIdIn(List.of(2L,1L,3L,4L,5L))).thenReturn(List.of(
                animal(1,Species.DOG,AnimalStatus.PROTECT),animal(2,Species.DOG,AnimalStatus.NOTICE),
                animal(3,Species.CAT,AnimalStatus.NOTICE),animal(4,Species.DOG,AnimalStatus.ADOPTED)));
        var result=service.search(request);
        assertThat(result.candidates()).extracting(c->c.animal().getId()).containsExactly(2L,1L);
        assertThat(result.candidates().getFirst().animal().getDescription()).isEqualTo("현재 설명");
        assertThat(result.candidates().getFirst().shelterPhone()).isEqualTo("02-0000-0000");
        assertThat(result.candidates().getFirst().matchedEvidence()).containsExactly(EVIDENCE);
        verify(reader).commit(transaction);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void current_status_filter_preserves_active_and_optional_resolved_sets(boolean includeResolved) {
        var request=request();request.setIncludeAdoptedOrReturned(includeResolved);
        var statuses=List.of(AnimalStatus.values());
        respond(new PythonLostSearchResponse(IntStream.range(0,statuses.size()).mapToObj(i->candidate(i+1)).toList()));
        when(animals.findWithShelterByIdIn(any())).thenReturn(IntStream.range(0,statuses.size())
                .mapToObj(i->animal(i+1,Species.DOG,statuses.get(i))).toList());
        assertThat(service.search(request).candidates()).extracting(c->c.animal().getStatus())
                .containsExactlyElementsOf(includeResolved?List.of(AnimalStatus.NOTICE,AnimalStatus.PROTECT,AnimalStatus.ADOPTED,AnimalStatus.RETURNED)
                        :List.of(AnimalStatus.NOTICE,AnimalStatus.PROTECT));
    }

    @Test void empty_candidates_do_not_open_reader_or_query_database() {
        assertThat(service.search(request()).candidates()).isEmpty();verifyNoInteractions(reader,animals);
    }

    @Test void at_most_twenty_candidates_are_accepted_before_dedup_and_current_lookup() {
        respond(new PythonLostSearchResponse(Collections.nCopies(20,candidate(1))));
        when(animals.findWithShelterByIdIn(List.of(1L))).thenReturn(List.of(animal(1,Species.DOG,AnimalStatus.PROTECT)));
        assertThat(service.search(request()).candidates()).hasSize(1);
    }

    static Stream<PythonLostSearchResponse> invalidResponses() {
        return Stream.of(null,new PythonLostSearchResponse(null),new PythonLostSearchResponse(Collections.nCopies(21,new PythonLostSearchResponse.Candidate(1L,.5,List.of()))),
                new PythonLostSearchResponse(Arrays.asList((PythonLostSearchResponse.Candidate)null)),
                new PythonLostSearchResponse(List.of(new PythonLostSearchResponse.Candidate(null,.5,List.of()))),
                new PythonLostSearchResponse(List.of(new PythonLostSearchResponse.Candidate(0L,.5,List.of()))),
                new PythonLostSearchResponse(List.of(new PythonLostSearchResponse.Candidate(1L,null,List.of()))),
                new PythonLostSearchResponse(List.of(new PythonLostSearchResponse.Candidate(1L,Double.NaN,List.of()))),
                new PythonLostSearchResponse(List.of(new PythonLostSearchResponse.Candidate(1L,Double.POSITIVE_INFINITY,List.of()))),
                new PythonLostSearchResponse(List.of(new PythonLostSearchResponse.Candidate(1L,.5,null))),
                new PythonLostSearchResponse(List.of(new PythonLostSearchResponse.Candidate(1L,.5,Arrays.asList((String)null)))),
                new PythonLostSearchResponse(List.of(new PythonLostSearchResponse.Candidate(1L,.5,List.of("UNRECOGNIZED_EVIDENCE")))));
    }
    @ParameterizedTest @MethodSource("invalidResponses")
    void invalid_python_reply_is_503_and_releases_permit_without_database_access(PythonLostSearchResponse response) {
        respond(response);expectStatus(503,()->service.search(request()));
        respond(new PythonLostSearchResponse(List.of()));assertThat(service.search(request()).candidates()).isEmpty();
        verifyNoInteractions(reader,animals);
    }

    @ParameterizedTest @ValueSource(ints={400,422,413,429,503})
    void remote_error_classification_discards_sensitive_exception_and_releases_permit(int remoteStatus) {
        var request=Request.create(Request.HttpMethod.POST,"http://synthetic.invalid/internal/animals/lost-candidates",
                Map.of("X-Internal-Api-Key",List.of(KEY)),new byte[0],StandardCharsets.UTF_8,null);
        FeignException error=FeignException.errorStatus("search",Response.builder().request(request).status(remoteStatus)
                .reason(KEY).body(KEY,StandardCharsets.UTF_8).build());
        when(client.search(anyString(),any(),anyString(),nullable(String.class),nullable(String.class),nullable(String.class),anyBoolean())).thenThrow(error);
        expectStatus(remoteStatus==413?413:remoteStatus==400||remoteStatus==422?400:503,()->service.search(request()));
        respond(new PythonLostSearchResponse(List.of()));assertThat(service.search(request()).candidates()).isEmpty();
        verifyNoInteractions(reader,animals);
    }

    @ParameterizedTest @ValueSource(strings={"wrong-species","missing-image","empty-image","oversized-image"})
    void invalid_upload_is_rejected_before_getbytes_client_or_database(String kind) throws Exception {
        var request=request();int expected=400;
        switch(kind) {
            case "wrong-species"->request.setSpecies(Species.ETC);
            case "missing-image"->request.setImage(null);
            case "empty-image"->request.setImage(new MockMultipartFile("image",new byte[0]));
            case "oversized-image"->{var image=mock(MultipartFile.class);when(image.isEmpty()).thenReturn(false);
                when(image.getSize()).thenReturn(LostSearchService.MAX_IMAGE_BYTES+1);request.setImage(image);expected=413;}
        }
        expectStatus(expected,()->service.search(request));verifyNoInteractions(client,reader,animals);
        if(kind.equals("oversized-image"))verify(request.getImage(),never()).getBytes();
        assertThat(service.search(request()).candidates()).isEmpty();
    }

    @Test void exact_five_mib_file_is_accepted_and_null_optional_parts_remain_absent() {
        var request=request();request.setImage(new MockMultipartFile("image",new byte[(int)LostSearchService.MAX_IMAGE_BYTES]));
        assertThat(service.search(request).candidates()).isEmpty();
        verify(client).search(eq(KEY),any(),eq("DOG"),isNull(),isNull(),isNull(),eq(false));
    }

    @Test void blank_internal_key_fails_before_file_bytes_or_external_call() {
        var disabled=new LostSearchService(client,animals,mapper," ",reader);
        expectStatus(503,()->disabled.search(request()));verifyNoInteractions(client,reader,animals);
    }

    @Test void image_read_failure_is_400_and_releases_permit() throws Exception {
        var image=mock(MultipartFile.class);when(image.getSize()).thenReturn(1L);when(image.getBytes()).thenThrow(new IOException(KEY));
        var request=request();request.setImage(image);expectStatus(400,()->service.search(request));
        assertThat(service.search(request()).candidates()).isEmpty();verifyNoInteractions(reader,animals);
    }

    @Test void database_failure_rolls_back_reader_and_releases_permit() {
        respond(new PythonLostSearchResponse(List.of(candidate(1))));
        var failure=new org.springframework.dao.DataAccessResourceFailureException("synthetic database failure");
        when(animals.findWithShelterByIdIn(any())).thenThrow(failure);
        assertThatThrownBy(()->service.search(request())).isSameAs(failure);verify(reader).rollback(transaction);
        respond(new PythonLostSearchResponse(List.of()));assertThat(service.search(request()).candidates()).isEmpty();
    }

    @Test void concurrent_call_is_busy_without_second_python_request_and_permit_returns_after_completion() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(client.search(anyString(),any(),anyString(),nullable(String.class),nullable(String.class),nullable(String.class),anyBoolean()))
                .thenAnswer(invocation->{entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return new PythonLostSearchResponse(List.of());});
        try(var executor=Executors.newSingleThreadExecutor()) {
            Future<LostSearchResponse> running=executor.submit(()->service.search(request()));
            try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();expectStatus(503,()->service.search(request())); }
            finally { release.countDown(); }
            assertThat(running.get(5,TimeUnit.SECONDS).candidates()).isEmpty();
            assertThat(service.search(request()).candidates()).isEmpty();
        }
        verify(client,times(2)).search(anyString(),any(),anyString(),nullable(String.class),nullable(String.class),nullable(String.class),anyBoolean());
        verifyNoInteractions(reader,animals);
    }
}
