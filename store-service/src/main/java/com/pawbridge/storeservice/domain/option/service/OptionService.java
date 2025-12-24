package com.pawbridge.storeservice.domain.option.service;

import com.pawbridge.storeservice.domain.option.dto.OptionGroupRequest;
import com.pawbridge.storeservice.domain.option.dto.OptionGroupResponse;
import com.pawbridge.storeservice.domain.option.dto.OptionValueRequest;
import com.pawbridge.storeservice.domain.option.dto.OptionValueResponse;
import com.pawbridge.storeservice.domain.product.entity.OptionGroup;
import com.pawbridge.storeservice.domain.product.entity.OptionValue;
import com.pawbridge.storeservice.domain.product.repository.OptionGroupRepository;
import com.pawbridge.storeservice.domain.product.repository.OptionValueRepository;
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
        OptionGroup group = optionGroupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("옵션 그룹을 찾을 수 없습니다: " + groupId));

        group.updateName(request.getName());
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
        OptionValue value = optionValueRepository.findById(valueId)
                .orElseThrow(() -> new IllegalArgumentException("옵션 값을 찾을 수 없습니다: " + valueId));

        value.updateName(request.getName());
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
}
