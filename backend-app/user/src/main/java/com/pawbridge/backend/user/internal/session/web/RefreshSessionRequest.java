package com.pawbridge.backend.user.internal.session.web;

import jakarta.validation.constraints.NotBlank;

public record RefreshSessionRequest(@NotBlank(message="리프레시 토큰은 필수입니다.") String refreshToken) {}
