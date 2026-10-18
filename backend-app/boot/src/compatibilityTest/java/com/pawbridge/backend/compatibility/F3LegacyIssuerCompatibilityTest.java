package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.jwt.JwtProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/** Generates the HTTP test tokens with the exact hash-pinned existing issuer. */
class F3LegacyIssuerCompatibilityTest {
    @Test
    void givenPinnedExistingIssuer__whenIssuingSyntheticRoleTokens__thenHostedPackagedChecksUseThoseTokens()
            throws Exception {
        try (F3PostgresqlFixture fixture = F3PostgresqlFixture.open()) {
            String secret = F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");
            SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            JwtProvider issuer = new JwtProvider(secret, 600_000, 600_000);
            Map<String, String> tokens = new LinkedHashMap<>();
            for (Role role : Role.values()) {
                User user = User.builder().userId(42L).email("synthetic@fixture.invalid")
                        .name("합성회원").nickname("합성작성자").role(role).careRegNo("F3-SYNTHETIC").build();
                String token = issuer.createAccessToken(user);
                var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
                assertThat(claims.get("userId", Long.class)).isEqualTo(42L);
                assertThat(claims.get("role", String.class)).isEqualTo(role.name());
                assertThat(claims.getSubject()).isEqualTo("synthetic@fixture.invalid");
                tokens.put(role.name(), token);
            }
            tokens.put("refresh", issuer.createRefreshToken());
            User user = User.builder().userId(42L).email("synthetic@fixture.invalid")
                    .name("합성회원").role(Role.ROLE_USER).build();
            tokens.put("expired", new JwtProvider(secret, -60_000, 600_000).createAccessToken(user));
            tokens.put("forged", new JwtProvider("f3-wrong-synthetic-secret-at-least-sixty-four-characters-0123456789012345",
                    600_000, 600_000).createAccessToken(user));
            tokens.put("missingId", signed(key, Map.of("role", "ROLE_USER")));
            tokens.put("zeroId", signed(key, Map.of("userId", 0L, "role", "ROLE_USER")));
            tokens.put("negativeId", signed(key, Map.of("userId", -1L, "role", "ROLE_USER")));
            tokens.put("stringId", signed(key, Map.of("userId", "42", "role", "ROLE_USER")));
            tokens.put("missingRole", signed(key, Map.of("userId", 42L)));
            tokens.put("numericRole", signed(key, Map.of("userId", 42L, "role", 42)));
            Path output = Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
            String owner = F3PostgresqlFixture.required("F3_CI_OWNER");
            assertThat(output).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP"))
                    .toAbsolutePath().normalize().resolve("f3-tokens-" + owner + ".json"));
            Files.writeString(output, new ObjectMapper().writeValueAsString(tokens));
            Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static String signed(SecretKey key, Map<String, Object> claims) {
        return Jwts.builder().claims(claims).issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(600))).signWith(key).compact();
    }
}
