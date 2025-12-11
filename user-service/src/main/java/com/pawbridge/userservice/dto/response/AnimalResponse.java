package com.pawbridge.userservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * animal-service에서 받아오는 동물 정보 DTO
 * - animal-service의 AnimalResponse와 동일한 구조
 * - Enum 타입은 Jackson이 자동으로 String으로 변환
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalResponse {

    private Long id;
    private String apmsNoticeNo;
    private String species;  // Enum: DOG, CAT, ETC
    private String breed;
    private String gender;  // Enum: MALE, FEMALE, UNKNOWN
    private Integer birthYear;
    private Integer age;
    private String specialMark;
    private String status;  // Enum: PROTECT, ADOPTED, etc
    private LocalDate noticeEndDate;
    private String imageUrl;
    private Integer favoriteCount;
    private Long shelterId;
    private String shelterName;
    private LocalDateTime createdAt;
}