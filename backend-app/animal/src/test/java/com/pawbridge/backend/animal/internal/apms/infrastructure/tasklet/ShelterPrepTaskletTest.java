package com.pawbridge.backend.animal.internal.apms.infrastructure.tasklet;

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot;
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShelterPrepTaskletTest {
    @Test void givenSnapshotFailure__whenPreparingShelters__thenFailBeforeDatabaseAccess() {
        var snapshot=mock(ApmsAnimalSnapshot.class); var repository=mock(ShelterRepository.class);
        var search=mock(SearchDocumentWriter.class); var manager=new ObservedManager();
        when(snapshot.animals()).thenThrow(new IllegalStateException("provider error"));
        assertThatThrownBy(() -> task(snapshot,repository,search,manager).execute(mock(StepContribution.class),mock(ChunkContext.class))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository,search); assertThat(manager.begins).isZero();
    }
    @Test void givenEmptySnapshot__whenPreparingShelters__thenCompleteWithoutWriting() {
        var snapshot=mock(ApmsAnimalSnapshot.class); var repository=mock(ShelterRepository.class);
        var search=mock(SearchDocumentWriter.class); var manager=new ObservedManager();
        when(snapshot.animals()).thenReturn(List.of());
        assertThat(task(snapshot,repository,search,manager).execute(mock(StepContribution.class),mock(ChunkContext.class))).isEqualTo(RepeatStatus.FINISHED);
        verify(repository,never()).saveAll(any()); verifyNoInteractions(search);
        assertThat(manager.commits).isEqualTo(1); assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
    @Test @SuppressWarnings({"unchecked","rawtypes"})
    void givenOldAnimalWithNewShelterInUpdatedSnapshot__whenPreparing__thenFetchOutsideAndSaveAndSearchInsideOneExplicitTransaction() {
        var snapshot=mock(ApmsAnimalSnapshot.class); var repository=mock(ShelterRepository.class);
        var search=mock(SearchDocumentWriter.class); var manager=new ObservedManager(); manager.setDefaultTimeout(7);
        when(snapshot.animals()).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); assertThat(manager.begins).isZero();
            return List.of(animal("new-shelter"),animal("existing-shelter"));
        });
        when(repository.findByCareRegNoIn(anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return List.of(Shelter.builder().id(50L).careRegNo("existing-shelter").name("Existing management").build());
        });
        when(repository.saveAll(anyList())).thenAnswer(call -> {
            List<Shelter> shelters=call.getArgument(0); shelters.forEach(s -> ReflectionTestUtils.setField(s,"id",51L)); return shelters;
        });
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();return null; }).when(search).shelter(51L);
        assertThat(task(snapshot,repository,search,manager).execute(mock(StepContribution.class),mock(ChunkContext.class))).isEqualTo(RepeatStatus.FINISHED);
        ArgumentCaptor<List<Shelter>> saved=ArgumentCaptor.forClass(List.class); verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(Shelter::getCareRegNo).containsExactly("new-shelter");
        var order=inOrder(repository,search);order.verify(repository).flush();order.verify(search).shelter(51L);
        assertThat(manager.begins).isEqualTo(1);assertThat(manager.timeout).isEqualTo(15);assertThat(manager.commits).isEqualTo(1);
        assertThat(manager.getDefaultTimeout()).isEqualTo(7);assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
    @Test void givenCallerWriterTransaction__whenPreparing__thenRejectBeforeSnapshotOrDatabaseWork() {
        var snapshot=mock(ApmsAnimalSnapshot.class);var repository=mock(ShelterRepository.class);var search=mock(SearchDocumentWriter.class);
        var manager=new ObservedManager();var prep=task(snapshot,repository,search,manager);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> prep.execute(mock(StepContribution.class),mock(ChunkContext.class))))
                .hasMessage("APMS preparation must start outside the writer transaction");
        verifyNoInteractions(snapshot,repository,search);assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
    @Test void givenSearchFailureAfterShelterSave__whenPreparing__thenRollbackTheSameTransactionAndPropagate() {
        var snapshot=mock(ApmsAnimalSnapshot.class);var repository=mock(ShelterRepository.class);var search=mock(SearchDocumentWriter.class);
        var manager=new ObservedManager();when(snapshot.animals()).thenReturn(List.of(animal("new-shelter")));
        when(repository.saveAll(anyList())).thenAnswer(call -> { List<Shelter> saved=call.getArgument(0);ReflectionTestUtils.setField(saved.getFirst(),"id",51L);return saved; });
        doThrow(new IllegalStateException("synthetic projection failure")).when(search).shelter(51L);
        assertThatThrownBy(() -> task(snapshot,repository,search,manager).execute(mock(StepContribution.class),mock(ChunkContext.class))).hasMessage("synthetic projection failure");
        assertThat(manager.rollbacks).isEqualTo(1);assertThat(manager.commits).isZero();assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
    private ShelterPrepTasklet task(ApmsAnimalSnapshot snapshot,ShelterRepository repository,SearchDocumentWriter search,ObservedManager manager) { return new ShelterPrepTasklet(search,snapshot,repository,manager,15); }
    private ApmsAnimal animal(String shelter) { return ApmsAnimal.builder().desertionNo("old").happenDt("20260715").careRegNo(shelter).careNm("Test shelter").careAddr("Test address").build(); }
    private static final class ObservedManager extends ResourcelessTransactionManager {
        int begins,commits,rollbacks,timeout;
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) { begins++;timeout=definition.getTimeout();super.doBegin(transaction,definition); }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++;super.doCommit(status); }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++;super.doRollback(status); }
    }
}
