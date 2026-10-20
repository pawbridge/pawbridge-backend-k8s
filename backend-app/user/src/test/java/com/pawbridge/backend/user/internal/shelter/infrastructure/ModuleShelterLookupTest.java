package com.pawbridge.backend.user.internal.shelter.infrastructure;

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModuleShelterLookupTest {
    private final AnimalShelterReads source = mock(AnimalShelterReads.class);
    private final ModuleShelterLookup lookup = new ModuleShelterLookup(source);

    @Test
    void givenAnimalApiProjection__whenLookingUp__thenMapOnlyUserContractAndPreserveRawRegistrationArgument() {
        when(source.findByCareRegNo("등록 번호")).thenReturn(new AnimalShelterReads.Shelter(9L, "등록 번호", "보호소"));
        assertEquals(new ShelterMembership.Shelter(9L, "등록 번호"), lookup.find("등록 번호"));
        verify(source).findByCareRegNo("등록 번호");
        verifyNoMoreInteractions(source);
    }

    @Test
    void givenNullOrMalformedIdentity__whenLookingUp__thenRetainExistingMembershipServiceValidationOwnership() {
        assertNull(lookup.find("r"));
        when(source.findByCareRegNo("r")).thenReturn(new AnimalShelterReads.Shelter(null, "different", null));
        assertEquals(new ShelterMembership.Shelter(null, "different"), lookup.find("r"));
    }

    @Test
    void givenAnimalUnavailable__whenLookingUp__thenPropagateForExisting503MappingWithoutInventingMissingMarker() {
        var failure = new AnimalShelterReadUnavailableException(new IllegalStateException("synthetic source missing"));
        when(source.findByCareRegNo("r")).thenThrow(failure);
        var actual = assertThrows(AnimalShelterReadUnavailableException.class, () -> lookup.find("r"));
        assertSame(failure, actual);
        assertFalse(ShelterMembership.MissingShelter.class.isInstance(actual));
    }
}
