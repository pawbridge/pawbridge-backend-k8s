package com.pawbridge.animalservice.exception;

public class RecommendationUnavailableException extends ApplicationException {
    public RecommendationUnavailableException() {
        super(ErrorCode.RECOMMENDATION_UNAVAILABLE);
    }
}
