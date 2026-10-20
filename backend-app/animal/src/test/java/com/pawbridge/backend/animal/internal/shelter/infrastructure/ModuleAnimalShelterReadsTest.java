package com.pawbridge.backend.animal.internal.shelter.infrastructure;

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModuleAnimalShelterReadsTest {
    private final ShelterReads source = mock(ShelterReads.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final ModuleAnimalShelterReads reads = new ModuleAnimalShelterReads(source, manager);

    ModuleAnimalShelterReadsTest() { when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus()); }

    @Test
    void givenRegistrationLookup__whenDelegating__thenReturnOnlySharedIdRegistrationAndNameWithRawInput() {
        when(source.findByCareRegNo(" 등록 번호 ")).thenReturn(new ShelterReads.Details(9L, "등록 번호", "보호소", null, null,
                null, null, null, null, null, null, null, null, null));
        assertThat(reads.findByCareRegNo(" 등록 번호 ")).isEqualTo(new AnimalShelterReads.Shelter(9L, "등록 번호", "보호소"));
        verify(source).findByCareRegNo(" 등록 번호 ");
        verifyNoMoreInteractions(source);
    }

    @Test
    void givenModuleBoundary__whenCreatingAnimalReadScope__thenRequestIndependentReadOnlyDefaultIsolationAndTimeout() {
        reads.findByCareRegNo("r");
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        assertThat(definition.getValue().getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
        verify(manager).commit(any());
    }

    @Test
    void givenCurrentSourceMissingRegistrationFailure__whenDelegating__thenPreserveUnavailableInsteadOfInventing404OrEmpty() {
        var failure = new IllegalStateException("Shelter not found: r");
        when(source.findByCareRegNo("r")).thenThrow(failure);
        assertThatThrownBy(() -> reads.findByCareRegNo("r")).isInstanceOf(AnimalShelterReadUnavailableException.class).hasCause(failure);
        verify(manager).rollback(any());
    }

    @Test
    void givenEmptySourceBody__whenDelegating__thenKeepNullForExistingCallerUnavailableChecks() {
        assertThat(reads.findByCareRegNo("r")).isNull();
        verify(source).findByCareRegNo("r");
    }
}
