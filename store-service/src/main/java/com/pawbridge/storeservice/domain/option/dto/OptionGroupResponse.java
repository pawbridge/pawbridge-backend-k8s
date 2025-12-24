package com.pawbridge.storeservice.domain.option.dto;

import com.pawbridge.storeservice.domain.product.entity.OptionGroup;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
@Builder
public class OptionGroupResponse {
    private Long id;
    private String name;
    private List<OptionValueResponse> values;

    public static OptionGroupResponse from(OptionGroup optionGroup) {
        return OptionGroupResponse.builder()
                .id(optionGroup.getId())
                .name(optionGroup.getName())
                .values(optionGroup.getOptionValues().stream()
                        .map(OptionValueResponse::from)
                        .collect(Collectors.toList()))
                .build();
    }
}
