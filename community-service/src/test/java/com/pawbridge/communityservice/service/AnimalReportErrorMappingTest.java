package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class AnimalReportErrorMappingTest {
    private final GlobalExceptionRestAdvice advice = new GlobalExceptionRestAdvice();

    @Test
    void givenInvalidReport_whenHandled_thenReturnBadRequestNotServerError() {
        var response = advice.responseStatus(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "사진은 최대 5장까지 첨부할 수 있습니다."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("사진은 최대 5장까지 첨부할 수 있습니다.");
    }
}
