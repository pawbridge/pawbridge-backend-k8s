package com.pawbridge.backend.animal.internal.sheltercommand.infrastructure;

import com.pawbridge.backend.animal.api.*;
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import com.pawbridge.backend.animal.internal.sheltercommand.application.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JpaShelterCommandsTest {
    final EntityManager entities = mock(EntityManager.class);
    final JpaTransactionManager manager = mock(JpaTransactionManager.class);
    final TransactionStatus status = mock(TransactionStatus.class);
    final SearchDocumentWriter search = mock(SearchDocumentWriter.class);
    final AnimalCommandActors actors = mock(AnimalCommandActors.class);
    final AnimalShelterMembers members = mock(AnimalShelterMembers.class);
    final Query lock = mock(Query.class);
    final TypedQuery<Long> animals = mock(TypedQuery.class);
    final Shelter shelter = Shelter.builder().id(7L).careRegNo("care-7").name("보호소").phone("old phone")
            .address("서울").ownerName("대표").organizationName("기관").email("old@example.test")
            .introduction("old intro").adoptionProcedure("old adoption").operatingHours("old hours").build();
    TransactionTemplate writes;
    JpaShelterCommands commands;
    final ShelterCommands.Create create = new ShelterCommands.Create("care-new", " 새 보호소 ", "02", "주소", "대표", "기관",
            "new@example.test", "소개", "절차", "09-18");
    final ShelterCommands.Update update = new ShelterCommands.Update("new phone", "new@example.test", "new intro", "new adoption", "new hours");

    @BeforeEach void setUp() {
        DataSource source = mock(DataSource.class);
        when(manager.getDataSource()).thenReturn(source);
        when(search.dataSource()).thenReturn(source);
        when(manager.getTransaction(any())).thenReturn(status);
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        commands = new JpaShelterCommands(entities, manager, writes, search, actors, members);
        clearInvocations(search);
        when(actors.find(10L)).thenReturn(new Actor(10L, "ROLE_ADMIN", null, true, false));
        when(entities.createNativeQuery("SELECT id FROM pawbridge_animal.shelters WHERE id = :id FOR UPDATE")).thenReturn(lock);
        when(lock.setParameter("id",7L)).thenReturn(lock);
        when(lock.getResultList()).thenReturn(List.of(7L));
        when(entities.find(Shelter.class, 7L)).thenReturn(shelter);
        when(entities.createQuery("select a.id from Animal a where a.shelter.id = :shelterId", Long.class)).thenReturn(animals);
        when(animals.setParameter("shelterId", 7L)).thenReturn(animals);
        when(animals.setMaxResults(1)).thenReturn(animals);
        when(animals.getResultList()).thenReturn(List.of());
    }
    @Test void givenAdministrator__whenCreate__thenPreserveEveryOriginalFieldAndFlushBeforeSameTransactionSearch() {
        LocalDateTime created = LocalDateTime.of(2026, 10, 21, 3, 0);
        doAnswer(call -> { Shelter value = call.getArgument(0);ReflectionTestUtils.setField(value,"id",9L);
            ReflectionTestUtils.setField(value,"createdAt",created);ReflectionTestUtils.setField(value,"updatedAt",created);return null;
        }).when(entities).persist(any(Shelter.class));
        var result = commands.create(10L, create);
        assertThat(result).isEqualTo(new ShelterCommands.Details(9L,"care-new"," 새 보호소 ","02","주소","대표","기관",
                "new@example.test","소개","절차","09-18",created,created));
        var order = inOrder(actors, entities, search, manager);
        order.verify(actors).find(10L);order.verify(entities).persist(any(Shelter.class));order.verify(entities).flush();
        order.verify(search).shelter(9L);order.verify(manager).commit(status);
        verifyNoInteractions(members);
    }
    @Test void givenSearchFailure__whenCreate__thenPropagateAndRequestWriterRollbackWithoutCommit() {
        doThrow(new IllegalStateException("search")).when(search).shelter(anyLong());
        doAnswer(call -> {Shelter saved = call.getArgument(0); ReflectionTestUtils.setField(saved,"id",9L);return null;}).when(entities).persist(any());
        assertThatThrownBy(() -> commands.create(10L, create)).isInstanceOf(IllegalStateException.class).hasMessage("search");
        verify(manager).rollback(status);verify(manager,never()).commit(any());
    }
    @Test void givenOwnShelterMember__whenUpdate__thenLockUserBeforeShelterAndReplaceOnlyFiveManagedFields() {
        when(actors.find(10L)).thenReturn(new Actor(10L,"ROLE_SHELTER","care-7",true,false));
        var result = commands.update(10L,7L,update);
        assertThat(result.phone()).isEqualTo("new phone");assertThat(result.email()).isEqualTo("new@example.test");
        assertThat(result.introduction()).isEqualTo("new intro");assertThat(result.adoptionProcedure()).isEqualTo("new adoption");
        assertThat(result.operatingHours()).isEqualTo("new hours");
        assertThat(result.careRegNo()).isEqualTo("care-7");assertThat(result.name()).isEqualTo("보호소");
        assertThat(result.address()).isEqualTo("서울");assertThat(result.ownerName()).isEqualTo("대표");
        assertThat(result.organizationName()).isEqualTo("기관");
        var order=inOrder(actors,entities);order.verify(actors).find(10L);order.verify(entities).find(Shelter.class,7L);
        order.verify(entities).flush();verifyNoInteractions(search,members);
    }
    @Test void givenAdministratorWithForeignCareNumber__whenUpdate__thenAllowEveryShelter() {
        when(actors.find(10L)).thenReturn(new Actor(10L,"ROLE_ADMIN","other",true,false));
        assertThat(commands.update(10L,7L,update).phone()).isEqualTo("new phone");
    }
    @Test void givenOmittedNullableFields__whenUpdate__thenClearAllFiveAsOriginalPutContract() {
        var result=commands.update(10L,7L,new ShelterCommands.Update(null,null,null,null,null));
        assertThat(result.phone()).isNull();assertThat(result.email()).isNull();assertThat(result.introduction()).isNull();
        assertThat(result.adoptionProcedure()).isNull();assertThat(result.operatingHours()).isNull();
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","care-8","CARE-7"})
    void givenForeignOrMissingMembership__whenUpdate__thenRejectWithoutMutatingShelter(String care) {
        when(actors.find(10L)).thenReturn(new Actor(10L,"ROLE_SHELTER",care,true,false));
        assertThatThrownBy(()->commands.update(10L,7L,update)).isInstanceOf(ShelterCommandForbiddenException.class);
        assertThat(shelter.getPhone()).isEqualTo("old phone");verifyNoInteractions(search,members);
    }
    @ParameterizedTest @ValueSource(strings={"ROLE_USER","ROLE_SUPER_ADMIN","ADMIN","SHELTER"})
    void givenUnapprovedRole__whenAnyCommand__thenRejectBeforeLookingUpShelter(String role) {
        when(actors.find(10L)).thenReturn(new Actor(10L,role,"care-7",true,false));
        assertThatThrownBy(()->commands.create(10L,create)).isInstanceOf(ShelterCommandForbiddenException.class);
        assertThatThrownBy(()->commands.update(10L,7L,update)).isInstanceOf(ShelterCommandForbiddenException.class);
        assertThatThrownBy(()->commands.delete(10L,7L)).isInstanceOf(ShelterCommandForbiddenException.class);
        verifyNoInteractions(entities,search,members);
    }
    @ParameterizedTest @ValueSource(strings={"missing","inactive","pending","wrong-id"})
    void givenInvalidCurrentAccount__whenUpdate__thenRejectBeforeShelterLock(String kind) {
        when(actors.find(10L)).thenReturn(kind.equals("missing")?null:new Actor(kind.equals("wrong-id")?11L:10L,
                "ROLE_ADMIN",null,!kind.equals("inactive"),kind.equals("pending")));
        assertThatThrownBy(()->commands.update(10L,7L,update)).isInstanceOf(ShelterCommandForbiddenException.class);
        verifyNoInteractions(entities,search,members);
    }
    @Test void givenUnverifiedIdentity__whenAnyCommand__thenNeverResolveUserOrShelter() {
        assertThatThrownBy(()->commands.create(null,create)).isInstanceOf(ShelterCommandForbiddenException.class);
        assertThatThrownBy(()->commands.update(0L,7L,update)).isInstanceOf(ShelterCommandForbiddenException.class);
        assertThatThrownBy(()->commands.delete(-1L,7L)).isInstanceOf(ShelterCommandForbiddenException.class);
        verifyNoInteractions(actors,entities,search,members);
    }
    @Test void givenShelterRole__whenCreateOrDelete__thenRejectBeforeTargetLookup() {
        when(actors.find(10L)).thenReturn(new Actor(10L,"ROLE_SHELTER","care-7",true,false));
        assertThatThrownBy(()->commands.create(10L,create)).isInstanceOf(ShelterCommandForbiddenException.class);
        assertThatThrownBy(()->commands.delete(10L,7L)).isInstanceOf(ShelterCommandForbiddenException.class);
        verifyNoInteractions(entities,search,members);
    }
    @Test void givenConnectedAnimal__whenDelete__thenConflictWithoutDeletingOrCheckingMembers() {
        when(animals.getResultList()).thenReturn(List.of(3L));
        assertThatThrownBy(()->commands.delete(10L,7L)).isInstanceOf(ShelterCommandConflictException.class);
        verify(animals).setMaxResults(1);verify(entities,never()).remove(any());verifyNoInteractions(members,search);
    }
    @Test void givenConnectedMember__whenDelete__thenReadAfterShelterLockAndConflictWithoutDeletion() {
        when(members.exists("care-7")).thenReturn(true);
        assertThatThrownBy(()->commands.delete(10L,7L)).isInstanceOf(ShelterCommandConflictException.class);
        var order=inOrder(actors,entities,animals,members);order.verify(actors).find(10L);
        order.verify(entities).find(Shelter.class,7L);
        order.verify(animals).getResultList();order.verify(members).exists("care-7");
        verify(entities,never()).remove(any());verifyNoInteractions(search);
    }
    @Test void givenNoReferences__whenDelete__thenRemoveOnlyLockedShelterAndCommit() {
        commands.delete(10L,7L);
        var order=inOrder(actors,entities,members,manager);order.verify(actors).find(10L);
        order.verify(entities).find(Shelter.class,7L);
        order.verify(members).exists("care-7");order.verify(entities).remove(shelter);order.verify(manager).commit(status);
        verifyNoInteractions(search);
    }
    @Test void givenMissingShelter__whenUpdateOrDelete__thenKeepOriginalJpaMissingException() {
        when(entities.find(Shelter.class,7L)).thenReturn(null);
        assertThatThrownBy(()->commands.update(10L,7L,update)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(()->commands.delete(10L,7L)).isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(members,search);
    }
    @ParameterizedTest @ValueSource(strings={"read-only","repeatable","default-isolation","requires-new","other-manager"})
    void givenIncompatibleTransactionTemplate__whenConstruct__thenReject(String kind) {
        switch(kind) {
            case "read-only" -> writes.setReadOnly(true);
            case "repeatable" -> writes.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            case "default-isolation" -> writes.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
            case "requires-new" -> writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            case "other-manager" -> writes.setTransactionManager(mock(JpaTransactionManager.class));
        }
        assertThatThrownBy(()->new JpaShelterCommands(entities,manager,writes,search,actors,members)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void givenCurrentAdministrator__whenDelete__thenNativeForUpdateExcludesApprovalBeforeAnyEntityOrReferenceRead() {
        commands.delete(10L,7L);
        var order=inOrder(actors,entities,lock,animals,members);
        order.verify(actors).find(10L);
        order.verify(entities).createNativeQuery("SELECT id FROM pawbridge_animal.shelters WHERE id = :id FOR UPDATE");
        order.verify(lock).setParameter("id",7L);order.verify(lock).getResultList();
        order.verify(entities).find(Shelter.class,7L);order.verify(animals).getResultList();
        order.verify(members).exists("care-7");order.verify(entities).remove(shelter);
    }
    @Test void givenNativeTargetAbsent__whenUpdateOrDelete__thenRejectBeforeManagedLoadOrReferences() {
        when(lock.getResultList()).thenReturn(List.of());
        assertThatThrownBy(()->commands.update(10L,7L,update)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(()->commands.delete(10L,7L)).isInstanceOf(EntityNotFoundException.class);
        verify(entities,never()).find(Shelter.class,7L);verify(entities,never()).remove(any());
        verifyNoInteractions(members,search,animals);
    }
    @Test void givenNativeLockFailure__whenUpdate__thenPropagateAndRollbackBeforeLoadingEntity() {
        doThrow(new LockTimeoutException("lock timeout")).when(lock).getResultList();
        assertThatThrownBy(()->commands.update(10L,7L,update)).isInstanceOf(LockTimeoutException.class);
        verify(entities,never()).find(Shelter.class,7L);verify(manager).rollback(status);
        assertThat(shelter.getPhone()).isEqualTo("old phone");verifyNoInteractions(search,members,animals);
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7,8,9})
    void givenSupplementaryTextInAnOriginalCreateColumn__whenCreatingAfterAdministratorCheck__thenRejectBeforeSqlAndRollback(int column) {
        String[] values={create.careRegNo(),create.name(),create.phone(),create.address(),create.ownerName(),create.organizationName(),
                create.email(),create.introduction(),create.adoptionProcedure(),create.operatingHours()};
        values[column]="unsupported-\uD83D\uDE80-text";
        var request=new ShelterCommands.Create(values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],values[8],values[9]);
        assertThatThrownBy(()->commands.create(10L,request)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(com.pawbridge.backend.animal.internal.command.infrastructure.persistence.PostgresqlRollbackCharsetViolation.MESSAGE);
        verify(actors).find(10L);verify(entities,never()).persist(any());verify(entities,never()).flush();
        verifyNoInteractions(search,members);verify(manager).rollback(status);verify(manager,never()).commit(any());
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4})
    void givenSupplementaryTextInAnOriginalUpdateColumn__whenUpdatingAfterAuthorityAndTargetLock__thenKeepFieldsAndRejectBeforeSql(int column) {
        String[] values={update.phone(),update.email(),update.introduction(),update.adoptionProcedure(),update.operatingHours()};
        values[column]="unsupported-\uD83D\uDE80-text";
        String previous=shelter.getIntroduction();
        assertThatThrownBy(()->commands.update(10L,7L,new ShelterCommands.Update(values[0],values[1],values[2],values[3],values[4])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(com.pawbridge.backend.animal.internal.command.infrastructure.persistence.PostgresqlRollbackCharsetViolation.MESSAGE);
        verify(actors).find(10L);verify(lock).getResultList();verify(entities).find(Shelter.class,7L);
        assertThat(shelter.getIntroduction()).isEqualTo(previous);
        verify(entities,never()).persist(any());verify(entities,never()).flush();verifyNoInteractions(search,members);
        verify(manager).rollback(status);verify(manager,never()).commit(any());
    }

}
