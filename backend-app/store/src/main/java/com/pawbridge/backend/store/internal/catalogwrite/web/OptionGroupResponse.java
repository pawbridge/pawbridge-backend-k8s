package com.pawbridge.backend.store.internal.catalogwrite.web;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OptionGroup;
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
