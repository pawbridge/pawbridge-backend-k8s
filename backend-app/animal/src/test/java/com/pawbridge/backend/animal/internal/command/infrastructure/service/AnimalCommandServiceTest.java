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
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
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
        when(manager.getTransaction(any())).thenReturn(transaction);
        when(actors.find(11L)).thenReturn(new Actor(11L, "ROLE_SHELTER", "care-7", true, false));
        when(animals.findById(42L)).thenReturn(Optional.of(animal));
        when(shelters.findByCareRegNo("care-7")).thenReturn(Optional.of(shelter));
        when(animals.save(any())).thenAnswer(invocation -> {
            Animal created = invocation.getArgument(0);
            ReflectionTestUtils.setField(created, "id", 43L);
            return created;
        });
        service = service(json);
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
        verify(animals,never()).countByApmsNoticeNoStartingWith(any());
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
        when(animals.findById(42L)).thenReturn(Optional.empty());
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
