package com.pawbridge.backend.animal.internal.command.infrastructure.repository;

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal;
import jakarta.persistence.LockModeType;
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
}
