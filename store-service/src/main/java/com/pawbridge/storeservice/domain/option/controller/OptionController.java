package com.pawbridge.storeservice.domain.option.controller;

import com.pawbridge.storeservice.domain.option.dto.OptionGroupRequest;
import com.pawbridge.storeservice.domain.option.dto.OptionGroupResponse;
import com.pawbridge.storeservice.domain.option.dto.OptionValueRequest;
import com.pawbridge.storeservice.domain.option.dto.OptionValueResponse;
import com.pawbridge.storeservice.domain.option.service.OptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 옵션 관리 API 컨트롤러 (관리자용)
 * - 옵션 그룹/값 CRUD
 */
@RestController
@RequestMapping("/api/option-groups")
@RequiredArgsConstructor
public class OptionController {

    private final OptionService optionService;

    // ==================== 옵션 그룹 ====================

    /**
     * 전체 옵션 그룹 목록 조회
     */
    @GetMapping
    public ResponseEntity<List<OptionGroupResponse>> getAllOptionGroups() {
        return ResponseEntity.ok(optionService.getAllOptionGroups());
    }

    /**
     * 옵션 그룹 상세 조회
     */
    @GetMapping("/{groupId}")
    public ResponseEntity<OptionGroupResponse> getOptionGroup(@PathVariable Long groupId) {
        return ResponseEntity.ok(optionService.getOptionGroup(groupId));
    }

    /**
     * 옵션 그룹 생성 (옵션 값도 함께 추가 가능)
     */
    @PostMapping
    public ResponseEntity<OptionGroupResponse> createOptionGroup(@RequestBody OptionGroupRequest request) {
        OptionGroupResponse response = optionService.createOptionGroup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 옵션 그룹 수정
     */
    @PutMapping("/{groupId}")
    public ResponseEntity<OptionGroupResponse> updateOptionGroup(
            @PathVariable Long groupId,
            @RequestBody OptionGroupRequest request) {
        return ResponseEntity.ok(optionService.updateOptionGroup(groupId, request));
    }

    /**
     * 옵션 그룹 삭제
     */
    @DeleteMapping("/{groupId}")
    public ResponseEntity<Void> deleteOptionGroup(@PathVariable Long groupId) {
        optionService.deleteOptionGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    // ==================== 옵션 값 ====================

    /**
     * 옵션 값 추가
     */
    @PostMapping("/{groupId}/values")
    public ResponseEntity<OptionValueResponse> addOptionValue(
            @PathVariable Long groupId,
            @RequestBody OptionValueRequest request) {
        OptionValueResponse response = optionService.addOptionValue(groupId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 옵션 값 수정
     */
    @PutMapping("/values/{valueId}")
    public ResponseEntity<OptionValueResponse> updateOptionValue(
            @PathVariable Long valueId,
            @RequestBody OptionValueRequest request) {
        return ResponseEntity.ok(optionService.updateOptionValue(valueId, request));
    }

    /**
     * 옵션 값 삭제
     */
    @DeleteMapping("/values/{valueId}")
    public ResponseEntity<Void> deleteOptionValue(@PathVariable Long valueId) {
        optionService.deleteOptionValue(valueId);
        return ResponseEntity.noContent().build();
    }
}
