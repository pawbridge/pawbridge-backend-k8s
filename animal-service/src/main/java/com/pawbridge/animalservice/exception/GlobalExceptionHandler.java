package com.pawbridge.animalservice.exception;

import com.pawbridge.animalservice.util.ResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 전역 예외 처리 핸들러
 * - @RestControllerAdvice로 모든 컨트롤러의 예외를 중앙에서 처리
 * - 일관된 에러 응답 형식 제공 (ResponseDto)
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * ApplicationException 처리
     * - 모든 커스텀 예외를 하나의 핸들러로 처리
     * - ErrorCode에서 HTTP 상태와 에러 메시지 추출
     */
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ResponseDto<Void>> handleApplicationException(ApplicationException ex) {
        ErrorCode errorCode = ex.getErrorCode();

        log.warn("Application exception occurred: code={}, message={}",
                errorCode.getCode(), ex.getMessage());

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ResponseDto.errorWithMessage(errorCode.getHttpStatus(), ex.getMessage()));
    }

    /**
     * MethodArgumentNotValidException 처리
     * - @Valid 검증 실패 시 400 반환
     * - 필드별 에러 메시지 수집
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseDto<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();

        List<String> fieldErrors = bindingResult.getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.toList());

        String errorMessage = String.join(", ", fieldErrors);
        log.warn("Validation error: {}", errorMessage);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ResponseDto.errorWithMessage(HttpStatus.BAD_REQUEST, errorMessage));
    }

    /**
     * BindException 처리
     * - 데이터 바인딩 실패 시 400 반환
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ResponseDto<Void>> handleBindException(BindException ex) {
        String errorMessage = ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        log.warn("Bind error: {}", errorMessage);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ResponseDto.errorWithMessage(HttpStatus.BAD_REQUEST, errorMessage));
    }

    /**
     * IllegalArgumentException 처리
     * - 잘못된 인자 전달 시 400 반환
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseDto<Void>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ResponseDto.errorWithMessage(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    /**
     * NoHandlerFoundException 처리
     * - 존재하지 않는 API 엔드포인트 요청 시 404 반환
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ResponseDto<Void>> handleNoHandlerFoundException(NoHandlerFoundException ex) {
        log.warn("No handler found for {} {}", ex.getHttpMethod(), ex.getRequestURL());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ResponseDto.errorWithMessage(HttpStatus.NOT_FOUND, "유효하지 않은 엔드포인트입니다."));
    }

    /**
     * HttpMessageNotReadableException 처리
     * - 요청 본문 형식이 올바르지 않을 때 400 반환
     * - 예: JSON 파싱 오류, 잘못된 형식의 요청 데이터
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ResponseDto<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        log.warn("Bad request body: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ResponseDto.errorWithMessage(HttpStatus.BAD_REQUEST, "요청 본문 형식이 올바르지 않습니다."));
    }

    /**
     * HttpRequestMethodNotSupportedException 처리
     * - 지원되지 않는 HTTP 메소드 요청 시 405 반환
     * - 예: GET만 허용되는 곳에 POST 요청
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ResponseDto<Void>> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not allowed: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ResponseDto.errorWithMessage(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메소드입니다."));
    }

    /**
     * MissingServletRequestParameterException 처리
     * - 필수 요청 파라미터 누락 시 400 반환
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ResponseDto<Void>> handleMissingServletRequestParameterException(MissingServletRequestParameterException ex) {
        String errorMessage = String.format("필수 요청 파라미터 '%s'(이)가 누락되었습니다.", ex.getParameterName());
        log.warn("Missing request parameter: {}", errorMessage);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ResponseDto.errorWithMessage(HttpStatus.BAD_REQUEST, errorMessage));
    }

    /**
     * TypeMismatchException 처리
     * - 요청 파라미터 타입 불일치 시 400 반환
     * - 예: 숫자 타입이어야 하는데 문자열 전달
     */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ResponseDto<Void>> handleTypeMismatchException(TypeMismatchException ex) {
        log.warn("Type mismatch: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ResponseDto.errorWithMessage(HttpStatus.BAD_REQUEST, "요청 파라미터 타입이 올바르지 않습니다."));
    }

    /**
     * DataAccessException 처리
     * - 데이터베이스 관련 예외 발생 시 500 반환
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ResponseDto<Void>> handleDataAccessException(DataAccessException ex) {
        log.error("Database error: {}", ex.getMessage(), ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResponseDto.errorWithMessage(HttpStatus.INTERNAL_SERVER_ERROR, "데이터베이스 오류가 발생했습니다."));
    }

    /**
     * Exception 처리
     * - 처리되지 않은 모든 예외는 500 반환
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDto<Void>> handleException(Exception ex) {
        log.error("Unexpected error occurred: {}", ex.getMessage(), ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResponseDto.errorWithMessage(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."));
    }
}
