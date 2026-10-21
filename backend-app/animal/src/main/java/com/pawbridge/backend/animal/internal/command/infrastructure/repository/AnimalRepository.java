package com.pawbridge.backend.animal.internal.command.infrastructure.repository;

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnimalRepository extends JpaRepository<Animal, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Animal a where a.id = :id")
    Optional<Animal> findByIdForUpdate(@Param("id") Long id);

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :day)", nativeQuery = true)
    int lockManualNoticePrefix(@Param("namespace") int namespace, @Param("day") int day);

    @Query(value = "SELECT COALESCE(MAX(CAST(substring(apms_notice_no FROM :pattern) AS integer)), 0) "
            + "FROM pawbridge_animal.animals WHERE apms_notice_no LIKE :prefix", nativeQuery = true)
    int findMaxManualNoticeSuffix(@Param("prefix") String prefix, @Param("pattern") String pattern);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"shelter"})
    java.util.List<Animal> findWithShelterByIdIn(java.util.List<Long> ids);
    @Query("SELECT MIN(a.happenDate) FROM Animal a WHERE a.apiSource = :source")
    LocalDate findEarliestHappenDate(@Param("source") ApiSource source);

    @Query("SELECT a.apmsDesertionNo, a.id FROM Animal a WHERE a.apmsDesertionNo IS NOT NULL")
    List<Object[]> findAllApmsDesertionNoAndId();

    /**
     * APMS 배치 기존 Animal 직접 UPDATE - SELECT 없이 id 기반 UPDATE만 실행
     * - merge()의 SELECT → UPDATE 2단계를 UPDATE 1단계로 단축
     * - @Modifying(clearAutomatically = true): 쿼리 후 1차 캐시 초기화로 스테일 방지
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("UPDATE Animal a SET " +
            "a.breed = :breed, a.birthYear = :birthYear, a.weight = :weight, " +
            "a.color = :color, a.gender = :gender, a.neuterStatus = :neuterStatus, " +
            "a.specialMark = :specialMark, a.apmsProcessState = :apmsProcessState, " +
            "a.noticeStartDate = :noticeStartDate, a.noticeEndDate = :noticeEndDate, " +
            "a.apmsUpdatedAt = :apmsUpdatedAt, a.happenDate = :happenDate, " +
            "a.happenPlace = :happenPlace, a.imageUrl = :imageUrl, a.imageUrl2 = :imageUrl2, " +
            "a.shelter = :shelter, a.status = :status WHERE a.id = :id")
    int updateAnimalFromApms(
            @Param("id") Long id,
            @Param("shelter") Shelter shelter,
            @Param("breed") String breed,
            @Param("birthYear") Integer birthYear,
            @Param("weight") String weight,
            @Param("color") String color,
            @Param("gender") com.pawbridge.backend.animal.internal.command.infrastructure.enums.Gender gender,
            @Param("neuterStatus") com.pawbridge.backend.animal.internal.command.infrastructure.enums.NeuterStatus neuterStatus,
            @Param("specialMark") String specialMark,
            @Param("apmsProcessState") String apmsProcessState,
            @Param("noticeStartDate") java.time.LocalDate noticeStartDate,
            @Param("noticeEndDate") java.time.LocalDate noticeEndDate,
            @Param("apmsUpdatedAt") java.time.LocalDateTime apmsUpdatedAt,
            @Param("happenDate") java.time.LocalDate happenDate,
            @Param("happenPlace") String happenPlace,
            @Param("imageUrl") String imageUrl,
            @Param("imageUrl2") String imageUrl2,
            @Param("status") com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus status
    );
}
