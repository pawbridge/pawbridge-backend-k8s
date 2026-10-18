package com.pawbridge.storeservice.domain.product.repository;

import com.pawbridge.storeservice.domain.product.entity.OptionValue;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OptionValueRepository extends JpaRepository<OptionValue, Long> {
    
    @Query("SELECT COUNT(sv) > 0 FROM SKUValue sv WHERE sv.optionValue.id = :optionValueId")
    boolean isOptionValueInUse(Long optionValueId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ov FROM OptionValue ov WHERE ov.id = :id")
    Optional<OptionValue> findByIdWithLock(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ov FROM OptionValue ov WHERE ov.id IN :ids ORDER BY ov.id")
    List<OptionValue> findAllByIdWithLock(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ov FROM OptionValue ov WHERE ov.optionGroup.id = :groupId ORDER BY ov.id")
    List<OptionValue> findAllByOptionGroupIdWithLock(@Param("groupId") Long groupId);
}
