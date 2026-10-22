package com.pawbridge.backend.user.internal.login.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.user.internal.login.application.LocalLogin;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.ArrayList;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** The original JSON authentication filter; no MVC validation or additional method policy. */
public final class LocalLoginFilter extends UsernamePasswordAuthenticationFilter {
    public static final String PATH="/api/v1/auth/login";
    private final LocalLogin login;
    public LocalLoginFilter(AuthenticationManager manager,LocalLogin login){
        setAuthenticationManager(manager);this.login=login;setFilterProcessesUrl(PATH);
    }
    @Override public Authentication attemptAuthentication(HttpServletRequest request,HttpServletResponse response) {
        try {
            var input=new ObjectMapper().readValue(request.getInputStream(),LoginRequest.class);
            return getAuthenticationManager().authenticate(new UsernamePasswordAuthenticationToken(input.email(),input.password(),new ArrayList<>()));
        }catch(InternalAuthenticationServiceException failure){
            throw new InternalAuthenticationServiceException("Local login authentication failed");
        }catch(IOException failure){throw new RuntimeException("로그인 요청 처리 중 오류가 발생했습니다.");}
    }
    @Override protected void successfulAuthentication(HttpServletRequest request,HttpServletResponse response,
            FilterChain chain,Authentication authentication)throws IOException {
        var account=((LocalLoginPrincipal)authentication.getPrincipal()).account();
        com.pawbridge.backend.user.internal.session.application.AuthSessions.Tokens tokens;
        try{tokens=login.complete(account);}
        catch(RuntimeException failure){throw new IllegalStateException("Local login completion failed: "+failure.getClass().getName());}
        write(response,200,"로그인에 성공했습니다.",new LoginResponse(account.userId(),account.email(),account.name(),
                account.role(),account.careRegNo(),tokens.accessToken(),tokens.refreshToken()));
    }
    @Override protected void unsuccessfulAuthentication(HttpServletRequest request,HttpServletResponse response,
            AuthenticationException failure)throws IOException {
        String message=failure instanceof BadCredentialsException?"이메일 또는 비밀번호가 일치하지 않습니다.":
                failure instanceof UsernameNotFoundException?"존재하지 않는 사용자입니다.":"인증에 실패했습니다.";
        write(response,401,message,null);
    }
    private static void write(HttpServletResponse response,int code,String message,Object data)throws IOException {
        response.setStatus(code);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write(new ObjectMapper().writeValueAsString(new Envelope(code,message,data)));
    }
    public record LoginRequest(@Email @NotBlank String email,@NotBlank String password){
        @Override public String toString(){return "LocalLoginRequest";}
    }
    private record LoginResponse(long userId,String email,String name,String role,String careRegNo,String accessToken,String refreshToken){}
    private record Envelope(int code,String message,Object data){}
}
