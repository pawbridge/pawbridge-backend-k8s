package com.pawbridge.backend.user.internal.shelter.infrastructure

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.user.internal.shelter.application.ShelterMembership
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

class ModuleShelterLookupTest {
    private val source: AnimalShelterReads = mock(AnimalShelterReads::class.java)
    private val lookup = ModuleShelterLookup(source)

    @Test
    fun givenAnimalApiProjection__whenLookingUp__thenMapOnlyUserContractAndPreserveRawRegistrationArgument() {
        `when`(source.findByCareRegNo("등록 번호")).thenReturn(AnimalShelterReads.Shelter(9L, "등록 번호", "보호소"))

        assertEquals(ShelterMembership.Shelter(9L, "등록 번호"), lookup.find("등록 번호"))

        verify(source).findByCareRegNo("등록 번호")
        verifyNoMoreInteractions(source)
    }

    @Test
    fun givenNullOrMalformedIdentity__whenLookingUp__thenRetainExistingMembershipServiceValidationOwnership() {
        assertNull(lookup.find("r"))
        `when`(source.findByCareRegNo("r")).thenReturn(AnimalShelterReads.Shelter(null, "different", null))

        assertEquals(ShelterMembership.Shelter(null, "different"), lookup.find("r"))
    }

    @Test
    fun givenAnimalUnavailable__whenLookingUp__thenPropagateForExisting503MappingWithoutInventingMissingMarker() {
        val failure = AnimalShelterReadUnavailableException(IllegalStateException("synthetic source missing"))
        `when`(source.findByCareRegNo("r")).thenThrow(failure)

        val actual = assertThrows(AnimalShelterReadUnavailableException::class.java) { lookup.find("r") }

        assertSame(failure, actual)
        assertFalse(ShelterMembership.MissingShelter::class.java.isInstance(actual))
    }
}
