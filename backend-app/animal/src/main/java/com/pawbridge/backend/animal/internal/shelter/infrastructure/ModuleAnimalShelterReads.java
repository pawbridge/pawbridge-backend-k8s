package com.pawbridge.backend.animal.internal.shelter.infrastructure;

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Delegates to the existing full detail read, including its public-information failure behavior. */
public final class ModuleAnimalShelterReads implements AnimalShelterReads {
    private final ShelterReads shelters;
    private final TransactionTemplate reads;
    public ModuleAnimalShelterReads(ShelterReads shelters, PlatformTransactionManager readerManager) {
        this.shelters = shelters;
        this.reads = new TransactionTemplate(readerManager);
        reads.setReadOnly(true);
        // REQUIRED, not REQUIRES_NEW: every manager now shares one pool, so a new transaction inside User approval's
        // writer transaction would hold a second pooled connection. Joining reads the same READ COMMITTED data
        // (approval writes no Animal rows before this lookup) and any failure already aborts the approval.
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public Shelter findByCareRegNo(String careRegNo) {
        try {
            // Without an outer transaction this is its own read-only transaction; inside approval it joins it.
            return reads.execute(transaction -> {
                var result = shelters.findByCareRegNo(careRegNo);
                return result == null ? null : new Shelter(result.id(), result.careRegNo(), result.name());
            });
        } catch (RuntimeException failure) {
            throw new AnimalShelterReadUnavailableException(failure);
        }
    }
}
