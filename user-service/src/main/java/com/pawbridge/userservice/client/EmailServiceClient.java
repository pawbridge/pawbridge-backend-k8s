package com.pawbridge.userservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "email-service",
        url = "${email-service.url:http://localhost:8088}"
)
public interface EmailServiceClient {

    @GetMapping("/internal/email/verified")
    Boolean isEmailVerified(@RequestParam("email") String email);

    @DeleteMapping("/internal/email/verified")
    void clearVerification(@RequestParam("email") String email);
}
