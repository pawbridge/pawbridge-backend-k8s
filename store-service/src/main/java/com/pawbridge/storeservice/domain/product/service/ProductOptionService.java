package com.pawbridge.storeservice.domain.product.service;

import com.pawbridge.storeservice.domain.product.dto.OptionGroupCreateDto;
import com.pawbridge.storeservice.domain.product.entity.OptionGroup;
import com.pawbridge.storeservice.domain.product.entity.OptionValue;
import com.pawbridge.storeservice.domain.product.entity.Product;
import com.pawbridge.storeservice.domain.product.repository.OptionGroupRepository;
import com.pawbridge.storeservice.domain.product.repository.OptionValueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Product Option Service
 * - 옵션 그룹 및 옵션 값 관리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductOptionService {

    private final OptionGroupRepository optionGroupRepository;
    private final OptionValueRepository optionValueRepository;

    /**
     * 옵션 그룹 및 옵션 값 생성
     * @param product 상품 엔티티
     * @param optionGroups 옵션 그룹 DTO 목록
     * @return "그룹명:값명" -> OptionValue 맵 (SKU 연결용)
     */
    public Map<String, OptionValue> createOptions(Product product, List<OptionGroupCreateDto> optionGroups) {
        Map<String, OptionValue> optionValueMap = new HashMap<>();
        
        if (optionGroups == null || optionGroups.isEmpty()) {
            return optionValueMap;
        }
        
        for (OptionGroupCreateDto groupDto : optionGroups) {
            OptionGroup group = OptionGroup.builder()
                    .product(product)
                    .name(groupDto.getName())
                    .build();
            optionGroupRepository.save(group);

            for (String valName : groupDto.getValues()) {
                OptionValue value = OptionValue.builder()
                        .optionGroup(group)
                        .name(valName)
                        .build();
                optionValueRepository.save(value);
                optionValueMap.put(groupDto.getName() + ":" + valName, value);
            }
        }
        
        log.debug(">>> [OPTION] 옵션 생성 완료: productId={}, 옵션 수={}", 
                product.getId(), optionValueMap.size());
        
        return optionValueMap;
    }
}
