package com.pawbridge.backend;

import com.pawbridge.backend.boot.configuration.PostWriteSettings;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

final class F3TestInputs {
    static final String JWT_SECRET = "f3-synthetic-key-only-never-for-production-012345678901234567890123456789";
    static final SecretKey KEY = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));

    static Map<String,String> values() {
        var values = new HashMap<String,String>();
        values.put("PAWBRIDGE_DB_URL", "jdbc:postgresql://127.0.0.1:5432/pawbridge_f2_ci");
        values.put("PAWBRIDGE_DB_USER", "fixture-reader"); values.put("PAWBRIDGE_DB_PASSWORD", "synthetic-reader-only");
        values.put("PAWBRIDGE_JWT_SECRET", JWT_SECRET);
        values.put("PAWBRIDGE_STORAGE_ENDPOINT", "http://127.0.0.1:39001");
        values.put("PAWBRIDGE_STORAGE_BUCKET", "f3-test-bucket");
        values.put("PAWBRIDGE_STORAGE_PUBLIC_BASE_URL", "https://fixture.invalid");
        values.put("PAWBRIDGE_STORAGE_OBJECT_PREFIX", "f3-test/");
        values.put("PAWBRIDGE_STORAGE_ACCESS_KEY_ID", "synthetic-access");
        values.put("PAWBRIDGE_STORAGE_SECRET_ACCESS_KEY", "synthetic-secret-only");
        values.put("PAWBRIDGE_STORAGE_REGION", "us-east-1");
        return values;
    }

    static PostWriteSettings settings() {
        var inputs = values();
        return PostWriteSettings.fromEnvironment(inputs::get);
    }

    static String token(long userId, String role) {
        return Jwts.builder().subject("synthetic@example.invalid").claim("userId",userId).claim("role",role)
                .expiration(new Date(System.currentTimeMillis()+3_600_000)).signWith(KEY).compact();
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class SecurityInputs {
        @Bean PostTokenAuthenticator postTokenAuthenticator() { return new PostTokenAuthenticator(KEY); }
    }
}
