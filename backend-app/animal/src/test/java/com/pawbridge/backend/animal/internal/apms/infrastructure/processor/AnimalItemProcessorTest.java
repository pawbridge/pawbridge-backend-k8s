package com.pawbridge.backend.animal.internal.apms.infrastructure.processor;

import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter;
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource;
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnimalItemProcessorTest {
    @Test void givenNewApmsRowAndExistingTypedShelter__whenProcessing__thenUseTheExistingAnimalModelAndOriginalSourceMapping() throws Exception {
        var animals=mock(AnimalRepository.class);var shelters=mock(ShelterRepository.class);
        var shelter=Shelter.builder().id(10L).careRegNo("owned").build();when(shelters.findAll()).thenReturn(List.of(shelter));
        var processor=new AnimalItemProcessor(animals,shelters);processor.beforeStep(null);clearInvocations(animals,shelters);
        var row=processor.process(dto("new"));
        assertThat(row.getId()).isNull();assertThat(row.getShelter()).isSameAs(shelter);assertThat(row.getApiSource()).isEqualTo(ApiSource.APMS_ANIMAL);
        assertThat(row.getApmsDesertionNo()).isEqualTo("new");assertThat(row.getSpecies()).isEqualTo(Species.DOG);
        assertThat(row.getBirthYear()).isEqualTo(2023);assertThat(row.getBreed()).isEqualTo("owned breed");
        assertThat(row.getHappenDate()).isEqualTo(LocalDate.of(2026,9,10));assertThat(row.getApmsUpdatedAt()).isEqualTo(LocalDateTime.of(2026,9,10,10,20,30));
        assertThat(row.getImageUrl()).isEqualTo("owned-image");assertThat(row.getImageUrl2()).isEqualTo("owned-extra");
        assertThat(row.getDescription()).isNull();assertThat(row.getFavoriteCount()).isZero();verifyNoInteractions(animals,shelters);
    }

    @Test void givenCachedExistingIdentity__whenProcessing__thenCreateOnlyTheOriginalUpdateCarrierWithoutPerItemLookup() throws Exception {
        var animals=mock(AnimalRepository.class);var shelters=mock(ShelterRepository.class);
        when(animals.findAllApmsDesertionNoAndId()).thenReturn(List.<Object[]>of(new Object[]{"old",41L}));
        when(shelters.findAll()).thenReturn(List.of(Shelter.builder().id(10L).careRegNo("owned").build()));
        var processor=new AnimalItemProcessor(animals,shelters);processor.beforeStep(null);clearInvocations(animals,shelters);
        var row=processor.process(dto("old"));assertThat(row.getId()).isEqualTo(41L);assertThat(row.getImageUrl()).isEqualTo("owned-image");
        assertThat(row.getApmsDesertionNo()).isNull();assertThat(row.getDescription()).isNull();verifyNoInteractions(animals,shelters);
    }

    @Test void givenMissingPreparedShelter__whenProcessing__thenFailInsteadOfSilentlyFiltering() {
        var animals=mock(AnimalRepository.class);var shelters=mock(ShelterRepository.class);
        var processor=new AnimalItemProcessor(animals,shelters);processor.beforeStep(null);
        assertThatThrownBy(() -> processor.process(dto("new"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shelterCache miss");
    }
    private ApmsAnimal dto(String id) { return ApmsAnimal.builder().desertionNo(id).careRegNo("owned").upKindCd("417000")
            .kindNm("[개] owned breed").age("2023(년생)").happenDt("20260910").updTm("2026-09-10 10:20:30.0")
            .processState("보호중").popfile1("owned-image").popfile2("owned-extra").build(); }
}
