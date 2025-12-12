package com.pawbridge.communityservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * User Service와 통신하는 Feign Client
 */
@FeignClient(name = "user-service")
public interface UserServiceClient {

    /**
     * 사용자 닉네임 조회
     * @param userId 사용자 ID
     * @return 사용자 닉네임
     */
    @GetMapping("/api/v1/users/internal/{userId}/nickname")
    String getUserNickname(@PathVariable("userId") Long userId);
}
