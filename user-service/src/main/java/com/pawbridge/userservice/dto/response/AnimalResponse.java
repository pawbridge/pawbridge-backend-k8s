package com.pawbridge.userservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * animal-service에서 받아오는 동물 정보 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnimalResponse {

    private Long id;
    private String apmsNoticeNo;
    private String species;  // DOG, CAT, ETC
    private String breed;
    private String gender;  // MALE, FEMALE, UNKNOWN
    private Integer birthYear;
    private Integer age;
    private String specialMark;
    private String status;  // PROTECT, ADOPTED, etc
    private LocalDate noticeEndDate;
    private String imageUrl;
    private Integer favoriteCount;
    private Long shelterId;
    private String shelterName;
    private LocalDateTime createdAt;
}