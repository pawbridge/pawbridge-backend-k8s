package com.pawbridge.backend.animal.internal.command.infrastructure.mapper

import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.CreateAnimalRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import java.time.LocalDate

class AnimalMapper {

    fun toEntity(request: CreateAnimalRequest, shelter: Shelter?): Animal {
        return Animal.builder()
            .apmsNoticeNo(request.apmsNoticeNo)
            .noticeStartDate(request.noticeStartDate)
            .noticeEndDate(request.noticeEndDate)
            .species(request.species)
            .breed(request.breed)
            .gender(request.gender)
            .neuterStatus(request.neuterStatus)
            .birthYear(request.birthYear)
            .weight(request.weight)
            .color(request.color)
            .specialMark(request.specialMark)
            .happenDate(request.happenDate)
            .happenPlace(request.happenPlace)
            .imageUrl(request.imageUrl)
            .imageUrl2(request.imageUrl2)
            .description(request.description)
            .status(request.status)
            .apiSource(request.apiSource)
            .shelter(shelter)
            .build()
    }

    fun toDetailResponse(animal: Animal): AnimalDetailResponse {
        val shelter = animal.shelter
        return AnimalDetailResponse.builder()
            .id(animal.id)
            .apmsNoticeNo(animal.apmsNoticeNo)
            .species(animal.species)
            .breed(animal.breed)
            .gender(animal.gender)
            .neuterStatus(animal.neuterStatus)
            .birthYear(animal.birthYear)
            .age(calculateAge(animal.birthYear))
            .weight(animal.weight)
            .color(animal.color)
            .specialMark(animal.specialMark)
            .status(animal.status)
            .noticeStartDate(animal.noticeStartDate)
            .noticeEndDate(animal.noticeEndDate)
            .happenPlace(animal.happenPlace)
            .happenDate(animal.happenDate)
            .imageUrl(animal.imageUrl)
            .imageUrl2(animal.imageUrl2)
            .description(animal.description)
            .favoriteCount(animal.favoriteCount)
            .shelterId(shelter?.id)
            .shelterName(shelter?.name)
            .apmsDesertionNo(animal.apmsDesertionNo)
            .apmsProcessState(animal.apmsProcessState)
            .apmsUpdatedAt(animal.apmsUpdatedAt)
            .apiSource(animal.apiSource)
            .createdAt(animal.createdAt)
            .updatedAt(animal.updatedAt)
            .build()
    }

    private fun calculateAge(birthYear: Int?): Int? {
        if (birthYear == null) {
            return null
        }
        val currentYear = LocalDate.now().year
        return currentYear - birthYear
    }
}
