package com.pawbridge.userservice.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DataFormatter {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    /**
     * LocalDateTime 객체를 "yyyy.MM.dd" 형식의 문자열로 변환합니다.
     * @param createdAt 변환할 LocalDateTime 객체
     * @return 포맷된 문자열
     */
    public static String getFormattedCreatedAt(LocalDateTime createdAt) {
        if (createdAt == null) {
            return null;
        }
        return createdAt.format(DATE_FORMATTER);
    }

    /**
     * LocalDateTime 객체를 "yyyy.MM.dd HH:mm" 형식의 문자열로 변환합니다.
     * @param createdAt 변환할 LocalDateTime 객체
     * @return 포맷된 문자열
     */
    public static String getFormattedCreatedAtWithTime(LocalDateTime createdAt) {
        if (createdAt == null) {
            return null;
        }
        return createdAt.format(DATE_TIME_FORMATTER);
    }

    /**
     * LocalDate 객체를 "yyyy.MM.dd" 형식의 문자열로 변환합니다.
     * @param date 변환할 LocalDate 객체
     * @return 포맷된 문자열
     */
    public static String getFormattedDate(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.format(DATE_FORMATTER);
    }
}