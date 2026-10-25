package com.pawbridge.backend.animal.internal.photoarchive.infrastructure

import java.net.URI

/**
 * Bound by Spring Boot through the JavaBean getters/setters (`@ConfigurationProperties` on a `@Bean` method),
 * so every property stays a mutable `var` on a class with a default constructor, as the Lombok original was.
 */
class PhotoArchiveProperties {
    var bucket: String? = null
    var optimizerUrl: URI? = null
    var internalApiKey: String? = null

    // Java Set.of(...): immutable default; Boot's binder replaces it with a new set when a value is configured.
    var allowedHosts: Set<String>? = java.util.Set.of("openapi.animal.go.kr")
    var scanSize: Int = 50
    var maxPhotos: Int = 25
    var maxRunSeconds: Int = 300
    var leaseSeconds: Int = 300
    var recheckSeconds: Int = 7 * 24 * 3600
    var intervalMs: Long = 900000
    var initialDelayMs: Long = 60000

    fun validate() {
        val bucket = bucket
        val optimizerUrl = optimizerUrl
        val internalApiKey = internalApiKey
        val allowedHosts = allowedHosts
        // Set.of(...).contains(null) throws NullPointerException for a URI without a scheme; keep the Java set.
        val webSchemes: Set<String> = java.util.Set.of("http", "https")
        if (bucket == null || isBlank(bucket) || optimizerUrl == null ||
            internalApiKey == null || isBlank(internalApiKey) ||
            optimizerUrl.host == null || optimizerUrl.userInfo != null ||
            optimizerUrl.query != null || optimizerUrl.fragment != null ||
            !webSchemes.contains(optimizerUrl.scheme) ||
            allowedHosts == null || allowedHosts.isEmpty() ||
            scanSize < 1 || scanSize > 500 || maxPhotos < 1 || maxPhotos > 1000 ||
            maxRunSeconds < 1 || maxRunSeconds > 3600 ||
            intervalMs < 1000 || intervalMs > 86400000 || initialDelayMs < 0 || initialDelayMs > 86400000 ||
            leaseSeconds < 300 || leaseSeconds > 3600 || recheckSeconds < 3600
        ) {
            throw IllegalArgumentException("Invalid APMS photo archive configuration")
        }
    }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }
}
