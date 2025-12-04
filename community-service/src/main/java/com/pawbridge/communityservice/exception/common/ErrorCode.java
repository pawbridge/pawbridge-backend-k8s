package com.pawbridge.communityservice.exception.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 커뮤니티 서비스 에러 코드 관리
 */
@Getter
public enum ErrorCode {

    // POST
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),
    POST_ALREADY_DELETED(HttpStatus.BAD_REQUEST, "이미 삭제된 게시글입니다."),
    UNAUTHORIZED_POST_ACCESS(HttpStatus.FORBIDDEN, "게시글에 접근할 권한이 없습니다."),

    // COMMENT
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
    COMMENT_ALREADY_DELETED(HttpStatus.BAD_REQUEST, "이미 삭제된 댓글입니다."),
    UNAUTHORIZED_COMMENT_ACCESS(HttpStatus.FORBIDDEN, "댓글에 접근할 권한이 없습니다."),

    // S3
    S3_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 업로드에 실패했습니다."),
    S3_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 삭제에 실패했습니다."),
    INVALID_IMAGE_FORMAT(HttpStatus.BAD_REQUEST, "지원하지 않는 이미지 형식입니다."),
    IMAGE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "이미지 크기가 너무 큽니다."),

    // ELASTICSEARCH
    SEARCH_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "검색 서비스를 사용할 수 없습니다."),
    ELASTICSEARCH_INDEX_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "검색 인덱싱에 실패했습니다."),

    // KAFKA
    KAFKA_PUBLISH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이벤트 발행에 실패했습니다."),
    EVENT_PROCESSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이벤트 처리에 실패했습니다."),

    // VALIDATION
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    MISSING_REQUIRED_FIELD(HttpStatus.BAD_REQUEST, "필수 입력값이 누락되었습니다."),

    // SYSTEM
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 에러가 발생했습니다."),
    DATABASE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "데이터베이스 에러가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
