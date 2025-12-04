package com.pawbridge.communityservice.exception.common;

import com.pawbridge.communityservice.util.ResponseDTO;
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
 * 글로벌 에러 핸들러
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionRestAdvice {

    @ExceptionHandler
    public ResponseEntity<ResponseDTO<Void>> applicationException(ApplicationException e) {
        log.error(e.getMessage(), e);
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(ResponseDTO.error(e.getErrorCode()));
    }

    @ExceptionHandler
    public ResponseEntity<ResponseDTO<Void>> bindException(BindException e) {
        log.error(e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ResponseDTO.errorWithMessage(HttpStatus.BAD_REQUEST,
                        e.getBindingResult().getAllErrors().get(0).getDefaultMessage()));
    }

    @ExceptionHandler
    public ResponseEntity<ResponseDTO<Void>> dbException(DataAccessException e) {
        log.error(e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResponseDTO.errorWithMessage(HttpStatus.INTERNAL_SERVER_ERROR, "서버 에러!"));
    }

    @ExceptionHandler
    public ResponseEntity<ResponseDTO<Void>> serverException(RuntimeException e) {
        log.error(e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResponseDTO.errorWithMessage(HttpStatus.INTERNAL_SERVER_ERROR, "서버 에러!"));
    }

    /**
     * MethodArgumentNotValidException 경우에 대한 에러 커스텀 핸들링 코드
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseDTO<Void>> handleValidationExceptions(
            MethodArgumentNotValidException e) {

        BindingResult bindingResult = e.getBindingResult();

        List<String> fieldErrors = bindingResult.getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.toList());

        log.error(e.getMessage(), e);
        String errorMessage = String.join(", ", fieldErrors);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ResponseDTO.errorWithMessage(HttpStatus.BAD_REQUEST, errorMessage));
    }

    /**
     * NoHandlerFoundException 경우에 대한 에러 커스텀 핸들링 코드
     * 존재하지 않는 API 엔드포인트에 대한 요청을 처리
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ResponseDTO<Void>> handleNoHandlerFoundException(NoHandlerFoundException e) {
        log.error("No handler found for {}: {}", e.getHttpMethod(), e.getRequestURL(), e);
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ResponseDTO.errorWithMessage(HttpStatus.NOT_FOUND, "유효하지 않은 엔드포인트입니다."));
    }

    /**
     * 요청 본문(Request Body)의 형식이 올바르지 않아 메시지를 읽을 수 없을 때 발생하는
     * {@code HttpMessageNotReadableException}을 처리
     * (예: JSON 파싱 오류, 잘못된 형식의 요청 데이터)
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ResponseDTO<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.error("Bad Request Body: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ResponseDTO.errorWithMessage(HttpStatus.BAD_REQUEST, "요청 본문 형식이 올바르지 않습니다."));
    }

    /**
     * 특정 URL에 대해 지원되지 않는 HTTP 메소드(예: GET만 허용되는 곳에 POST 요청)로 요청이 들어올 때 발생하는
     * {@code HttpRequestMethodNotSupportedException}을 처리
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ResponseDTO<Void>> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.error("Method Not Allowed: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ResponseDTO.errorWithMessage(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메소드입니다."));
    }

    /**
     * {@code @RequestParam} 등으로 지정된 필수 요청 파라미터가 클라이언트 요청에 누락되었을 때 발생하는
     * {@code MissingServletRequestParameterException}을 처리
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ResponseDTO<Void>> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.error("Missing Request Parameter: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ResponseDTO.errorWithMessage(HttpStatus.BAD_REQUEST,
                        String.format("필수 요청 파라미터 '%s'(이)가 누락되었습니다.", e.getParameterName())));
    }

    /**
     * 요청 파라미터나 경로 변수의 데이터 타입이 예상과 다를 때 발생하는
     * {@code TypeMismatchException}을 처리
     * (예: 숫자 타입이어야 하는데 문자열이 전달된 경우)
     */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ResponseDTO<Void>> handleTypeMismatchException(TypeMismatchException e) {
        log.error("Type Mismatch: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ResponseDTO.errorWithMessage(HttpStatus.BAD_REQUEST, "요청 파라미터 타입이 올바르지 않습니다."));
    }
}
