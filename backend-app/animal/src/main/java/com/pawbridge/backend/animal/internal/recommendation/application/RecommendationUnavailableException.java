package com.pawbridge.backend.animal.internal.recommendation.application;

public final class RecommendationUnavailableException extends RuntimeException {
    public RecommendationUnavailableException() {
        super("추천 자료를 준비 중입니다. 잠시 후 다시 시도해 주세요");
    }
}
