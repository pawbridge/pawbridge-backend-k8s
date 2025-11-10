package com.pawbridge.animalservice.repository;

import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.Species;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Animal 엔티티 리포지토리
 * - 동물 정보 CRUD 및 검색 쿼리
 * - N+1 문제 방지: 단건 상세는 @EntityGraph, 목록은 DTO 프로젝션 사용
 */
@Repository
public interface AnimalRepository extends JpaRepository<Animal, Long> {

    // ========== 기본 조회 (UNIQUE KEY) ==========

    /**
     * APMS 유기번호로 조회 (UNIQUE)
     * - 배치 작업에서 중복 체크용
     * @param apmsDesertionNo APMS 유기번호
     * @return Animal Optional
     */
    Optional<Animal> findByApmsDesertionNo(String apmsDesertionNo);

    /**
     * APMS 유기번호 존재 여부 확인
     * @param apmsDesertionNo APMS 유기번호
     * @return 존재 여부
     */
    boolean existsByApmsDesertionNo(String apmsDesertionNo);

    // ========== 단건 상세 조회 (Shelter 정보 포함) ==========

    /**
     * ID로 동물 조회 (Shelter fetch join)
     * - 상세 페이지용
     * - @EntityGraph로 N+1 방지
     * @param id 동물 ID
     * @return Animal Optional with Shelter
     */
    @EntityGraph(attributePaths = {"shelter"})
    Optional<Animal> findWithShelterById(Long id);

    /**
     * APMS 유기번호로 조회 (Shelter fetch join)
     * @param apmsDesertionNo APMS 유기번호
     * @return Animal Optional with Shelter
     */
    @EntityGraph(attributePaths = {"shelter"})
    Optional<Animal> findWithShelterByApmsDesertionNo(String apmsDesertionNo);

    // ========== 목록 조회 (페이징) - Shelter 불필요 ==========

    /**
     * 축종별 조회
     * - Shelter 정보 불필요 시 사용
     * @param species 축종
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    Page<Animal> findBySpecies(Species species, Pageable pageable);

    /**
     * 상태별 조회
     * @param status 상태
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    Page<Animal> findByStatus(AnimalStatus status, Pageable pageable);

    /**
     * 축종 + 성별 조회
     * @param species 축종
     * @param gender 성별
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    Page<Animal> findBySpeciesAndGender(Species species, Gender gender, Pageable pageable);

    /**
     * 축종 + 상태 조회
     * @param species 축종
     * @param status 상태
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    Page<Animal> findBySpeciesAndStatus(Species species, AnimalStatus status, Pageable pageable);

    /**
     * 여러 상태 중 하나에 해당하는 동물 조회
     * - 예: [NOTICE, PROTECT] → 공고중 또는 보호중
     * @param statuses 상태 목록
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    Page<Animal> findByStatusIn(List<AnimalStatus> statuses, Pageable pageable);

    // ========== 목록 조회 (Shelter 정보 필요) ==========
    // TODO: DTO 생성 후 프로젝션 방식으로 변경 권장
    //  @Query("SELECT new com.pawbridge.animalservice.dto.AnimalListDto(...) FROM Animal a JOIN a.shelter s")
    //  현재는 @EntityGraph 방식 사용 (Hibernate 경고 발생 가능)

    /**
     * 축종 + 상태 조회 (Shelter 포함)
     * - @EntityGraph로 N+1 방지
     * - Hibernate 경고 발생 가능 (HHH000104)
     * @param species 축종
     * @param status 상태
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    @EntityGraph(attributePaths = {"shelter"})
    @Query("SELECT a FROM Animal a WHERE a.species = :species AND a.status = :status")
    Page<Animal> findBySpeciesAndStatusWithShelter(
            @Param("species") Species species,
            @Param("status") AnimalStatus status,
            Pageable pageable
    );

    // ========== 보호소별 조회 ==========

    /**
     * 보호소 ID로 동물 목록 조회
     * @param shelterId 보호소 ID
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    Page<Animal> findByShelterId(Long shelterId, Pageable pageable);

    /**
     * 보호소 ID + 축종으로 조회
     * @param shelterId 보호소 ID
     * @param species 축종
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    Page<Animal> findByShelterIdAndSpecies(Long shelterId, Species species, Pageable pageable);

    /**
     * 보호소 ID + 상태로 조회
     * @param shelterId 보호소 ID
     * @param status 상태
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    Page<Animal> findByShelterIdAndStatus(Long shelterId, AnimalStatus status, Pageable pageable);

    // ========== 공고 관련 조회 ==========

    /**
     * 공고 중인 동물 (공고 종료일 임박 순)
     * - 메인 페이지용
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    @Query("SELECT a FROM Animal a WHERE a.status = 'NOTICE' ORDER BY a.noticeEndDate ASC")
    Page<Animal> findNoticeAnimalsOrderByNoticeEndDate(Pageable pageable);

    /**
     * 공고 종료일 범위 조회
     * @param startDate 시작일
     * @param endDate 종료일
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    @Query("SELECT a FROM Animal a WHERE a.noticeEndDate BETWEEN :startDate AND :endDate")
    Page<Animal> findByNoticeEndDateBetween(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable
    );

    /**
     * 공고 종료 임박 동물 조회 (D-3 이내)
     * @param today 오늘 날짜
     * @param deadline 임박 기준일 (오늘 + 3일)
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    @Query("SELECT a FROM Animal a WHERE a.status = 'NOTICE' AND a.noticeEndDate BETWEEN :today AND :deadline ORDER BY a.noticeEndDate ASC")
    Page<Animal> findExpiringSoonAnimals(
            @Param("today") LocalDate today,
            @Param("deadline") LocalDate deadline,
            Pageable pageable
    );

    // ========== 나이 범위 검색 ==========

    /**
     * 출생 연도 범위로 조회
     * - 나이 필터용: 사용자가 "1~3살" 선택 → birthYear BETWEEN (현재-3) AND (현재-1)
     * @param startYear 시작 연도
     * @param endYear 종료 연도
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    @Query("SELECT a FROM Animal a WHERE a.birthYear BETWEEN :startYear AND :endYear")
    Page<Animal> findByBirthYearBetween(
            @Param("startYear") Integer startYear,
            @Param("endYear") Integer endYear,
            Pageable pageable
    );

    /**
     * 축종 + 출생 연도 범위로 조회
     * @param species 축종
     * @param startYear 시작 연도
     * @param endYear 종료 연도
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    @Query("SELECT a FROM Animal a WHERE a.species = :species AND a.birthYear BETWEEN :startYear AND :endYear")
    Page<Animal> findBySpeciesAndBirthYearBetween(
            @Param("species") Species species,
            @Param("startYear") Integer startYear,
            @Param("endYear") Integer endYear,
            Pageable pageable
    );

    // ========== 텍스트 검색 ==========

    /**
     * 품종명으로 검색 (부분 일치)
     * @param breed 품종명 (예: "리트리버")
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    Page<Animal> findByBreedContaining(String breed, Pageable pageable);

    /**
     * 특징으로 검색 (부분 일치)
     * @param keyword 검색 키워드
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    Page<Animal> findBySpecialMarkContaining(String keyword, Pageable pageable);

    /**
     * 발견 장소로 검색 (부분 일치)
     * @param place 장소 (예: "남문동")
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    Page<Animal> findByHappenPlaceContaining(String place, Pageable pageable);

    // ========== 복합 검색 ==========
    // TODO: 복잡한 동적 쿼리는 Querydsl로 구현 예정

    /**
     * 축종 + 품종 검색
     * @param species 축종
     * @param breed 품종 키워드
     * @param pageable 페이징 정보
     * @return Page<Animal>
     */
    @Query("SELECT a FROM Animal a WHERE a.species = :species AND a.breed LIKE %:breed%")
    Page<Animal> findBySpeciesAndBreedContaining(
            @Param("species") Species species,
            @Param("breed") String breed,
            Pageable pageable
    );

    // ========== 통계 쿼리 ==========

    /**
     * 축종별 동물 수 카운트
     * @param species 축종
     * @return 개수
     */
    long countBySpecies(Species species);

    /**
     * 상태별 동물 수 카운트
     * @param status 상태
     * @return 개수
     */
    long countByStatus(AnimalStatus status);

    /**
     * 보호소별 동물 수 카운트
     * @param shelterId 보호소 ID
     * @return 개수
     */
    long countByShelterId(Long shelterId);

    /**
     * 축종 + 상태별 카운트
     * @param species 축종
     * @param status 상태
     * @return 개수
     */
    long countBySpeciesAndStatus(Species species, AnimalStatus status);

    // ========== 배치 작업용 ==========

    /**
     * 특정 날짜 이후 APMS에서 수정된 동물 조회
     * - APMS 동기화 시 증분 업데이트용
     * @param dateTime 기준 날짜시간
     * @return 동물 목록
     */
    @Query("SELECT a FROM Animal a WHERE a.apmsUpdatedAt > :dateTime")
    List<Animal> findByApmsUpdatedAtAfter(@Param("dateTime") LocalDateTime dateTime);

    /**
     * 공고 종료일이 지난 동물 조회
     * - 상태 자동 업데이트 배치용
     * @param today 오늘 날짜
     * @return 동물 목록
     */
    @Query("SELECT a FROM Animal a WHERE a.status = 'NOTICE' AND a.noticeEndDate < :today")
    List<Animal> findExpiredNoticeAnimals(@Param("today") LocalDate today);
}
