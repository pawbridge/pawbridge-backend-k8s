package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.request.CreateShelterRequest;
import com.pawbridge.animalservice.dto.request.UpdateShelterRequest;
import com.pawbridge.animalservice.dto.response.ShelterDetailResponse;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.mapper.ShelterMapper;
import com.pawbridge.animalservice.repository.ShelterRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shelter Command Service (CUD)
 * - 보호소 생성, 수정, 삭제 담당
 * - 쓰기 작업 트랜잭션 관리
 * - DTO 기반 처리
 */
@Service
@RequiredArgsConstructor
public class ShelterCommandService {

    private final ShelterRepository shelterRepository;
    private final ShelterMapper mapper;

    /**
     * 보호소 생성
     * @param request 보호소 등록 요청 DTO
     * @return ShelterDetailResponse
     */
    @Transactional
    public ShelterDetailResponse create(CreateShelterRequest request) {
        // DTO → Entity 변환
        Shelter shelter = mapper.toEntity(request);

        // 저장
        Shelter saved = shelterRepository.save(shelter);

        // Entity → Response DTO
        return mapper.toDetailResponse(saved);
    }

    /**
     * APMS 데이터로부터 보호소 생성 (배치 전용)
     * - 중복 체크 포함
     * - ApmsShelterMapper에서 Entity를 만들어서 전달
     * @param shelter 보호소 Entity
     * @return 저장된 Shelter
     * @throws IllegalStateException 이미 존재하는 보호소일 때
     */
    @Transactional
    public Shelter createFromApms(Shelter shelter) {
        // 중복 체크
        if (shelterRepository.existsByCareRegNo(shelter.getCareRegNo())) {
            throw new IllegalStateException("Shelter already exists: " + shelter.getCareRegNo());
        }

        return shelterRepository.save(shelter);
    }

    /**
     * 보호소 정보 수정 (보호소 회원이 직접 수정)
     * @param id 보호소 ID
     * @param request 수정 요청 DTO
     * @return ShelterDetailResponse
     * @throws EntityNotFoundException 보호소가 없을 때
     */
    @Transactional
    public ShelterDetailResponse update(Long id, UpdateShelterRequest request) {
        Shelter shelter = shelterRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Shelter not found: " + id));

        // Entity의 비즈니스 메서드 활용
        shelter.updateInfo(
                request.getPhone(),
                request.getEmail(),
                request.getIntroduction(),
                request.getAdoptionProcedure(),
                request.getOperatingHours()
        );

        return mapper.toDetailResponse(shelter);
    }

    /**
     * 보호소 삭제
     * @param id 보호소 ID
     * @throws EntityNotFoundException 보호소가 없을 때
     */
    @Transactional
    public void delete(Long id) {
        if (!shelterRepository.existsById(id)) {
            throw new EntityNotFoundException("Shelter not found: " + id);
        }
        shelterRepository.deleteById(id);
    }
}
