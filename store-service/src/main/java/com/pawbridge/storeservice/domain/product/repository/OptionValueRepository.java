package com.pawbridge.storeservice.domain.product.repository;

import com.pawbridge.storeservice.domain.product.entity.OptionValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OptionValueRepository extends JpaRepository<OptionValue, Long> {
    
    @Query("SELECT COUNT(sv) > 0 FROM SKUValue sv WHERE sv.optionValue.id = :optionValueId")
    boolean isOptionValueInUse(Long optionValueId);
}

