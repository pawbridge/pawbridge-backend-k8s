package com.pawbridge.backend.user.internal.shelter.infrastructure;

import com.pawbridge.backend.animal.api.AnimalShelterReads;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;

/** User depends only on Animal.api; existing membership service retains all authorization and TX. */
public final class ModuleShelterLookup implements ShelterMembership.ShelterLookup {
    private final AnimalShelterReads shelters;
    public ModuleShelterLookup(AnimalShelterReads shelters) { this.shelters = shelters; }

    @Override
    public ShelterMembership.Shelter find(String careRegNo) {
        var result = shelters.findByCareRegNo(careRegNo);
        return result == null ? null : new ShelterMembership.Shelter(result.id(), result.careRegNo());
    }
}
