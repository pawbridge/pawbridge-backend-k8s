package com.pawbridge.communityservice.contact;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Private mailbox responses must not be cached by a browser or intermediary. */
@Component
@Profile("postgresql")
public class PrivateNoteHttpBoundary extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals("/api/v1/notes") && !path.startsWith("/api/v1/notes/")
                && !path.equals("/api/v1/chats") && !path.startsWith("/api/v1/chats/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
