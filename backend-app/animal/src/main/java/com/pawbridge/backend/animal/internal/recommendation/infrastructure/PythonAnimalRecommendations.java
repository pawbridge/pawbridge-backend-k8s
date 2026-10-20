package com.pawbridge.backend.animal.internal.recommendation.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException;
import feign.Client;
import feign.Feign;
import feign.Headers;
import feign.Logger;
import feign.Param;
import feign.Request;
import feign.RequestLine;
import feign.Retryer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Selected GET from PythonLostSearchClient; neither image inference nor legacy search is imported. */
public final class PythonAnimalRecommendations {
    private final Endpoint endpoint;
    private final String internalKey;

    public PythonAnimalRecommendations(String baseUrl, String internalKey, ObjectMapper mapper) {
        this(baseUrl, internalKey, mapper, new Client.Default(null, null));
    }

    PythonAnimalRecommendations(String baseUrl, String internalKey, ObjectMapper mapper, Client client) {
        this.internalKey = Objects.requireNonNull(internalKey);
        this.endpoint = Feign.builder().client(client)
                .options(new Request.Options(3, TimeUnit.SECONDS, 45, TimeUnit.SECONDS, false))
                .retryer(Retryer.NEVER_RETRY).logLevel(Logger.Level.NONE)
                .decoder((response, type) -> response.body() == null ? null
                        : mapper.readValue(response.body().asInputStream(), new TypeReference<List<Long>>() { }))
                .target(Endpoint.class, baseUrl);
    }

    public List<Long> recommend(long animalId, String species) {
        if (internalKey.isBlank()) throw new RecommendationUnavailableException();
        try {
            return endpoint.recommend(internalKey, animalId, species);
        } catch (Exception failure) {
            // Feign failures may contain the key, URI and response body. Do not retain or log them.
            throw new RecommendationUnavailableException();
        }
    }

    interface Endpoint {
        @RequestLine("GET /internal/animals/{animalId}/similar?species={species}")
        @Headers("X-Internal-Api-Key: {key}")
        List<Long> recommend(@Param("key") String key, @Param("animalId") long animalId,
                             @Param("species") String species);
    }
}
