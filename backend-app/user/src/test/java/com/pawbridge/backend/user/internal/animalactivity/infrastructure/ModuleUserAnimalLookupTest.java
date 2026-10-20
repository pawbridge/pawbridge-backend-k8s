package com.pawbridge.backend.user.internal.animalactivity.infrastructure;

import com.pawbridge.backend.animal.api.AnimalMemberReads;
import com.pawbridge.backend.animal.api.AnimalShelterReads;
import com.pawbridge.backend.animal.api.AnimalMemberReadUnavailableException;
import com.pawbridge.backend.user.internal.animalactivity.application.UserAnimalActivities;
import com.pawbridge.backend.user.internal.shelter.infrastructure.ModuleShelterLookup;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModuleUserAnimalLookupTest {
    private final AnimalMemberReads animals=mock(AnimalMemberReads.class);
    private final ModuleUserAnimalLookup lookup=new ModuleUserAnimalLookup(animals);
    @Test void givenBatchAllFieldsAndNulls__whenMapping__thenRetainEveryDtoValueAndDelegateInputUnchanged() {
        LocalDate end=LocalDate.of(2026,12,31); LocalDateTime created=LocalDateTime.of(2026,10,20,1,2,3,123456000);
        var row=new AnimalMemberReads.Animal(8L,"공고","DOG","품종","MALE",2020,6,"특징","ADOPTED",end,"image",5,7L,"센터",created);
        var nullable=new AnimalMemberReads.Animal(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null);
        List<Long> ids=Arrays.asList(8L,8L,404L,null); when(animals.batch(ids)).thenReturn(Arrays.asList(row,nullable,null));
        assertIterableEquals(Arrays.asList(new UserAnimalActivities.Animal(8L,"공고","DOG","품종","MALE",2020,6,"특징","ADOPTED",end,"image",5,7L,"센터",created),
                new UserAnimalActivities.Animal(null,null,null,null,null,null,null,null,null,null,null,null,null,null,null),null),lookup.batch(ids));
        verify(animals).batch(same(ids)); verifyNoMoreInteractions(animals);
    }
    @Test void givenActualPageNumberAndRawSort__whenByShelter__thenKeepLegacyWirePageZeroAndAllOtherMetadata() {
        var request=new UserAnimalActivities.PageRequest(3,20,"shelter.animals.id,asc");
        when(animals.byShelter(7L,new AnimalMemberReads.PageRequest(3,20,"shelter.animals.id,asc")))
                .thenReturn(new AnimalMemberReads.Page(List.of(),3,20,61,4,true));
        assertEquals(new UserAnimalActivities.AnimalPage(List.of(),0,20,61,4,true),lookup.byShelter(7L,request));
        verify(animals).byShelter(7L,new AnimalMemberReads.PageRequest(3,20,"shelter.animals.id,asc"));
    }
    @Test void givenUnifiedAnimalContract__whenBothUserConsumersFindShelter__thenUseSameApiAndPreserveTheirDifferentProjection() {
        when(animals.findByCareRegNo(" r ")).thenReturn(new AnimalShelterReads.Shelter(7L,"r","센터"));
        assertEquals(new UserAnimalActivities.Shelter(7L,"센터"),lookup.shelter(" r "));
        assertEquals("r",new ModuleShelterLookup(animals).find(" r ").careRegNo());
        verify(animals,times(2)).findByCareRegNo(" r ");
    }
    @Test void givenAbsentBodiesOrNullContent__whenMapping__thenKeepCallerUnavailableAndNullableSemantics() {
        when(animals.batch(any())).thenReturn(null);
        assertNull(lookup.batch(List.of(8L))); assertNull(lookup.shelter("r"));
        var request=new UserAnimalActivities.PageRequest(0,20,"id,asc"); assertNull(lookup.byShelter(7L,request));
        when(animals.byShelter(any(),any())).thenReturn(new AnimalMemberReads.Page(null,2,20,5,1,true));
        assertNull(lookup.byShelter(7L,request).content());
    }
    @Test void givenReadFailure__whenAnyActivityCall__thenPreserveExistingCallerFailurePolicy() {
        var failure=new AnimalMemberReadUnavailableException(new IllegalStateException("read unavailable"));
        when(animals.batch(any())).thenThrow(failure); when(animals.byShelter(any(),any())).thenThrow(failure);
        when(animals.findByCareRegNo(any())).thenThrow(failure);
        assertSame(failure,assertThrows(AnimalMemberReadUnavailableException.class,()->lookup.batch(List.of(8L))));
        assertSame(failure,assertThrows(AnimalMemberReadUnavailableException.class,()->lookup.shelter("r")));
        assertSame(failure,assertThrows(AnimalMemberReadUnavailableException.class,()->lookup.byShelter(7L,new UserAnimalActivities.PageRequest(0,20,"id,asc"))));
    }
}
