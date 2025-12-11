package com.pawbridge.userservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * animal-service에서 받아오는 보호소 정보 DTO
 * - animal-service의 ShelterResponse와 동일한 구조
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShelterResponse {
    private Long id;
    private String careRegNo;
    private String name;
    private String phone;
    private String address;
    private String organizationName;
    private LocalDateTime createdAt;
}
