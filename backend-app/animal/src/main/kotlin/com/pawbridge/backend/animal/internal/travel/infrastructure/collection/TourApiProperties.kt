package com.pawbridge.backend.animal.internal.travel.infrastructure.collection

/**
 * Bound by Spring Boot through the JavaBean getters/setters (`@ConfigurationProperties` on a `@Bean` method).
 * The default constructor and the setter names/validation stay as in the Java class.
 */
class TourApiProperties {
    // isEnabled/setEnabled and isBulkPetEnabled/setBulkPetEnabled keep the JavaBean accessor names.
    var isEnabled: Boolean = false
    var serviceKey: String = ""
    var isBulkPetEnabled: Boolean = false

    var koreanServiceKey: String = ""
        get() = if (isBlank(field)) bulkServiceKey else field

    // Compatibility alias for the environment variable already deployed by Kubernetes.
    var bulkServiceKey: String = ""

    var maxBulkPagesPerRun: Int = 10
        set(value) {
            if (value < 1 || value > 100) {
                throw IllegalArgumentException("Bulk page limit must be 1..100")
            }
            field = value
        }

    // Per-operation daily ceiling. Failed attempts consume budget; not a provider entitlement.
    var dailyRequestLimit: Int = 900
        set(value) {
            if (value < 1 || value > 1000) {
                throw IllegalArgumentException("TourAPI budget must be between 1 and 1000")
            }
            field = value
        }

    var maxPagesPerRun: Int = 10
        set(value) {
            if (value < 1 || value > 100) {
                throw IllegalArgumentException("Page limit must be 1..100")
            }
            field = value
        }

    var maxDetailsPerRun: Int = 18
        set(value) {
            if (value < 1 || value > 100) {
                throw IllegalArgumentException("Detail limit must be 1..100")
            }
            field = value
        }

    var maxVisitDetailsPerRun: Int = 18
        set(value) {
            if (value < 1 || value > 100) {
                throw IllegalArgumentException("Visit detail limit must be 1..100")
            }
            field = value
        }

    var detailRefreshDays: Int = 14
        set(value) {
            if (value < 1) {
                throw IllegalArgumentException("Detail refresh days must be positive")
            }
            field = value
        }

    // Java String.isBlank(): empty or only Character.isWhitespace characters (Kotlin isBlank also accepts NBSP).
    private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }
}
