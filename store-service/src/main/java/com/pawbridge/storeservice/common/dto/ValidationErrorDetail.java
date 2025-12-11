package com.pawbridge.storeservice.common.dto;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ValidationErrorDetail {
    private String field;
    private String value;
    private String reason;

    private ValidationErrorDetail(final String field, final String value, final String reason) {
        this.field = field;
        this.value = value;
        this.reason = reason;
    }

    public static List<ValidationErrorDetail> of(final String field, final String value, final String reason) {
        List<ValidationErrorDetail> details = new ArrayList<>();
        details.add(new ValidationErrorDetail(field, value, reason));
        return details;
    }

    public static List<ValidationErrorDetail> of(final BindingResult bindingResult) {
        final List<FieldError> fieldErrors = bindingResult.getFieldErrors();
        return fieldErrors.stream()
                .map(error -> new ValidationErrorDetail(
                        error.getField(),
                        error.getRejectedValue() == null ? "" : error.getRejectedValue().toString(),
                        error.getDefaultMessage()))
                .collect(Collectors.toList());
    }
}
