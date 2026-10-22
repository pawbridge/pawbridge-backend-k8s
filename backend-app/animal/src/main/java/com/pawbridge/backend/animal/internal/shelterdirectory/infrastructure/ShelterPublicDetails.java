package com.pawbridge.backend.animal.internal.shelterdirectory.infrastructure;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Original ShelterPublicInformation.details write patch; reads stay in JdbcShelterReads. */
final class ShelterPublicDetails {
    private ShelterPublicDetails() { }
    static Map<String, Object> details(Map<String, String> row) {
        var values = new LinkedHashMap<String, Object>();
        Map.of("careTel", "phone", "weekOprStime", "weekdayOpen", "weekOprEtime", "weekdayClose",
                "weekendOprStime", "weekendOpen", "weekendOprEtime", "weekendClose", "closeDay", "closedDays")
                .forEach((key, value) -> { if (row.containsKey(key)) values.put(value, row.get(key)); });
        try {
            double lat = Double.parseDouble(row.get("lat")), lng = Double.parseDouble(row.get("lng"));
            if (Double.isFinite(lat) && Double.isFinite(lng) && lat >= 32 && lat <= 39.5 && lng >= 124 && lng <= 132) {
                values.put("latitude", lat); values.put("longitude", lng);
            }
        } catch (RuntimeException ignored) { /* Missing or invalid coordinates do not replace saved coordinates. */ }
        try { values.put("sourceUpdatedDate", LocalDate.parse(row.get("dataStdDt")).toString()); }
        catch (RuntimeException ignored) { }
        return values;
    }
}
