package com.pawbridge.backend.animal.internal.apms.infrastructure.processor

import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.batch.core.StepExecution
import java.time.LocalDate
import java.time.LocalDateTime

class AnimalItemProcessorTest {
    @Test
    fun givenNewApmsRowAndExistingTypedShelter__whenProcessing__thenUseTheExistingAnimalModelAndOriginalSourceMapping() {
        val animals = mock(AnimalRepository::class.java)
        val shelters = mock(ShelterRepository::class.java)
        val shelter = Shelter.builder().id(10L).careRegNo("owned").build()
        `when`(shelters.findAll()).thenReturn(listOf(shelter))
        val processor = AnimalItemProcessor(animals, shelters)
        processor.beforeStep(mock(StepExecution::class.java))
        clearInvocations(animals, shelters)

        val row = processor.process(dto("new"))

        assertThat(row.id).isNull()
        assertThat(row.shelter).isSameAs(shelter)
        assertThat(row.apiSource).isEqualTo(ApiSource.APMS_ANIMAL)
        assertThat(row.apmsDesertionNo).isEqualTo("new")
        assertThat(row.species).isEqualTo(Species.DOG)
        assertThat(row.birthYear).isEqualTo(2023)
        assertThat(row.breed).isEqualTo("owned breed")
        assertThat(row.happenDate).isEqualTo(LocalDate.of(2026, 9, 10))
        assertThat(row.apmsUpdatedAt).isEqualTo(LocalDateTime.of(2026, 9, 10, 10, 20, 30))
        assertThat(row.imageUrl).isEqualTo("owned-image")
        assertThat(row.imageUrl2).isEqualTo("owned-extra")
        assertThat(row.description).isNull()
        assertThat(row.favoriteCount).isZero()
        verifyNoInteractions(animals, shelters)
    }

    @Test
    fun givenCachedExistingIdentity__whenProcessing__thenCreateOnlyTheOriginalUpdateCarrierWithoutPerItemLookup() {
        val animals = mock(AnimalRepository::class.java)
        val shelters = mock(ShelterRepository::class.java)
        `when`(animals.findAllApmsDesertionNoAndId()).thenReturn(listOf<Array<Any?>>(arrayOf("old", 41L)))
        `when`(shelters.findAll()).thenReturn(listOf(Shelter.builder().id(10L).careRegNo("owned").build()))
        val processor = AnimalItemProcessor(animals, shelters)
        processor.beforeStep(mock(StepExecution::class.java))
        clearInvocations(animals, shelters)

        val row = processor.process(dto("old"))

        assertThat(row.id).isEqualTo(41L)
        assertThat(row.imageUrl).isEqualTo("owned-image")
        assertThat(row.apmsDesertionNo).isNull()
        assertThat(row.description).isNull()
        verifyNoInteractions(animals, shelters)
    }

    @Test
    fun givenMissingPreparedShelter__whenProcessing__thenFailInsteadOfSilentlyFiltering() {
        val animals = mock(AnimalRepository::class.java)
        val shelters = mock(ShelterRepository::class.java)
        val processor = AnimalItemProcessor(animals, shelters)
        processor.beforeStep(mock(StepExecution::class.java))

        assertThatThrownBy { processor.process(dto("new")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("shelterCache miss")
    }

    private fun dto(id: String): ApmsAnimal {
        return ApmsAnimal.builder()
            .desertionNo(id)
            .careRegNo("owned")
            .upKindCd("417000")
            .kindNm("[개] owned breed")
            .age("2023(년생)")
            .happenDt("20260910")
            .updTm("2026-09-10 10:20:30.0")
            .processState("보호중")
            .popfile1("owned-image")
            .popfile2("owned-extra")
            .build()
    }
}
