package com.pawbridge.animalservice.exception;

/**
 * 동시에 여러 요청(예: 수동 재인덱싱 동시 호출)이 들어왔을 때 발생하는 예외
 * HTTP 409 Conflict 반환
 */
public class ConcurrentRequestException extends ApplicationException {
    public ConcurrentRequestException() {
        super(ErrorCode.CONCURRENT_REQUEST);
    }
}
