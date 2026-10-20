package com.pawbridge.backend.user.internal.oauth.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.backend.user.internal.oauth.application.GoogleLogin;
import com.pawbridge.backend.user.internal.oauth.infrastructure.GoogleOAuthPrincipal;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.*;

class GoogleOAuthHandlersTest {
    private static final String FRONT="https://fixture.invalid/oauth/callback";
    private static final String PRIVATE="synthetic-private-access-refresh-secret";
    private HttpServletResponse response()throws Exception{var r=mock(HttpServletResponse.class);when(r.encodeRedirectURL(anyString())).thenAnswer(call->call.getArgument(0));return r;}
    @Test void given__persisted_google_user__when__success_completes__then__retain_original_query_token_redirect()throws Exception{
        var login=mock(GoogleLogin.class);var user=new GoogleLogin.GoogleUser(7,"fixture@example.invalid","name","ROLE_USER",null,"GOOGLE");
        when(login.complete(user)).thenReturn(new AuthSessions.Tokens("synthetic-access","synthetic-refresh"));var response=response();
        Logger redirect=(Logger)LoggerFactory.getLogger(org.springframework.security.web.DefaultRedirectStrategy.class);
        var previous=redirect.getLevel();var events=new ListAppender<ILoggingEvent>();events.start();redirect.addAppender(events);redirect.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try{
            new GoogleOAuth2SuccessHandler(login,FRONT).onAuthenticationSuccess(mock(HttpServletRequest.class),response,
                    new UsernamePasswordAuthenticationToken(new GoogleOAuthPrincipal(user,Map.of()),null));
            var url=ArgumentCaptor.forClass(String.class);verify(response).sendRedirect(url.capture());
            assertEquals(FRONT+"?accessToken=synthetic-access&refreshToken=synthetic-refresh",url.getValue());verify(login).complete(user);
            for(var event:events.list){assertFalse(event.getFormattedMessage().contains("synthetic-access"));assertFalse(event.getFormattedMessage().contains("synthetic-refresh"));}
        }finally{redirect.setLevel(previous);redirect.detachAppender(events);events.stop();}
    }
    @ParameterizedTest @ValueSource(strings={"provider","forged-processing","db-completion"})
    void given__exception_containing_private_tokens__when__redirecting_failure__then__emit_only_fixed_url_and_type_only_log(String source)throws Exception{
        var response=response();var request=mock(HttpServletRequest.class);
        Logger logger=(Logger)LoggerFactory.getLogger(source.equals("db-completion")?GoogleOAuth2SuccessHandler.class:GoogleOAuth2FailureHandler.class);
        var events=new ListAppender<ILoggingEvent>();events.start();logger.addAppender(events);
        try{
            if(source.equals("db-completion")){
                var login=mock(GoogleLogin.class);var user=new GoogleLogin.GoogleUser(7,"fixture@example.invalid","name","ROLE_USER",null,"GOOGLE");
                when(login.complete(user)).thenThrow(new IllegalStateException(PRIVATE));
                new GoogleOAuth2SuccessHandler(login,FRONT).onAuthenticationSuccess(request,response,new UsernamePasswordAuthenticationToken(new GoogleOAuthPrincipal(user,Map.of()),null));
            }else{
                var error=new OAuth2AuthenticationException(new OAuth2Error(source.equals("provider")?"server_error":"oauth2_processing_error",PRIVATE,null));
                new GoogleOAuth2FailureHandler(FRONT).onAuthenticationFailure(request,response,error);
            }
            var url=ArgumentCaptor.forClass(String.class);verify(response).sendRedirect(url.capture());assertFalse(url.getValue().contains(PRIVATE));assertTrue(url.getValue().startsWith("https://fixture.invalid/login?error="));
            assertFalse(events.list.isEmpty());for(var event:events.list){assertFalse(event.getFormattedMessage().contains(PRIVATE));assertNull(event.getThrowableProxy());}
        }finally{logger.detachAppender(events);events.stop();}
    }
    @ParameterizedTest @ValueSource(strings={"?tenant=a","#oauth-error"})
    void given__frontend_query_or_fragment__when__token_completion_fails__then__preserve_them_and_append_the_fixed_error_parameter(String suffix)throws Exception{
        var login=mock(GoogleLogin.class);var user=new GoogleLogin.GoogleUser(7,"fixture@example.invalid","name","ROLE_USER",null,"GOOGLE");
        when(login.complete(user)).thenThrow(new IllegalStateException(PRIVATE));var response=response();
        new GoogleOAuth2SuccessHandler(login,FRONT+suffix).onAuthenticationSuccess(mock(HttpServletRequest.class),response,
                new UsernamePasswordAuthenticationToken(new GoogleOAuthPrincipal(user,Map.of()),null));
        var url=ArgumentCaptor.forClass(String.class);verify(response).sendRedirect(url.capture());
        var target=java.net.URI.create(url.getValue());
        String error=java.net.URLEncoder.encode("로그인 처리 중 오류가 발생했습니다",java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("/login",target.getPath());
        assertEquals(suffix.startsWith("?")?"tenant=a&error="+error:"error="+error,target.getRawQuery());
        assertEquals(suffix.startsWith("#")?"oauth-error":null,target.getFragment());
        assertFalse(url.getValue().contains(PRIVATE));verify(login).complete(user);
    }
    @Test void given__known_static_local_account_conflict__when__oauth_fails__then__retain_only_that_allowed_reason()throws Exception{
        var response=response();String message="이미 해당 이메일로 가입된 계정이 있습니다. 일반 로그인을 이용해주세요.";
        new GoogleOAuth2FailureHandler(FRONT).onAuthenticationFailure(mock(HttpServletRequest.class),response,
                new OAuth2AuthenticationException(new OAuth2Error("oauth2_processing_error",message,null)));
        var url=ArgumentCaptor.forClass(String.class);verify(response).sendRedirect(url.capture());assertTrue(url.getValue().contains(java.net.URLEncoder.encode(message,java.nio.charset.StandardCharsets.UTF_8)));
    }
}
