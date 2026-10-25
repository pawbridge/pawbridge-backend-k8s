package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure

import java.time.LocalDate

/** Original ShelterPublicInformation.details write patch; reads stay in JdbcShelterReads. */
internal object ShelterPublicDetails {
    // The Java source iterated Map.of(), whose order is unspecified; each key is independent, so a fixed order is equivalent.
    private val TEXT_KEYS = linkedMapOf(
        "careTel" to "phone",
        "weekOprStime" to "weekdayOpen",
        "weekOprEtime" to "weekdayClose",
        "weekendOprStime" to "weekendOpen",
        "weekendOprEtime" to "weekendClose",
        "closeDay" to "closedDays",
    )

    @JvmStatic
    fun details(row: Map<String, String>): MutableMap<String, Any?> {
        val values = LinkedHashMap<String, Any?>()
        for ((key, value) in TEXT_KEYS) {
            if (row.containsKey(key)) {
                values[value] = row[key]
            }
        }
        try {
            // Double.parseDouble(null) throws NullPointerException, which is ignored like a malformed number.
            val lat = java.lang.Double.parseDouble(row["lat"])
            val lng = java.lang.Double.parseDouble(row["lng"])
            if (lat.isFinite() && lng.isFinite() && lat >= 32 && lat <= 39.5 && lng >= 124 && lng <= 132) {
                values["latitude"] = lat
                values["longitude"] = lng
            }
        } catch (ignored: RuntimeException) {
            // Missing or invalid coordinates do not replace saved coordinates.
        }
        try {
            values["sourceUpdatedDate"] = LocalDate.parse(row["dataStdDt"]).toString()
        } catch (ignored: RuntimeException) {
            // A missing or invalid source date is simply not recorded.
        }
        return values
    }
}
