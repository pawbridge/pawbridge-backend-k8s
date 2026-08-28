package com.pawbridge.storeservice.domain.product.repository;

import com.pawbridge.storeservice.domain.product.entity.OptionGroup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OptionGroupRepository extends JpaRepository<OptionGroup, Long> {
    boolean existsByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT og FROM OptionGroup og WHERE og.id = :id")
    Optional<OptionGroup> findByIdWithLock(@Param("id") Long id);
}
