package com.pawbridge.storeservice.domain.option.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class OptionGroupRequest {
    private String name;
    private List<String> values; // 옵션 값 목록 (생성 시 함께 추가 가능)
}
