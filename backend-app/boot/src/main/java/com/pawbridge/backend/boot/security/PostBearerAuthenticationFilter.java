package com.pawbridge.backend.boot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applied only inside the security chain, before the matching POST reaches MVC. */
public final class PostBearerAuthenticationFilter extends OncePerRequestFilter {
    private final PostTokenAuthenticator tokens;
    private final RequestMatcher protectedRequest;
    private final ObjectMapper mapper = new ObjectMapper();

    public PostBearerAuthenticationFilter(PostTokenAuthenticator tokens, RequestMatcher protectedRequest) {
        this.tokens = tokens;
        this.protectedRequest = protectedRequest;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) { return !protectedRequest.matches(request); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long userId;
        try { userId = tokens.authenticate(request.getHeader("Authorization")); }
        catch (PostTokenAuthenticator.Failure failure) {
            SecurityContextHolder.clearContext();
            response.setStatus(401);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            var body = new LinkedHashMap<String, Object>();
            body.put("code", 401); body.put("message", failure.getMessage()); body.put("data", null);
            mapper.writeValue(response.getWriter(), body);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        SecurityContextHolder.setContext(context);
        // Business and commit failures are deliberately outside the authentication catch.
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
