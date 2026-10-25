package com.pawbridge.backend.animal.internal.apms.infrastructure.processor

import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.batch.core.StepExecution
import java.time.LocalDate
import java.time.LocalDateTime

class AnimalItemProcessorTest {
    @Test
    fun `신규 APMS 행과 기존에 있는 유형별 보호소가 있을 때__처리하면__기존 동물 모델과 원래 출처 매핑을 그대로 쓴다`() {
        // given
        val animals = mockk<AnimalRepository>()
        val shelters = mockk<ShelterRepository>()
        val shelter = Shelter.builder().id(10L).careRegNo("owned").build()
        val processor = AnimalItemProcessor(animals, shelters)

        every { animals.findAllApmsDesertionNoAndId() } returns listOf()
        every { shelters.findAll() } returns listOf(shelter)

        processor.beforeStep(mockk<StepExecution>())
        clearMocks(animals, shelters, answers = false)

        // when
        val row = processor.process(dto("new"))

        // then
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
        confirmVerified(animals, shelters)
    }

    @Test
    fun `캐시된 기존 식별자가 있을 때__처리하면__건별 조회 없이 원래의 갱신용 객체만 만든다`() {
        // given
        val animals = mockk<AnimalRepository>()
        val shelters = mockk<ShelterRepository>()
        val processor = AnimalItemProcessor(animals, shelters)

        every { animals.findAllApmsDesertionNoAndId() } returns listOf<Array<Any?>>(arrayOf("old", 41L))
        every { shelters.findAll() } returns listOf(Shelter.builder().id(10L).careRegNo("owned").build())

        processor.beforeStep(mockk<StepExecution>())
        clearMocks(animals, shelters, answers = false)

        // when
        val row = processor.process(dto("old"))

        // then
        assertThat(row.id).isEqualTo(41L)
        assertThat(row.imageUrl).isEqualTo("owned-image")
        assertThat(row.apmsDesertionNo).isNull()
        assertThat(row.description).isNull()
        confirmVerified(animals, shelters)
    }

    @Test
    fun `준비된 보호소가 없을 때__처리하면__조용히 거르지 않고 실패한다`() {
        // given
        val animals = mockk<AnimalRepository>()
        val shelters = mockk<ShelterRepository>()
        val processor = AnimalItemProcessor(animals, shelters)

        every { animals.findAllApmsDesertionNoAndId() } returns listOf()
        every { shelters.findAll() } returns listOf()

        processor.beforeStep(mockk<StepExecution>())

        // when
        val thrown = assertThatThrownBy { processor.process(dto("new")) }

        // then
        thrown
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
