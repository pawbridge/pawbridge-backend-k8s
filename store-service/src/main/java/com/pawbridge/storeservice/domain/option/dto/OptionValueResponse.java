package com.pawbridge.storeservice.domain.option.dto;

import com.pawbridge.storeservice.domain.product.entity.OptionValue;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OptionValueResponse {
    private Long id;
    private String name;
    private Long groupId;
    private String groupName;

    public static OptionValueResponse from(OptionValue optionValue) {
        return OptionValueResponse.builder()
                .id(optionValue.getId())
                .name(optionValue.getName())
                .groupId(optionValue.getOptionGroup().getId())
                .groupName(optionValue.getOptionGroup().getName())
                .build();
    }
}
