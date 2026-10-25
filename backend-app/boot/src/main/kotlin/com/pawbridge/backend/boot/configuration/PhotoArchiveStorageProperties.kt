package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.javaIsBlank
import java.net.URI
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bound by Spring Boot through the JavaBean getters/setters, so every property stays a mutable `var`
 * on a class with a default constructor (no constructor binding).
 */
@ConfigurationProperties("apms-photo-archive.storage")
class PhotoArchiveStorageProperties {
    var endpoint: URI? = null
    var region: String? = "auto"
    var accessKeyId: String? = null
    var secretAccessKey: String? = null

    fun validate() {
        val endpoint = endpoint
        val region = region
        val accessKeyId = accessKeyId
        val secretAccessKey = secretAccessKey
        if (endpoint == null || endpoint.host == null ||
            !"https".equals(endpoint.scheme) && !"http".equals(endpoint.scheme) ||
            endpoint.userInfo != null || endpoint.query != null || endpoint.fragment != null ||
            region == null || javaIsBlank(region) ||
            accessKeyId == null || javaIsBlank(accessKeyId) ||
            secretAccessKey == null || javaIsBlank(secretAccessKey)
        ) {
            throw IllegalArgumentException("Dedicated APMS photo storage configuration is required")
        }
    }
}
