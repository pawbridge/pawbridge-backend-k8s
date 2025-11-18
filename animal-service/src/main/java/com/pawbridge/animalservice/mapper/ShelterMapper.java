package com.pawbridge.animalservice.mapper;

import com.pawbridge.animalservice.dto.request.CreateShelterRequest;
import com.pawbridge.animalservice.dto.response.ShelterDetailResponse;
import com.pawbridge.animalservice.dto.response.ShelterResponse;
import com.pawbridge.animalservice.entity.Shelter;
import org.springframework.stereotype.Component;

/**
 * Shelter Entity ↔ DTO 변환 매퍼
 */
@Component
public class ShelterMapper {

    // Request DTO → Entity

    /**
     * CreateShelterRequest → Shelter Entity
     * - Builder 패턴 사용
     * @param request 요청 DTO
     * @return Shelter 엔티티
     */
    public Shelter toEntity(CreateShelterRequest request) {
        return Shelter.builder()
                .careRegNo(request.getCareRegNo())
                .name(request.getName())
                .phone(request.getPhone())
                .address(request.getAddress())
                .ownerName(request.getOwnerName())
                .organizationName(request.getOrganizationName())
                .email(request.getEmail())
                .introduction(request.getIntroduction())
                .adoptionProcedure(request.getAdoptionProcedure())
                .operatingHours(request.getOperatingHours())
                .build();
    }

    // Entity → Response DTO

    /**
     * Shelter Entity → ShelterResponse (목록 조회용)
     * @param shelter 보호소 엔티티
     * @return ShelterResponse DTO
     */
    public ShelterResponse toResponse(Shelter shelter) {
        return ShelterResponse.builder()
                .id(shelter.getId())
                .careRegNo(shelter.getCareRegNo())
                .name(shelter.getName())
                .phone(shelter.getPhone())
                .address(shelter.getAddress())
                .organizationName(shelter.getOrganizationName())
                .createdAt(shelter.getCreatedAt())
                .build();
    }

    /**
     * Shelter Entity → ShelterDetailResponse (상세 조회용)
     * @param shelter 보호소 엔티티
     * @return ShelterDetailResponse DTO
     */
    public ShelterDetailResponse toDetailResponse(Shelter shelter) {
        return ShelterDetailResponse.builder()
                .id(shelter.getId())
                .careRegNo(shelter.getCareRegNo())
                .name(shelter.getName())
                .phone(shelter.getPhone())
                .address(shelter.getAddress())
                .ownerName(shelter.getOwnerName())
                .organizationName(shelter.getOrganizationName())
                .email(shelter.getEmail())
                .introduction(shelter.getIntroduction())
                .adoptionProcedure(shelter.getAdoptionProcedure())
                .operatingHours(shelter.getOperatingHours())
                .createdAt(shelter.getCreatedAt())
                .updatedAt(shelter.getUpdatedAt())
                .build();
    }
}
