package com.pawbridge.backend.boot.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter

/** Applied only inside the security chain, before the matching protected GET, POST, PUT or DELETE reaches MVC. */
class PostBearerAuthenticationFilter(
    private val tokens: PostTokenAuthenticator,
    private val protectedRequest: RequestMatcher,
    private val streamRequest: RequestMatcher,
    private val contexts: RequestAttributeSecurityContextRepository,
) : OncePerRequestFilter() {
    private val mapper = ObjectMapper()

    constructor(tokens: PostTokenAuthenticator, protectedRequest: RequestMatcher) : this(
        tokens,
        protectedRequest,
        RequestMatcher { false },
        RequestAttributeSecurityContextRepository(),
    )

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        return !protectedRequest.matches(request)
    }

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val authentication: UsernamePasswordAuthenticationToken
        val initialStream = request.dispatcherType == DispatcherType.REQUEST && streamRequest.matches(request)
        try {
            authentication = tokens.authenticateToken(request.getHeader("Authorization"), initialStream)
        } catch (failure: PostTokenAuthenticator.Failure) {
            SecurityContextHolder.clearContext()
            response.status = 401
            response.characterEncoding = StandardCharsets.UTF_8.name()
            response.contentType = "application/json"
            val body = LinkedHashMap<String, Any?>()
            body["code"] = 401
            body["message"] = failure.message
            body["data"] = null
            mapper.writeValue(response.writer, body)
            return
        }
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        // Business and commit failures are deliberately outside the authentication catch.
        try {
            if (initialStream) {
                contexts.saveContext(context, request, response)
            }
            filterChain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
