package com.pawbridge.backend.user.internal.animalactivity.infrastructure

import com.pawbridge.backend.animal.api.AnimalMemberReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalMemberReads
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities
import com.pawbridge.backend.user.internal.shelter.infrastructure.ModuleShelterLookup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import java.time.LocalDate
import java.time.LocalDateTime

class ModuleUserAnimalLookupTest {
    private val animals: AnimalMemberReads = mock(AnimalMemberReads::class.java)
    private val lookup = ModuleUserAnimalLookup(animals)

    @Test
    fun givenBatchAllFieldsAndNulls__whenMapping__thenRetainEveryDtoValueAndDelegateInputUnchanged() {
        val end = LocalDate.of(2026, 12, 31)
        val created = LocalDateTime.of(2026, 10, 20, 1, 2, 3, 123456000)
        val row = AnimalMemberReads.Animal(8L, "공고", "DOG", "품종", "MALE", 2020, 6, "특징", "ADOPTED", end, "image", 5, 7L, "센터", created)
        val nullable = AnimalMemberReads.Animal(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
        val ids: List<Long?> = listOf(8L, 8L, 404L, null)
        doReturn(listOf(row, nullable, null)).`when`(animals).batch(ids)

        assertIterableEquals(
            listOf(
                UserAnimalActivities.Animal(8L, "공고", "DOG", "품종", "MALE", 2020, 6, "특징", "ADOPTED", end, "image", 5, 7L, "센터", created),
                UserAnimalActivities.Animal(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                null,
            ),
            lookup.batch(ids),
        )

        verify(animals).batch(same(ids))
        verifyNoMoreInteractions(animals)
    }

    @Test
    fun givenActualPageNumberAndRawSort__whenByShelter__thenKeepLegacyWirePageZeroAndAllOtherMetadata() {
        val request = UserAnimalActivities.PageRequest(3, 20, "shelter.animals.id,asc")
        `when`(animals.byShelter(7L, AnimalMemberReads.PageRequest(3, 20, "shelter.animals.id,asc")))
            .thenReturn(AnimalMemberReads.Page(listOf(), 3, 20, 61, 4, true))

        assertEquals(UserAnimalActivities.AnimalPage(listOf(), 0, 20, 61, 4, true), lookup.byShelter(7L, request))

        verify(animals).byShelter(7L, AnimalMemberReads.PageRequest(3, 20, "shelter.animals.id,asc"))
    }

    @Test
    fun givenUnifiedAnimalContract__whenBothUserConsumersFindShelter__thenUseSameApiAndPreserveTheirDifferentProjection() {
        `when`(animals.findByCareRegNo(" r ")).thenReturn(AnimalShelterReads.Shelter(7L, "r", "센터"))

        assertEquals(UserAnimalActivities.Shelter(7L, "센터"), lookup.shelter(" r "))
        assertEquals("r", ModuleShelterLookup(animals).find(" r ")!!.careRegNo)

        verify(animals, times(2)).findByCareRegNo(" r ")
    }

    @Test
    fun givenAbsentBodiesOrNullContent__whenMapping__thenKeepCallerUnavailableAndNullableSemantics() {
        `when`(animals.batch(any())).thenReturn(null)
        assertNull(lookup.batch(listOf(8L)))
        assertNull(lookup.shelter("r"))
        val request = UserAnimalActivities.PageRequest(0, 20, "id,asc")
        assertNull(lookup.byShelter(7L, request))
        `when`(animals.byShelter(any(), any())).thenReturn(AnimalMemberReads.Page(null, 2, 20, 5, 1, true))

        assertNull(lookup.byShelter(7L, request)!!.content)
    }

    @Test
    fun givenReadFailure__whenAnyActivityCall__thenPreserveExistingCallerFailurePolicy() {
        val failure = AnimalMemberReadUnavailableException(IllegalStateException("read unavailable"))
        `when`(animals.batch(any())).thenThrow(failure)
        `when`(animals.byShelter(any(), any())).thenThrow(failure)
        `when`(animals.findByCareRegNo(any())).thenThrow(failure)

        assertSame(failure, assertThrows(AnimalMemberReadUnavailableException::class.java) { lookup.batch(listOf(8L)) })
        assertSame(failure, assertThrows(AnimalMemberReadUnavailableException::class.java) { lookup.shelter("r") })
        assertSame(
            failure,
            assertThrows(AnimalMemberReadUnavailableException::class.java) {
                lookup.byShelter(7L, UserAnimalActivities.PageRequest(0, 20, "id,asc"))
            },
        )
    }
}
