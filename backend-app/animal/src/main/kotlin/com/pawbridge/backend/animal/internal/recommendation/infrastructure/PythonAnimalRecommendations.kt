package com.pawbridge.backend.animal.internal.recommendation.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException
import feign.Client
import feign.Feign
import feign.Headers
import feign.Logger
import feign.Param
import feign.Request
import feign.RequestLine
import feign.Retryer
import feign.codec.Decoder
import java.util.concurrent.TimeUnit

/** Selected GET from PythonLostSearchClient; neither image inference nor legacy search is imported. */
class PythonAnimalRecommendations internal constructor(
    baseUrl: String,
    internalKey: String,
    mapper: ObjectMapper,
    client: Client,
) {
    private val endpoint: Endpoint
    private val internalKey: String = internalKey

    constructor(baseUrl: String, internalKey: String, mapper: ObjectMapper) :
        this(baseUrl, internalKey, mapper, Client.Default(null, null))

    init {
        endpoint = Feign.builder().client(client)
            .options(Request.Options(3, TimeUnit.SECONDS, 45, TimeUnit.SECONDS, false))
            .retryer(Retryer.NEVER_RETRY).logLevel(Logger.Level.NONE)
            .decoder(
                Decoder { response, _ ->
                    if (response.body() == null) {
                        null
                    } else {
                        mapper.readValue(response.body().asInputStream(), object : TypeReference<List<@JvmSuppressWildcards Long>>() {})
                    }
                },
            )
            .target(Endpoint::class.java, baseUrl)
    }

    fun recommend(animalId: Long, species: String?): List<Long?>? {
        if (isBlank(internalKey)) {
            throw RecommendationUnavailableException()
        }
        try {
            return endpoint.recommend(internalKey, animalId, species)
        } catch (failure: Exception) {
            // Feign failures may contain the key, URI and response body. Do not retain or log them.
            throw RecommendationUnavailableException()
        }
    }

    interface Endpoint {
        @RequestLine("GET /internal/animals/{animalId}/similar?species={species}")
        @Headers("X-Internal-Api-Key: {key}")
        fun recommend(
            @Param("key") key: String,
            @Param("animalId") animalId: Long,
            @Param("species") species: String?,
        ): List<Long?>?
    }

    private companion object {
        // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }
    }
}
