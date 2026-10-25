package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.javaIsBlank
import java.net.URI
import org.springframework.core.env.Environment

/** Settings exist only for the explicitly enabled APMS schedule or manual API. Never render the service key. */
class ApmsScheduleSettings(environment: Environment) {
    private val workers: Int
    private val transactionTimeout: Int
    private val maxRequests: Int
    private val maxAnimals: Int
    private val serviceKey: String
    private val baseUri: URI
    private val cron: String

    init {
        workers = environment.getProperty("apms.batch.workers", Int::class.javaObjectType, 1)
        transactionTimeout = environment.getProperty("apms.batch.step-timeout-seconds", Int::class.javaObjectType, 15)
        maxRequests = environment.getProperty("apms.sync.max-requests", Int::class.javaObjectType, 100)
        maxAnimals = environment.getProperty("apms.sync.max-animals", Int::class.javaObjectType, 50000)
        if (workers < 1 || workers > 4) {
            throw IllegalArgumentException("APMS workers must be between one and four")
        }
        if (transactionTimeout < 1 || maxRequests < 1 || maxAnimals < 1) {
            throw IllegalArgumentException("APMS timeout and collection limits must be positive")
        }
        val configuredKey = environment.getProperty("apms.api.service-key")
        if (configuredKey == null || javaIsBlank(configuredKey) || configuredKey.any { Character.isISOControl(it) }) {
            throw IllegalArgumentException("APMS service key is required")
        }
        serviceKey = configuredKey
        baseUri = ApmsHttpClient.baseUri(environment.getProperty("apms.api.base-url"))
        cron = environment.getProperty("apms.batch.collection-cron", "0 */30 * * * *")
    }

    fun workers(): Int = workers

    fun transactionTimeout(): Int = transactionTimeout

    fun maxRequests(): Int = maxRequests

    fun maxAnimals(): Int = maxAnimals

    fun serviceKey(): String = serviceKey

    fun baseUri(): URI = baseUri

    fun cron(): String = cron

    fun requireCapacity(maximumPoolSize: Int) {
        if (maximumPoolSize < workers + 2) {
            throw IllegalArgumentException("APMS writer capacity must cover workers, session lock and metadata")
        }
    }
}
