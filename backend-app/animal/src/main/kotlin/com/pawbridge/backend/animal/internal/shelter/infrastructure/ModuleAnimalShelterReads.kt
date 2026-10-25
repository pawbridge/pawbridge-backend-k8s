package com.pawbridge.backend.animal.internal.shelter.infrastructure

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.animal.api.AnimalShelterReads.Shelter
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** Delegates to the existing full detail read, including its public-information failure behavior. */
class ModuleAnimalShelterReads(
    private val shelters: ShelterReads,
    readerManager: PlatformTransactionManager,
) : AnimalShelterReads {
    private val reads: TransactionTemplate = TransactionTemplate(readerManager)

    init {
        reads.isReadOnly = true
        // REQUIRED, not REQUIRES_NEW: every manager now shares one pool, so a new transaction inside User approval's
        // writer transaction would hold a second pooled connection. Joining reads the same READ COMMITTED data
        // (approval writes no Animal rows before this lookup) and any failure already aborts the approval.
        reads.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
    }

    override fun findByCareRegNo(careRegNo: String?): Shelter? {
        try {
            // Without an outer transaction this is its own read-only transaction; inside approval it joins it.
            return reads.execute {
                val result = shelters.findByCareRegNo(careRegNo)
                if (result == null) null else Shelter(result.id, result.careRegNo, result.name)
            }
        } catch (failure: RuntimeException) {
            throw AnimalShelterReadUnavailableException(failure)
        }
    }
}
