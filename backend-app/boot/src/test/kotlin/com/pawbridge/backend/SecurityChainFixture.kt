package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.boot.security.PostTokenAuthenticator
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.core.env.MapPropertySource
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockServletContext
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real ReadSecurityConfiguration chain in front of a single catch-all handler.
 * A request that gets past the chain is counted, so "rejected before any handler" is a plain count of zero.
 */
class SecurityChainFixture private constructor(
    val mvc: MockMvc,
    val recorder: Recorder,
    private val context: AnnotationConfigWebApplicationContext,
) : AutoCloseable {
    @RestController
    class Recorder {
        val reached = AtomicInteger()

        @RequestMapping("/**")
        fun any(): String {
            reached.incrementAndGet()
            return "reached"
        }
    }

    /** The production chain and Spring MVC, with nothing behind them yet. */
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(ReadSecurityConfiguration::class)
    class ChainConfiguration {
        @Bean
        fun postTokenAuthenticator(): PostTokenAuthenticator = PostTokenAuthenticator(F3TestInputs.KEY)

        companion object {
            @Bean
            @JvmStatic
            fun placeholders(): PropertySourcesPlaceholderConfigurer = PropertySourcesPlaceholderConfigurer()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class RecorderConfiguration {
        @Bean
        fun recorder(): Recorder = Recorder()
    }

    /** Closes the Spring context; a test heap is small, so contexts are never kept longer than the test needs. */
    override fun close() {
        context.close()
    }

    fun call(request: MockHttpServletRequestBuilder): Outcome {
        val before = recorder.reached.get()
        val result = mvc.perform(request).andReturn()
        val response = result.response
        return Outcome(
            response.status,
            response.contentAsString,
            recorder.reached.get() - before,
            response.getHeader("Set-Cookie"),
            response.getHeader("Cache-Control"),
        )
    }

    data class Outcome(
        val status: Int,
        val body: String,
        val reached: Int,
        val setCookie: String?,
        val cacheControl: String?,
    ) {
        /** The security headers every answer of the chain carries, whoever wrote the body. */
        fun hasSecurityHeaders(): Boolean = setCookie == null && cacheControl == SECURITY_CACHE

        fun isEnvelope(code: Int, message: String): Boolean {
            return body == "{\"code\":$code,\"message\":\"$message\",\"data\":null}" && hasSecurityHeaders()
        }
    }

    companion object {
        /** A new chain with the given properties; the caller closes it. */
        fun open(properties: Map<String, String>): SecurityChainFixture {
            val context = newContext(properties, RecorderConfiguration::class.java)
            return SecurityChainFixture(mvcOf(context), context.getBean(Recorder::class.java), context)
        }

        /** The production chain in front of whatever [configuration] registers, with every route flag on. */
        fun assemble(configuration: Class<*>): Assembly {
            val context = newContext(SecurityRoutes.everythingOn, configuration)
            return Assembly(mvcOf(context), context)
        }

        class Assembly(val mvc: MockMvc, private val context: AnnotationConfigWebApplicationContext) : AutoCloseable {
            override fun close() {
                context.close()
            }
        }

        private fun newContext(
            properties: Map<String, String>,
            configuration: Class<*>,
        ): AnnotationConfigWebApplicationContext {
            val context = AnnotationConfigWebApplicationContext()
            context.servletContext = MockServletContext()
            context.environment.propertySources.addFirst(MapPropertySource("flags", properties))
            context.register(ChainConfiguration::class.java, configuration)
            context.refresh()
            return context
        }

        private fun mvcOf(context: AnnotationConfigWebApplicationContext): MockMvc {
            return MockMvcBuilders.webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        }

        const val SECURITY_CACHE = "no-cache, no-store, max-age=0, must-revalidate"
        const val ADMIN_MESSAGE = "관리자 권한이 필요합니다."
        const val GENERIC_MESSAGE = "접근할 수 없습니다."
        const val MISSING_TOKEN = "인증 토큰이 필요합니다."
        const val BAD_TOKEN = "유효하지 않거나 만료된 토큰입니다."
        const val BAD_CLAIMS = "토큰 파싱에 실패했습니다."

        private val otherKey = Keys.hmacShaKeyFor(
            "another-synthetic-key-only-never-for-production-0123456789012345678901".toByteArray(Charsets.UTF_8),
        )

        fun send(method: String, path: String, authorization: String?): MockHttpServletRequestBuilder {
            val builder = request(HttpMethod.valueOf(method), path)
                .header("X-User-Id", "99")
                .header("X-User-Role", "ROLE_ADMIN")
            if (authorization != null) {
                builder.header("Authorization", authorization)
            }
            return builder
        }

        fun bearer(userId: Long, role: String): String = "Bearer " + F3TestInputs.token(userId, role)

        private fun custom(configure: io.jsonwebtoken.JwtBuilder.() -> Unit, expiresInMillis: Long = 3_600_000): String {
            val builder = Jwts.builder().subject("synthetic@example.invalid")
            builder.configure()
            return "Bearer " + builder.expiration(Date(System.currentTimeMillis() + expiresInMillis))
                .signWith(F3TestInputs.KEY)
                .compact()
        }

        /** Every way a caller can fail authentication, with the message the chain answers. */
        val rejectedTokens: List<Pair<String?, String>> = listOf(
            null to MISSING_TOKEN,
            "Basic dXNlcjpwYXNz" to MISSING_TOKEN,
            "Bearer invalid" to BAD_TOKEN,
            ("Bearer " + Jwts.builder().claim("userId", 7).claim("role", "ROLE_ADMIN")
                .expiration(Date(System.currentTimeMillis() + 3_600_000)).signWith(otherKey).compact()) to BAD_TOKEN,
            custom({ claim("userId", 7).claim("role", "ROLE_ADMIN") }, -60_000) to BAD_TOKEN,
        ) + PostTokenAuthenticatorTest.invalidIdentity().toList().map { claims ->
            custom({ claims(claims) }) to BAD_CLAIMS
        }
    }
}
