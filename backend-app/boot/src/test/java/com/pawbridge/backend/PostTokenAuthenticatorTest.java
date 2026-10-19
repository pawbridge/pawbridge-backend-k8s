package com.pawbridge.backend;

import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import io.jsonwebtoken.Jwts;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PostTokenAuthenticatorTest {
    private final PostTokenAuthenticator tokens = new PostTokenAuthenticator(F3TestInputs.KEY);

    @ParameterizedTest
    @ValueSource(strings={"ROLE_USER","ROLE_ADMIN","ROLE_SHELTER",""," ","ROLE_UNKNOWN","ROLE_ADMIN "})
    void given__signed_access_identity__when__authenticating__then__use_claim_user_id_for_every_existing_role(String role) {
        assertThat(tokens.authenticate("Bearer "+F3TestInputs.token(7,role))).isEqualTo(7);
    }

    @Test
    void given__missing_or_non_bearer_header__when__authenticating__then__keep_missing_token_message() {
        for(String header : new String[]{null,"Basic ignored","bearer ignored"})
            assertThatThrownBy(()->tokens.authenticate(header)).isInstanceOf(PostTokenAuthenticator.Failure.class)
                    .hasMessage("인증 토큰이 필요합니다.");
    }

    @Test
    void given__invalid_or_expired_signature__when__authenticating__then__do_not_echo_token_details() {
        String expired = Jwts.builder().claim("userId",7).claim("role","ROLE_USER")
                .expiration(new Date(1)).signWith(F3TestInputs.KEY).compact();
        for(String token:new String[]{"", "private-token-detail", expired,
                "eyJhbGciOiJub25lIn0.eyJ1c2VySWQiOjd9."})
            assertThatThrownBy(()->tokens.authenticate("Bearer "+token))
                    .hasMessage("유효하지 않거나 만료된 토큰입니다.");
    }

    @ParameterizedTest
    @MethodSource("invalidIdentity")
    void given__signed_but_invalid_identity__when__authenticating__then__reject_as_claim_parsing_failure(Map<String,Object> claims) {
        String token=Jwts.builder().claims(claims).expiration(new Date(System.currentTimeMillis()+3_600_000))
                .signWith(F3TestInputs.KEY).compact();
        assertThatThrownBy(()->tokens.authenticate("Bearer "+token)).hasMessage("토큰 파싱에 실패했습니다.");
    }

    static Stream<Map<String,Object>> invalidIdentity() {
        return Stream.of(Map.of(),Map.of("role","ROLE_USER"),Map.of("userId",0,"role","ROLE_USER"),
                Map.of("userId",-1,"role","ROLE_USER"),Map.of("userId","not-a-number","role","ROLE_USER"),
                Map.of("userId",7),Map.of("userId",7,"role",Map.of("unexpected",true)));
    }

    @Test
    void givenSignedIdentityWithoutExpiry__whenAuthenticatingAnExistingRestContract__thenKeepItsOptionalExpiryPolicy() {
        String token = Jwts.builder().claim("userId", 7).claim("role", "ROLE_UNKNOWN").signWith(F3TestInputs.KEY).compact();
        assertThat(tokens.authenticate("Bearer " + token)).isEqualTo(7);
    }
}
