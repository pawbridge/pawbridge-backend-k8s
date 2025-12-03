package com.pawbridge.animalservice.mapper;

import com.pawbridge.animalservice.document.AnimalDocument;
import com.pawbridge.animalservice.dto.response.AnimalDetailResponse;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.ApiSource;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.NeuterStatus;
import com.pawbridge.animalservice.enums.Species;
import org.springframework.stereotype.Component;

import java.time.Year;

/**
 * AnimalDocument → AnimalResponse 변환 Mapper
 * - Elasticsearch 검색 결과를 API 응답 DTO로 변환
 */
@Component
public class AnimalDocumentMapper {

    /**
     * AnimalDocument를 AnimalResponse로 변환
     * @param document Elasticsearch 문서
     * @return AnimalResponse
     */
    public AnimalResponse toResponse(AnimalDocument document) {
        if (document == null) {
            return null;
        }

        return AnimalResponse.builder()
            .id(document.getId() != null ? Long.parseLong(document.getId()) : null)
            .apmsNoticeNo(document.getApmsNoticeNo())
            .species(document.getSpecies() != null ? Species.valueOf(document.getSpecies()) : null)
            .breed(document.getBreed())
            .gender(document.getGender() != null ? Gender.valueOf(document.getGender()) : null)
            .birthYear(document.getBirthYear())
            .age(calculateAge(document.getBirthYear()))
            .specialMark(document.getSpecialMark())
            .status(document.getStatus() != null ? AnimalStatus.valueOf(document.getStatus()) : null)
            .noticeEndDate(document.getNoticeEndDate())
            .imageUrl(document.getImageUrl())
            .favoriteCount(document.getFavoriteCount())
            .shelterId(document.getShelterId())
            .shelterName(document.getShelterName())
            .createdAt(document.getCreatedAt())
            .build();
    }

    /**
     * AnimalDocument를 AnimalDetailResponse로 변환
     * @param document Elasticsearch 문서
     * @return AnimalDetailResponse (전체 상세 정보)
     */
    public AnimalDetailResponse toDetailResponse(AnimalDocument document) {
        if (document == null) {
            return null;
        }

        return AnimalDetailResponse.builder()
            // 기본 정보
            .id(document.getId() != null ? Long.parseLong(document.getId()) : null)
            .apmsNoticeNo(document.getApmsNoticeNo())
            .species(document.getSpecies() != null ? Species.valueOf(document.getSpecies()) : null)
            .breed(document.getBreed())
            .gender(document.getGender() != null ? Gender.valueOf(document.getGender()) : null)
            .neuterStatus(document.getNeuterStatus() != null ? NeuterStatus.valueOf(document.getNeuterStatus()) : null)
            .birthYear(document.getBirthYear())
            .age(calculateAge(document.getBirthYear()))

            // 신체 특징
            .weight(document.getWeight())
            .color(document.getColor())
            .specialMark(document.getSpecialMark())

            // 상태 및 공고 정보
            .status(document.getStatus() != null ? AnimalStatus.valueOf(document.getStatus()) : null)
            .noticeStartDate(document.getNoticeStartDate())
            .noticeEndDate(document.getNoticeEndDate())

            // 발견/접수 정보
            .happenPlace(document.getHappenPlace())
            .happenDate(document.getHappenDate())

            // 이미지
            .imageUrl(document.getImageUrl())
            .imageUrl2(document.getImageUrl2())

            // 설명
            .description(document.getDescription())

            // 찜 정보
            .favoriteCount(document.getFavoriteCount())

            // 보호소 정보
            .shelterId(document.getShelterId())
            .shelterName(document.getShelterName())

            // APMS 연동 정보
            .apmsDesertionNo(document.getApmsDesertionNo())
            .apmsProcessState(document.getApmsProcessState())
            .apmsUpdatedAt(document.getApmsUpdatedAt())
            .apiSource(document.getApiSource() != null ? ApiSource.valueOf(document.getApiSource()) : null)

            // 메타 정보
            .createdAt(document.getCreatedAt())
            .updatedAt(document.getUpdatedAt())
            .build();
    }

    /**
     * 나이 계산 (현재 연도 기준)
     * @param birthYear 출생 연도
     * @return 만 나이
     */
    private Integer calculateAge(Integer birthYear) {
        if (birthYear == null) {
            return null;
        }
        return Year.now().getValue() - birthYear;
    }
}
