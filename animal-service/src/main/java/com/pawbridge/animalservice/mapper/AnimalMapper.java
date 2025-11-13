package com.pawbridge.animalservice.mapper;

import com.pawbridge.animalservice.dto.request.CreateAnimalRequest;
import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Animal Entity ↔ DTO 변환 매퍼
 */
@Component
public class AnimalMapper {

    // Request DTO → Entity
    /**
     * CreateAnimalRequest → Animal Entity
     * - Shelter는 별도로 설정 필요
     * @param request 요청 DTO
     * @param shelter 보호소 엔티티
     * @return Animal 엔티티
     */
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

    //Entity → Response DTO
    /**
     * Animal Entity → AnimalResponse (목록 조회용)
     * @param animal 동물 엔티티
     * @return AnimalResponse DTO
     */
    public AnimalResponse toResponse(Animal animal) {
        return AnimalResponse.builder()
                .id(animal.getId())
                .apmsNoticeNo(animal.getApmsNoticeNo())
                .species(animal.getSpecies())
                .breed(animal.getBreed())
                .gender(animal.getGender())
                .birthYear(animal.getBirthYear())
                .age(calculateAge(animal.getBirthYear()))
                .status(animal.getStatus())
                .noticeEndDate(animal.getNoticeEndDate())
                .imageUrl(animal.getImageUrl())
                .favoriteCount(animal.getFavoriteCount())
                .shelterId(animal.getShelter() != null ? animal.getShelter().getId() : null)
                .shelterName(animal.getShelter() != null ? animal.getShelter().getName() : null)
                .createdAt(animal.getCreatedAt())
                .build();
    }

    /**
     * Animal Entity → AnimalDetailResponse (상세 조회용)
     * @param animal 동물 엔티티
     * @return AnimalDetailResponse DTO
     */
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

    /**
     * 출생 연도로부터 나이 계산
     * @param birthYear 출생 연도
     * @return 나이 (null이면 null 반환)
     */
    private Integer calculateAge(Integer birthYear) {
        if (birthYear == null) {
            return null;
        }
        int currentYear = LocalDate.now().getYear();
        return currentYear - birthYear;
    }
}
