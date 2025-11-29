package com.pawbridge.userservice.email.util;

import java.security.SecureRandom;

public class CodeGenerator {

    private static final SecureRandom random = new SecureRandom();

    /**
     * 숫자 인증 코드 생성
     */
    public static String generateNumeric(int length) {
        int bound = (int) Math.pow(10, length);
        int code = random.nextInt(bound);
        return String.format("%0" + length + "d", code);
    }
}
