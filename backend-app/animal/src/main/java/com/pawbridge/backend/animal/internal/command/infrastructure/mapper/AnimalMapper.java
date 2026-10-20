package com.pawbridge.backend.animal.internal.command.infrastructure.mapper;

import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.CreateAnimalRequest;
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter;

import java.time.LocalDate;

public class AnimalMapper {
public Animal toEntity(CreateAnimalRequest request, Shelter shelter) {
        return Animal.builder()
                .apmsNoticeNo(request.getApmsNoticeNo())
                .noticeStartDate(request.getNoticeStartDate())
                .noticeEndDate(request.getNoticeEndDate())
                .species(request.getSpecies())
                .breed(request.getBreed())
                .gender(request.getGender())
                .neuterStatus(request.getNeuterStatus())
                .birthYear(request.getBirthYear())
                .weight(request.getWeight())
                .color(request.getColor())
                .specialMark(request.getSpecialMark())
                .happenPlace(request.getHappenPlace())
                .imageUrl(request.getImageUrl())
                .imageUrl2(request.getImageUrl2())
                .description(request.getDescription())
                .status(request.getStatus())
                .apiSource(request.getApiSource())
                .shelter(shelter)
                .build();
    }

public AnimalDetailResponse toDetailResponse(Animal animal) {
        return AnimalDetailResponse.builder()
                .id(animal.getId())
                .apmsNoticeNo(animal.getApmsNoticeNo())
                .species(animal.getSpecies())
                .breed(animal.getBreed())
                .gender(animal.getGender())
                .neuterStatus(animal.getNeuterStatus())
                .birthYear(animal.getBirthYear())
                .age(calculateAge(animal.getBirthYear()))
                .weight(animal.getWeight())
                .color(animal.getColor())
                .specialMark(animal.getSpecialMark())
                .status(animal.getStatus())
                .noticeStartDate(animal.getNoticeStartDate())
                .noticeEndDate(animal.getNoticeEndDate())
                .happenPlace(animal.getHappenPlace())
                .happenDate(animal.getHappenDate())
                .imageUrl(animal.getImageUrl())
                .imageUrl2(animal.getImageUrl2())
                .description(animal.getDescription())
                .favoriteCount(animal.getFavoriteCount())
                .shelterId(animal.getShelter() != null ? animal.getShelter().getId() : null)
                .shelterName(animal.getShelter() != null ? animal.getShelter().getName() : null)
                .apmsDesertionNo(animal.getApmsDesertionNo())
                .apmsProcessState(animal.getApmsProcessState())
                .apmsUpdatedAt(animal.getApmsUpdatedAt())
                .apiSource(animal.getApiSource())
                .createdAt(animal.getCreatedAt())
                .updatedAt(animal.getUpdatedAt())
                .build();
    }

private Integer calculateAge(Integer birthYear) {
        if (birthYear == null) {
            return null;
        }
        int currentYear = LocalDate.now().getYear();
        return currentYear - birthYear;
    }
}
