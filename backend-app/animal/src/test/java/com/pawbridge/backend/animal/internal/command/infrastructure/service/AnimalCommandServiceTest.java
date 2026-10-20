package com.pawbridge.backend.animal.internal.command.infrastructure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.animal.api.AnimalCommandActors;
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor;
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.*;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.*;
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.*;
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.*;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnimalCommandServiceTest {
    final AnimalRepository animals = mock(AnimalRepository.class);
    final ShelterRepository shelters = mock(ShelterRepository.class);
    final OutboxEventRepository events = mock(OutboxEventRepository.class);
    final AnimalCommandActors actors = mock(AnimalCommandActors.class);
    final SearchDocumentWriter search = mock(SearchDocumentWriter.class);
    final JpaTransactionManager manager = mock(JpaTransactionManager.class);
    final TransactionStatus transaction = mock(TransactionStatus.class);
    final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    final Shelter shelter = Shelter.builder().id(7L).careRegNo("care-7").name("보호소").build();
    final Animal animal = Animal.builder().id(42L).shelter(shelter).species(Species.DOG)
            .status(AnimalStatus.PROTECT).apiSource(ApiSource.APMS_ANIMAL).apmsNoticeNo("기존-공고").build();
    AnimalCommandService service;

    @BeforeEach void setUp() {
        DataSource source = mock(DataSource.class);
        when(manager.getDataSource()).thenReturn(source);
        when(search.dataSource()).thenReturn(source);
        // Metadata-only transaction fixture: repositories and manager remain mocks, with no JDBC resource.
        when(manager.getTransaction(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            TransactionDefinition definition=invocation.getArgument(0);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                    definition.getIsolationLevel()==TransactionDefinition.ISOLATION_DEFAULT ? null : definition.getIsolationLevel());
            return transaction;
        });
        doAnswer(invocation -> { clearMockTransactionMetadata(); return null; }).when(manager).commit(transaction);
        doAnswer(invocation -> { clearMockTransactionMetadata(); return null; }).when(manager).rollback(transaction);
        when(actors.find(11L)).thenReturn(new Actor(11L, "ROLE_SHELTER", "care-7", true, false));
        when(animals.findByIdForUpdate(42L)).thenReturn(Optional.of(animal));
        when(shelters.findByCareRegNo("care-7")).thenReturn(Optional.of(shelter));
        when(animals.save(any())).thenAnswer(invocation -> {
            Animal created = invocation.getArgument(0);
            ReflectionTestUtils.setField(created, "id", 43L);
            return created;
        });
        service = service(json);
    }

    @AfterEach void transaction_metadata_does_not_leak() {
        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull();
        } finally {
            clearMockTransactionMetadata();
        }
    }

    void clearMockTransactionMetadata() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
    }

    TransactionTemplate transactionWithIsolation(int isolation) {
        var template=new TransactionTemplate(manager);
        template.setIsolationLevel(isolation);
        return template;
    }

    AnimalCommandService service(ObjectMapper mapper) {
        return new AnimalCommandService(search, animals, shelters, new AnimalMapper(),
                new OutboxService(events, mapper), new NoticeNumberGenerator(animals), actors, manager);
    }

    CreateAnimalRequest request() {
        return CreateAnimalRequest.builder().careRegNo("care-7").species(Species.DOG)
                .gender(Gender.MALE).neuterStatus(NeuterStatus.NO).status(AnimalStatus.PROTECT)
                .noticeStartDate(LocalDate.of(2026,10,20)).noticeEndDate(LocalDate.of(2026,10,30))
                .breed("믹스견").description("보호 중").build();
    }

    OutboxEvent event() { var captured=ArgumentCaptor.forClass(OutboxEvent.class); verify(events).save(captured.capture()); return captured.getValue(); }

    @Test void create_defaults_manual_and_refreshes_search_before_original_outbox() throws Exception {
        var response = service.create(11L, request());
        assertThat(response.getId()).isEqualTo(43L);
        assertThat(response.getApiSource()).isEqualTo(ApiSource.MANUAL);
        assertThat(response.getApmsNoticeNo()).matches("MAN-\\d{6}-000001");
        var order=inOrder(actors, animals, search, events, manager);
        order.verify(actors).find(11L); order.verify(animals).save(any()); order.verify(animals).flush();
        order.verify(search).animal(43L); order.verify(events).save(any()); order.verify(manager).commit(transaction);
        var event=event();var payload=json.readTree(event.getPayload());
        assertThat(event.getAggregateType()).isEqualTo("Animal"); assertThat(event.getAggregateId()).isEqualTo("43");
        assertThat(event.getEventType()).isEqualTo("ANIMAL_CREATED");assertThat(event.getTopic()).isEqualTo("animal.created");
        assertThat(payload.get("shelterId").asLong()).isEqualTo(7);
        assertThat(payload.get("eventId").asText()).isNotEqualTo(event.getEventId());
    }

    @Test void administrator_can_create_for_other_shelter_and_preserve_apms_source_and_notice() {
        when(actors.find(11L)).thenReturn(new Actor(11L,"ROLE_ADMIN","another",true,false));
        var request=request();request.setApiSource(ApiSource.APMS_ANIMAL); request.setApmsNoticeNo("APMS-공고");
        var result=service.create(11L,request);
        assertThat(result.getApiSource()).isEqualTo(ApiSource.APMS_ANIMAL);
        assertThat(result.getApmsNoticeNo()).isEqualTo("APMS-공고");
        verify(animals,never()).findMaxManualNoticeSuffix(anyString(),anyString());
        verify(animals,never()).lockManualNoticePrefix(anyInt(),anyInt());
    }

    static Stream<Arguments> nonManualSourcesAndMissingNotices() {
        return Arrays.stream(ApiSource.values()).filter(source -> source != ApiSource.MANUAL)
                .flatMap(source -> Stream.<String>of(null, "", " \t\n")
                        .map(notice -> Arguments.of(source, notice)));
    }

    @ParameterizedTest @MethodSource("nonManualSourcesAndMissingNotices")
    void non_manual_without_notice_is_rejected_before_animal_search_outbox_or_generated_number(ApiSource source, String notice) {
        var request=request();request.setApiSource(source);request.setApmsNoticeNo(notice);
        var mapping=spy(new AnimalMapper());
        var numbers=spy(new NoticeNumberGenerator(animals));
        var command=new AnimalCommandService(search,animals,shelters,mapping,
                new OutboxService(events,json),numbers,actors,manager);

        assertThatThrownBy(()->command.create(11L,request)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수동 등록 외 데이터는 공고번호가 필요합니다.");
        assertThat(request.getApiSource()).isEqualTo(source);
        assertThat(request.getApmsNoticeNo()).isEqualTo(notice);
        verifyNoInteractions(animals,events,mapping,numbers);
        verify(search,never()).animal(anyLong());
        verify(manager).rollback(transaction);verify(manager,never()).commit(any());
    }

    static Stream<Arguments> manualSourcesAndMissingNotices() {
        return Stream.<ApiSource>of(null,ApiSource.MANUAL)
                .flatMap(source -> Stream.<String>of(null,""," \t\n")
                        .map(notice -> Arguments.of(source,notice)));
    }

    @ParameterizedTest @MethodSource("manualSourcesAndMissingNotices")
    void default_or_explicit_manual_without_notice_still_generates_notice(ApiSource source,String notice) {
        var request=request();request.setApiSource(source);request.setApmsNoticeNo(notice);
        var result=service.create(11L,request);
        assertThat(result.getApiSource()).isEqualTo(ApiSource.MANUAL);
        assertThat(result.getApmsNoticeNo()).matches("MAN-\\d{6}-000001");
        verify(animals).findMaxManualNoticeSuffix(anyString(),anyString());
        verify(manager).commit(transaction);
    }

    @ParameterizedTest @EnumSource(ApiSource.class)
    void supplied_notice_preserves_each_source_without_generating_replacement(ApiSource source) {
        var request=request();request.setApiSource(source);request.setApmsNoticeNo("supplied-notice-7");
        var result=service.create(11L,request);
        assertThat(result.getApiSource()).isEqualTo(source);
        assertThat(result.getApmsNoticeNo()).isEqualTo("supplied-notice-7");
        verify(animals,never()).findMaxManualNoticeSuffix(anyString(),anyString());
        verify(animals,never()).lockManualNoticePrefix(anyInt(),anyInt());
        verify(manager).commit(transaction);
    }

    @Test void changed_status_response_contains_timestamp_updated_during_flush() {
        var before=LocalDateTime.of(2026,10,20,10,0);
        var after=before.plusMinutes(1);
        ReflectionTestUtils.setField(animal,"updatedAt",before);
        doAnswer(invocation -> {
            ReflectionTestUtils.setField(animal,"updatedAt",after);
            return null;
        }).when(animals).flush();
        var request=new UpdateAnimalStatusRequest();request.setNewStatus(AnimalStatus.ADOPTED);

        var result=service.updateStatus(11L,42L,request);

        assertThat(result.getStatus()).isEqualTo(AnimalStatus.ADOPTED);
        assertThat(result.getUpdatedAt()).isEqualTo(after).isNotEqualTo(before);
        verify(events).save(any());verify(manager).commit(transaction);
        verify(search,never()).animal(anyLong());
    }

    @Test void status_flush_failure_rolls_back_and_does_not_map_or_return_success_response() {
        var mapping=spy(new AnimalMapper());
        var command=new AnimalCommandService(search,animals,shelters,mapping,
                new OutboxService(events,json),new NoticeNumberGenerator(animals),actors,manager);
        var failure=new org.springframework.dao.DataIntegrityViolationException("synthetic flush failure");
        doThrow(failure).when(animals).flush();
        var request=new UpdateAnimalStatusRequest();request.setNewStatus(AnimalStatus.ADOPTED);

        assertThatThrownBy(()->command.updateStatus(11L,42L,request)).isSameAs(failure);

        verify(events).save(any());
        verify(mapping,never()).toDetailResponse(any());
        verify(manager).rollback(transaction);verify(manager,never()).commit(any());
        verify(search,never()).animal(anyLong());
    }

    @Test void status_updates_apms_animal_and_emits_original_payload_even_when_status_is_unchanged() throws Exception {
        var request=new UpdateAnimalStatusRequest(); request.setNewStatus(AnimalStatus.PROTECT);
        var response=service.updateStatus(11L,42L,request);
        assertThat(response.getStatus()).isEqualTo(AnimalStatus.PROTECT);
        var event=event();var payload=json.readTree(event.getPayload());
        assertThat(event.getTopic()).isEqualTo("animal.status.changed");
        assertThat(payload.get("oldStatus").asText()).isEqualTo("PROTECT");
        assertThat(payload.get("newStatus").asText()).isEqualTo("PROTECT");
        assertThat(payload.get("animalName").asText()).isEqualTo("기존-공고");
        verify(search,never()).animal(anyLong()); verify(manager).commit(transaction);
    }

    @Test void description_updates_apms_animal_and_refreshes_search_before_outbox() throws Exception {
        var request=new UpdateAnimalDescriptionRequest();request.setDescription("새 설명");
        assertThat(service.updateDescription(11L,42L,request).getDescription()).isEqualTo("새 설명");
        var event=event();assertThat(event.getTopic()).isEqualTo("animal.updated");
        assertThat(json.readTree(event.getPayload()).get("updatedFields").get("description").asText()).isEqualTo("새 설명");
        var order=inOrder(animals,search,events,manager);order.verify(animals).flush();order.verify(search).animal(42L);
        order.verify(events).save(any());order.verify(manager).commit(transaction);
    }

    @Test void delete_checks_current_owner_and_has_no_new_outbox_or_search_cleanup() {
        service.delete(11L,42L);verify(animals).deleteById(42L);verifyNoInteractions(events);
        verify(search,never()).animal(anyLong());verify(manager).commit(transaction);
    }

    static Stream<Actor> deniedActors() {
        return Stream.of(null, new Actor(11L,"ROLE_USER","care-7",true,false),
                new Actor(11L,"ROLE_ADMIN",null,false,false),new Actor(11L,"ROLE_ADMIN",null,true,true),
                new Actor(12L,"ROLE_ADMIN",null,true,false),new Actor(11L,null,"care-7",true,false),
                new Actor(11L,"ROLE_SHELTER",null,true,false));
    }

    @ParameterizedTest @MethodSource("deniedActors")
    void unavailable_or_unauthorized_current_actor_cannot_create(Actor actor) {
        when(actors.find(11L)).thenReturn(actor);
        assertThatThrownBy(()->service.create(11L,request())).isInstanceOf(AnimalCommandForbiddenException.class);
        verifyNoInteractions(animals,shelters,events); verify(search,never()).animal(anyLong());
        verify(manager).rollback(transaction);
    }

    @ParameterizedTest @ValueSource(strings={"ADMIN","SHELTER"})
    void unprefixed_role_aliases_are_denied(String role) {
        when(actors.find(11L)).thenReturn(new Actor(11L,role,"care-7",true,false));
        assertThatThrownBy(()->service.create(11L,request())).isInstanceOf(AnimalCommandForbiddenException.class);
        verifyNoInteractions(animals,shelters,events);verify(search,never()).animal(anyLong());
        verify(manager).rollback(transaction);
    }

    @ParameterizedTest @ValueSource(strings={"create","status","description","delete"})
    void another_shelter_cannot_mutate_any_command(String command) {
        when(actors.find(11L)).thenReturn(new Actor(11L,"ROLE_SHELTER","other-care",true,false));
        assertThatThrownBy(()->invoke(command,11L)).isInstanceOf(AnimalCommandForbiddenException.class);
        verify(animals,never()).save(any());verify(animals,never()).deleteById(any());verifyNoInteractions(events);
        verify(search,never()).animal(anyLong());assertThat(animal.getDescription()).isNull();
        assertThat(animal.getStatus()).isEqualTo(AnimalStatus.PROTECT);verify(manager).rollback(transaction);
    }

    @Test void missing_principal_cannot_invoke_delete() {
        assertThatThrownBy(()->service.delete(null,42L)).isInstanceOf(AnimalCommandForbiddenException.class);
        verifyNoInteractions(actors,animals,events);verify(manager).rollback(transaction);
    }

    @ParameterizedTest @ValueSource(strings={"status","description","delete"})
    void missing_animal_keeps_original_entity_not_found_failure(String command) {
        when(animals.findByIdForUpdate(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(()->invoke(command,11L)).isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(events);verify(manager).rollback(transaction);
    }

    @Test void missing_shelter_keeps_original_failure_before_save() {
        when(shelters.findByCareRegNo("care-7")).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.create(11L,request())).isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(animals,events);verify(manager).rollback(transaction);
    }

    @Test void search_failure_rolls_back_without_recording_outbox() {
        doThrow(new IllegalStateException("projection failed")).when(search).animal(43L);
        assertThatThrownBy(()->service.create(11L,request())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(events);verify(manager).rollback(transaction);verify(manager,never()).commit(any());
    }

    @Test void outbox_serialization_failure_rolls_back_the_writer_transaction() throws Exception {
        ObjectMapper failing=mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("serialize"){});
        assertThatThrownBy(()->service(failing).create(11L,request())).isInstanceOf(RuntimeException.class).hasMessage("Failed to serialize event payload");
        verifyNoInteractions(events);verify(manager).rollback(transaction);verify(manager,never()).commit(any());
    }

    @Test void delete_fk_failure_propagates_and_rolls_back() {
        doThrow(new org.springframework.dao.DataIntegrityViolationException("referenced")).when(animals).deleteById(42L);
        assertThatThrownBy(()->service.delete(11L,42L)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verify(manager).rollback(transaction);verifyNoInteractions(events);
    }

    @Test void search_and_jpa_using_different_writer_sources_fail_before_work() {
        when(manager.getDataSource()).thenReturn(mock(DataSource.class));
        assertThatThrownBy(()->service(json)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings={"status","description","delete"})
    void owned_mutations_take_user_then_animal_write_lock_and_never_read_unlocked(String command) {
        invoke(command,11L);
        var order=inOrder(actors,animals);
        order.verify(actors).find(11L);
        order.verify(animals).findByIdForUpdate(42L);
        verify(animals,never()).findById(any());
        verify(manager).commit(transaction);
    }

    @Test void notice_uses_live_max_after_middle_gap_and_same_day_advisory_lock_before_max() {
        when(animals.findMaxManualNoticeSuffix(anyString(),anyString())).thenReturn(3);
        var number=transactionWithIsolation(TransactionDefinition.ISOLATION_READ_COMMITTED)
                .execute(status -> new NoticeNumberGenerator(animals).generate());
        assertThat(number).matches("MAN-\\d{6}-000004");
        String prefix=number.substring(0,number.length()-6);
        int day=Integer.parseInt(number.substring(4,10));
        var order=inOrder(animals);
        order.verify(animals).lockManualNoticePrefix(0x5042414E,day);
        order.verify(animals).findMaxManualNoticeSuffix(prefix+"%","^"+prefix+"([0-9]{6})$");
        verify(animals,never()).save(any());verify(animals,never()).flush();
        verifyNoInteractions(events);verify(search,never()).animal(anyLong());
        verify(manager).commit(transaction);
    }

    @Test void exhausted_six_digit_suffix_fails_without_emitting_a_seven_digit_number() {
        when(animals.findMaxManualNoticeSuffix(anyString(),anyString())).thenReturn(999999);
        assertThatThrownBy(() -> transactionWithIsolation(TransactionDefinition.ISOLATION_READ_COMMITTED)
                .execute(status -> new NoticeNumberGenerator(animals).generate()))
                .isInstanceOf(IllegalStateException.class).hasMessage("수동 등록 공고번호의 일일 한도를 초과했습니다.");
        verify(animals,never()).save(any());verify(animals,never()).flush();
        verifyNoInteractions(events);verify(search,never()).animal(anyLong());
        verify(manager).rollback(transaction);verify(manager,never()).commit(any());
    }

    @Test void notice_generation_outside_transaction_fails_before_any_repository_call() {
        assertThatThrownBy(() -> new NoticeNumberGenerator(animals).generate())
                .isInstanceOf(IllegalStateException.class).hasMessage("수동 공고번호는 쓰기 트랜잭션 안에서 생성해야 합니다.");
        verifyNoInteractions(animals,events);verify(search,never()).animal(anyLong());
    }

    @ParameterizedTest @ValueSource(ints={-1,1,4,8})
    void notice_generation_without_explicit_read_committed_is_rejected_before_lock_or_max(int isolation) {
        assertThatThrownBy(() -> transactionWithIsolation(isolation)
                .execute(status -> new NoticeNumberGenerator(animals).generate()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("수동 공고번호에는 명시적인 READ_COMMITTED 격리 수준이 필요합니다.");
        verifyNoInteractions(animals,events);verify(search,never()).animal(anyLong());
        verify(manager).rollback(transaction);verify(manager,never()).commit(any());
    }

    @Test void notice_generation_in_read_only_transaction_is_rejected_before_lock_or_max() {
        var template=transactionWithIsolation(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(true);
        assertThatThrownBy(() -> template.execute(status -> new NoticeNumberGenerator(animals).generate()))
                .isInstanceOf(IllegalStateException.class).hasMessage("수동 공고번호는 쓰기 트랜잭션 안에서 생성해야 합니다.");
        verifyNoInteractions(animals,events);verify(search,never()).animal(anyLong());
        verify(manager).rollback(transaction);verify(manager,never()).commit(any());
    }

    void invoke(String command,Long userId) {
        switch(command) {
            case "create"->service.create(userId,request());
            case "status"->{var request=new UpdateAnimalStatusRequest();request.setNewStatus(AnimalStatus.ADOPTED);service.updateStatus(userId,42L,request);}
            case "description"->{var request=new UpdateAnimalDescriptionRequest();request.setDescription("수정");service.updateDescription(userId,42L,request);}
            case "delete"->service.delete(userId,42L);
            default->throw new AssertionError(command);
        }
    }
}
