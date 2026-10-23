package com.pawbridge.backend.user.internal.oauth.infrastructure;

import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException;
import java.util.Map;

/**
 * Provider-neutral view of the user-info response.
 *
 * <p>Google returns a flat map ({@code sub}, {@code email}, {@code name}). Naver wraps the same facts in
 * {@code response} ({@code id}, {@code email}, {@code name}); its {@code id} is unique per Naver application.
 */
record SocialProfile(String provider, String providerId, String email, String name) {
    static final String GOOGLE = "GOOGLE";
    static final String NAVER = "NAVER";

    static SocialProfile from(String registrationId, Map<String, Object> attributes) {
        if ("google".equalsIgnoreCase(registrationId)) {
            return withEmail(GOOGLE, text(attributes, "sub"), text(attributes, "email"), text(attributes, "name"));
        }
        if ("naver".equalsIgnoreCase(registrationId)) {
            Map<?, ?> response = attributes.get("response") instanceof Map<?, ?> nested ? nested : Map.of();
            String providerId = text(response, "id");
            if (providerId == null || providerId.isEmpty()) {
                throw new SocialLoginRejectedException("로그인 처리 중 오류가 발생했습니다");
            }
            return withEmail(NAVER, providerId, text(response, "email"), text(response, "name"));
        }
        throw new SocialLoginRejectedException("지원하지 않는 로그인 방식입니다: " + registrationId);
    }

    boolean identifiedByProviderId() {
        return NAVER.equals(provider);
    }

    private static SocialProfile withEmail(String provider, String providerId, String email, String name) {
        if (email == null || email.isEmpty()) {
            throw new SocialLoginRejectedException("이메일 정보를 가져올 수 없습니다");
        }
        return new SocialProfile(provider, providerId, email, name);
    }

    private static String text(Map<?, ?> source, String key) {
        return source.get(key) instanceof String value ? value : null;
    }
}
