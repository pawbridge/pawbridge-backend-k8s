package com.pawbridge.animalservice.chatbot.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

@Service
public class ChatbotClientIpResolver {

    public String resolve(HttpServletRequest request) {
        String cloudflareIp = firstNonBlank(request.getHeader("CF-Connecting-IP"));
        if (cloudflareIp != null) {
            return cloudflareIp;
        }

        String forwardedFor = firstNonBlank(request.getHeader("X-Forwarded-For"));
        if (forwardedFor != null) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = firstNonBlank(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }

        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    private String firstNonBlank(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
