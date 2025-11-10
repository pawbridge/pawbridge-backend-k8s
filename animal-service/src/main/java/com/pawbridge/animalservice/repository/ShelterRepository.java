package com.pawbridge.animalservice.repository;

import com.pawbridge.animalservice.entity.Shelter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Shelter 엔티티 리포지토리
 * - 보호소 정보 CRUD 및 검색 쿼리
 */
@Repository
public interface ShelterRepository extends JpaRepository<Shelter, Long> {

    // ========== 기본 조회 (UNIQUE KEY) ==========

    /**
     * APMS 보호소 등록번호로 조회 (UNIQUE)
     * - 배치 작업에서 Shelter 조회/생성 시 사용
     * @param careRegNo APMS 보호소 등록번호
     * @return Shelter Optional
     */
    Optional<Shelter> findByCareRegNo(String careRegNo);

    /**
     * APMS 보호소 등록번호 존재 여부 확인
     * @param careRegNo APMS 보호소 등록번호
     * @return 존재 여부
     */
    boolean existsByCareRegNo(String careRegNo);

    // ========== 텍스트 검색 (페이징) ==========

    /**
     * 보호소 이름으로 검색 (부분 일치)
     * - 사용자가 보호소 검색 시 사용
     * @param name 보호소 이름 (예: "창원")
     * @param pageable 페이징 정보
     * @return Page<Shelter>
     */
    Page<Shelter> findByNameContaining(String name, Pageable pageable);

    /**
     * 보호소 주소로 검색 (부분 일치)
     * - 지역별 보호소 검색
     * @param address 주소 키워드 (예: "경상남도", "창원시")
     * @param pageable 페이징 정보
     * @return Page<Shelter>
     */
    Page<Shelter> findByAddressContaining(String address, Pageable pageable);

    /**
     * 관할 기관으로 검색 (부분 일치)
     * @param organizationName 관할 기관명 (예: "창원시")
     * @param pageable 페이징 정보
     * @return Page<Shelter>
     */
    Page<Shelter> findByOrganizationNameContaining(String organizationName, Pageable pageable);

    // ========== 복합 검색 ==========

    /**
     * 이름 또는 주소로 검색
     * - 통합 검색용
     * @param name 보호소 이름 키워드
     * @param address 주소 키워드
     * @param pageable 페이징 정보
     * @return Page<Shelter>
     */
    @Query("SELECT s FROM Shelter s WHERE s.name LIKE %:name% OR s.address LIKE %:address%")
    Page<Shelter> findByNameOrAddress(
            @Param("name") String name,
            @Param("address") String address,
            Pageable pageable
    );

    // ========== 배치 작업용 ==========

    /**
     * 여러 개의 careRegNo로 Shelter 조회
     * - 배치 작업에서 대량의 Shelter를 한 번에 조회
     * - IN 절로 N+1 방지
     * @param careRegNos APMS 보호소 등록번호 목록
     * @return Shelter 목록
     */
    @Query("SELECT s FROM Shelter s WHERE s.careRegNo IN :careRegNos")
    List<Shelter> findByCareRegNoIn(@Param("careRegNos") List<String> careRegNos);

    // ========== 통계 쿼리 ==========

    /**
     * 전체 보호소 수 카운트
     * @return 보호소 개수
     */
    @Query("SELECT COUNT(s) FROM Shelter s")
    long countAllShelters();
}
