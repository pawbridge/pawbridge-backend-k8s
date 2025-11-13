package com.pawbridge.animalservice.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 애플리케이션 에러 코드 정의
 * - 모든 커스텀 예외의 에러 코드를 중앙에서 관리
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Animal 관련 에러 (404)
    ANIMAL_NOT_FOUND("ANIMAL_NOT_FOUND", "동물을 찾을 수 없습니다", HttpStatus.NOT_FOUND),

    // Shelter 관련 에러 (404)
    SHELTER_NOT_FOUND("SHELTER_NOT_FOUND", "보호소를 찾을 수 없습니다", HttpStatus.NOT_FOUND),

    // Validation 에러 (400)
    INVALID_INPUT_VALUE("INVALID_INPUT_VALUE", "입력값이 올바르지 않습니다", HttpStatus.BAD_REQUEST),
    INVALID_ARGUMENT("INVALID_ARGUMENT", "잘못된 인자가 전달되었습니다", HttpStatus.BAD_REQUEST),

    // 권한 에러 (403)
    PERMISSION_DENIED("PERMISSION_DENIED", "접근 권한이 없습니다", HttpStatus.FORBIDDEN),

    // 서버 에러 (500)
    INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
