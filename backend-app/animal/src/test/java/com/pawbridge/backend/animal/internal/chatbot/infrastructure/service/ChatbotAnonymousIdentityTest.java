package com.pawbridge.backend.animal.internal.chatbot.infrastructure.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ChatbotAnonymousIdentityTest {
    @ParameterizedTest @ValueSource(strings={"01234567-89ab-cdef-0123-456789abcdef","1-1-1-1-1"})
    void given__uuid_parsable_cookie__when__resolving__then__reuse_original_value_without_reissuing_or_canonicalizing(String anonymous) {
        var request=new MockHttpServletRequest();request.setCookies(new Cookie(ChatbotCookieService.COOKIE_NAME,anonymous));
        var response=new MockHttpServletResponse();
        assertEquals(anonymous,new ChatbotCookieService().resolveAnonymousSessionId(request,response));assertNull(response.getHeader("Set-Cookie"));
    }
    static Stream<Arguments> newCookies(){return Stream.of(Arguments.of(null,false,null,false),Arguments.of("broken",false,null,false),
            Arguments.of("",true,null,true),Arguments.of(" ",false,"HTTPS",true));}
    @ParameterizedTest @MethodSource("newCookies")
    void given__missing_or_invalid_cookie_and_transport__when__resolving__then__issue_uuid_http_only_lax_root_cookie_for_180_days(String value,boolean secure,String proto,boolean expectedSecure) {
        var request=new MockHttpServletRequest();if(value!=null)request.setCookies(new Cookie(ChatbotCookieService.COOKIE_NAME,value));
        request.setSecure(secure);if(proto!=null)request.addHeader("X-Forwarded-Proto",proto);var response=new MockHttpServletResponse();
        String anonymous=new ChatbotCookieService().resolveAnonymousSessionId(request,response);UUID.fromString(anonymous);
        String cookie=response.getHeader("Set-Cookie");assertNotNull(cookie);assertTrue(cookie.startsWith("pawbridge_anon_id="+anonymous));
        assertTrue(cookie.contains("Path=/"));assertTrue(cookie.contains("Max-Age=15552000"));assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Lax"));
        assertEquals(expectedSecure,cookie.contains("; Secure"));
    }
    static Stream<Arguments> ips(){return Stream.of(
            Arguments.of(" 192.0.2.1 ","192.0.2.2, 192.0.2.3","192.0.2.4","192.0.2.5","192.0.2.1"),
            Arguments.of(" "," 192.0.2.2, 192.0.2.3 ","192.0.2.4","192.0.2.5","192.0.2.2"),
            Arguments.of(null," "," 192.0.2.4 ","192.0.2.5","192.0.2.4"),
            Arguments.of(null,null,null,"192.0.2.5","192.0.2.5"),Arguments.of(null,null,null,null,"unknown"));}
    @ParameterizedTest @MethodSource("ips")
    void given__original_proxy_headers_and_remote_address__when__resolving__then__preserve_cf_forwarded_real_remote_precedence(String cf,String forwarded,String real,String remote,String expected) {
        var request=mock(HttpServletRequest.class);when(request.getHeader("CF-Connecting-IP")).thenReturn(cf);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwarded);when(request.getHeader("X-Real-IP")).thenReturn(real);when(request.getRemoteAddr()).thenReturn(remote);
        assertEquals(expected,new ChatbotClientIpResolver().resolve(request));
    }
    @Test void given__known_hmac_sha256_vector__when__hashing__then__return_original_lowercase_hex() {
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                new ChatbotIpHashService("key").hash("The quick brown fox jumps over the lazy dog"));
    }
    @ParameterizedTest @NullSource @ValueSource(strings={""," "})
    void given__blank_hash_secret__when__constructing_then_hashing__then__preserve_request_time_failure(String secret) {
        var hashes=assertDoesNotThrow(()->new ChatbotIpHashService(secret));
        assertEquals("chatbot.security.ip-hash-secret 설정이 필요합니다.",assertThrows(IllegalStateException.class,()->hashes.hash("192.0.2.1")).getMessage());
    }
}
