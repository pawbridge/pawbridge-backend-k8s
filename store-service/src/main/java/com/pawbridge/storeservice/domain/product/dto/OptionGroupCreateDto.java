package com.pawbridge.storeservice.domain.product.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

@Getter
@NoArgsConstructor
public class OptionGroupCreateDto {
    private String name; // 예: Color
    private List<String> values; // 예: [Red, Blue]
}
