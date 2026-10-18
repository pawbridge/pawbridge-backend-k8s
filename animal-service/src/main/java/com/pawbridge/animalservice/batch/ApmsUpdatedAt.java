package com.pawbridge.animalservice.batch;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;

/** APMS timestamps may omit fractions or contain up to nanosecond precision. */
public final class ApmsUpdatedAt {
    private static final DateTimeFormatter FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).toFormatter().withResolverStyle(ResolverStyle.STRICT);

    private ApmsUpdatedAt() { }

    public static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, FORMAT);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("APMS update timestamp is invalid");
        }
    }

    public static String normalize(String value) {
        LocalDateTime parsed = parse(value);
        return parsed == null ? null : parsed.format(FORMAT);
    }
}
