package com.pawbridge.animalservice.chatbot.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatbotIpHashService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Value("${chatbot.security.ip-hash-secret:}")
    private String ipHashSecret;

    public String hash(String clientIp) {
        if (ipHashSecret == null || ipHashSecret.isBlank()) {
            throw new IllegalStateException("chatbot.security.ip-hash-secret 설정이 필요합니다.");
        }

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(ipHashSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(clientIp.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("IP hash 생성에 실패했습니다.", e);
        }
    }
}
