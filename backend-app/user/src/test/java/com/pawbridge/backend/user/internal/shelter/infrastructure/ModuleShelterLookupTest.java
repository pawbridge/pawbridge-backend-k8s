package com.pawbridge.backend.user.internal.shelter.infrastructure;

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModuleShelterLookupTest {
    private final AnimalShelterReads source = mock(AnimalShelterReads.class);
    private final ModuleShelterLookup lookup = new ModuleShelterLookup(source);

    @Test
    void givenAnimalApiProjection__whenLookingUp__thenMapOnlyUserContractAndPreserveRawRegistrationArgument() {
        when(source.findByCareRegNo("등록 번호")).thenReturn(new AnimalShelterReads.Shelter(9L, "등록 번호", "보호소"));
        assertThat(lookup.find("등록 번호")).isEqualTo(new ShelterMembership.Shelter(9L, "등록 번호"));
        verify(source).findByCareRegNo("등록 번호");
        verifyNoMoreInteractions(source);
    }

    @Test
    void givenNullOrMalformedIdentity__whenLookingUp__thenRetainExistingMembershipServiceValidationOwnership() {
        assertThat(lookup.find("r")).isNull();
        when(source.findByCareRegNo("r")).thenReturn(new AnimalShelterReads.Shelter(null, "different", null));
        assertThat(lookup.find("r")).isEqualTo(new ShelterMembership.Shelter(null, "different"));
    }

    @Test
    void givenAnimalUnavailable__whenLookingUp__thenPropagateForExisting503MappingWithoutInventingMissingMarker() {
        var failure = new AnimalShelterReadUnavailableException(new IllegalStateException("synthetic source missing"));
        when(source.findByCareRegNo("r")).thenThrow(failure);
        assertThatThrownBy(() -> lookup.find("r")).isSameAs(failure).isNotInstanceOf(ShelterMembership.MissingShelter.class);
    }
}
