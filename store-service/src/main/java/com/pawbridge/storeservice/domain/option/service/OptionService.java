package com.pawbridge.storeservice.domain.option.service;

import com.pawbridge.storeservice.domain.option.dto.OptionGroupRequest;
import com.pawbridge.storeservice.domain.option.dto.OptionGroupResponse;
import com.pawbridge.storeservice.domain.option.dto.OptionValueRequest;
import com.pawbridge.storeservice.domain.option.dto.OptionValueResponse;
import com.pawbridge.storeservice.domain.product.entity.OptionGroup;
import com.pawbridge.storeservice.domain.product.entity.OptionValue;
import com.pawbridge.storeservice.domain.product.repository.OptionGroupRepository;
import com.pawbridge.storeservice.domain.product.repository.OptionValueRepository;
import com.pawbridge.storeservice.domain.product.repository.ProductRepository;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import com.pawbridge.storeservice.domain.product.service.ProductOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 옵션 그룹/값 관리 서비스 (표준화용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptionService {

    private final OptionGroupRepository optionGroupRepository;
    private final OptionValueRepository optionValueRepository;
    private final ProductRepository productRepository;
    private final ProductSKURepository productSKURepository;
    private final ProductOutboxService productOutboxService;

    // ==================== 옵션 그룹 ====================

    @Transactional(readOnly = true)
    public List<OptionGroupResponse> getAllOptionGroups() {
        return optionGroupRepository.findAll().stream()
                .map(OptionGroupResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OptionGroupResponse getOptionGroup(Long groupId) {
        OptionGroup group = optionGroupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: " + groupId));
        return OptionGroupResponse.from(group);
    }

    @Transactional
    public OptionGroupResponse createOptionGroup(OptionGroupRequest request) {
        // 중복 체크
        if (optionGroupRepository.existsByName(request.getName())) {
            throw new IllegalStateException("이미 존재하는 옵션 그룹입니다: " + request.getName());
        }

        OptionGroup group = OptionGroup.builder()
                .name(request.getName())
                .build();
        optionGroupRepository.save(group);

        // 옵션 값도 함께 생성
        if (request.getValues() != null && !request.getValues().isEmpty()) {
            for (String valueName : request.getValues()) {
                OptionValue value = OptionValue.builder()
                        .optionGroup(group)
                        .name(valueName)
                        .build();
                optionValueRepository.save(value);
                group.getOptionValues().add(value);
            }
        }

        log.info(">>> [OPTION] 옵션 그룹 생성: name={}", request.getName());
        return OptionGroupResponse.from(group);
    }

    @Transactional
    public OptionGroupResponse updateOptionGroup(Long groupId, OptionGroupRequest request) {
        OptionGroup group = optionGroupRepository.findByIdWithLock(groupId)
                .orElseThrow(() -> new IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: " + groupId));
        optionValueRepository.findAllByOptionGroupIdWithLock(groupId);
        List<Long> affectedProductIds = productSKURepository.findProductIdsByOptionGroupId(groupId);

        group.updateName(request.getName());
        publishAffectedProductSnapshots(affectedProductIds);
        log.info(">>> [OPTION] 옵션 그룹 수정: id={}, name={}", groupId, request.getName());
        return OptionGroupResponse.from(group);
    }

    @Transactional
    public void deleteOptionGroup(Long groupId) {
        OptionGroup group = optionGroupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: " + groupId));

        // 사용 중인 옵션 그룹인지 확인 (SKUValue에서 참조 중인지)
        boolean isInUse = group.getOptionValues().stream()
                .anyMatch(ov -> optionValueRepository.isOptionValueInUse(ov.getId()));
        if (isInUse) {
            throw new IllegalStateException("사용 중인 옵션 그룹은 삭제할 수 없습니다.");
        }

        optionGroupRepository.delete(group);
        log.info(">>> [OPTION] 옵션 그룹 삭제: id={}", groupId);
    }

    // ==================== 옵션 값 ====================

    @Transactional
    public OptionValueResponse addOptionValue(Long groupId, OptionValueRequest request) {
        OptionGroup group = optionGroupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: " + groupId));

        OptionValue value = OptionValue.builder()
                .optionGroup(group)
                .name(request.getName())
                .build();
        optionValueRepository.save(value);
        group.getOptionValues().add(value);

        log.info(">>> [OPTION] 옵션 값 추가: groupId={}, name={}", groupId, request.getName());
        return OptionValueResponse.from(value);
    }

    @Transactional
    public OptionValueResponse updateOptionValue(Long valueId, OptionValueRequest request) {
        OptionValue value = optionValueRepository.findByIdWithLock(valueId)
                .orElseThrow(() -> new IllegalArgumentException("옵션 값을 찾을 수 없습니다: " + valueId));
        List<Long> affectedProductIds = productSKURepository.findProductIdsByOptionValueId(valueId);

        value.updateName(request.getName());
        publishAffectedProductSnapshots(affectedProductIds);
        log.info(">>> [OPTION] 옵션 값 수정: id={}, name={}", valueId, request.getName());
        return OptionValueResponse.from(value);
    }

    @Transactional
    public void deleteOptionValue(Long valueId) {
        OptionValue value = optionValueRepository.findById(valueId)
                .orElseThrow(() -> new IllegalArgumentException("옵션 값을 찾을 수 없습니다: " + valueId));

        // 사용 중인지 확인
        if (optionValueRepository.isOptionValueInUse(valueId)) {
            throw new IllegalStateException("사용 중인 옵션 값은 삭제할 수 없습니다.");
        }

        optionValueRepository.delete(value);
        log.info(">>> [OPTION] 옵션 값 삭제: id={}", valueId);
    }

    private void publishAffectedProductSnapshots(List<Long> affectedProductIds) {
        affectedProductIds.stream()
                .sorted()
                .forEach(productId -> {
                    var product = productRepository.findByIdWithLock(productId)
                            .orElseThrow(() -> new IllegalStateException("옵션에 연결된 상품을 찾을 수 없습니다: " + productId));
                    var lockedSkus = productSKURepository.findAllByProductIdWithLock(productId);
                    if (!lockedSkus.isEmpty()) {
                        productOutboxService.publishProductSnapshot(product);
                    }
                });
    }
}
