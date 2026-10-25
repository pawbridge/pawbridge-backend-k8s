package com.pawbridge.backend.user.internal.shelter.infrastructure

import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership

/** User depends only on Animal.api; existing membership service retains all authorization and TX. */
class ModuleShelterLookup(private val shelters: AnimalShelterReads) : ShelterMembership.ShelterLookup {

    override fun find(careRegNo: String?): ShelterMembership.Shelter? {
        val result = shelters.findByCareRegNo(careRegNo) ?: return null
        return ShelterMembership.Shelter(result.id(), result.careRegNo())
    }
}
