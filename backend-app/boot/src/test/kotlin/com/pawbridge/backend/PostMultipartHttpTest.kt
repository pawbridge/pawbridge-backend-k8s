package com.pawbridge.backend

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import com.zaxxer.hikari.HikariDataSource
import jakarta.servlet.ServletException
import jakarta.servlet.http.Part
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartException
import org.springframework.web.multipart.support.StandardServletMultipartResolver
import java.util.concurrent.atomic.AtomicInteger

class PostMultipartHttpTest {
    @ParameterizedTest
    @CsvSource("POST,false", "POST,true", "PUT,false", "PUT,true")
    fun given__multipart_parsing_failure__when__authenticating_before_handler_selection__then__reject_missing_token_or_return_safe_legacy_500(
        method: String,
        oversized: Boolean,
    ) {
        val values = F3TestInputs.values()
        val reads = ReadDatabaseSettings.fromEnvironment { name -> values[name] }
        val writes = PostWriteSettings.fromEnvironment { name -> values[name] }
        WebApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PawBridgeApplication::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("readDatabaseSettings", reads)
                context.beanFactory.registerSingleton("postWriteSettings", writes)
            }
            .withPropertyValues("spring.config.location=classpath:/application.properties")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean("multipartResolver")).isInstanceOf(StandardServletMultipartResolver::class.java)
                val mvc = MockMvcBuilders.webAppContextSetup(context.sourceApplicationContext)
                    .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity())
                    .build()
                val logger = LoggerFactory.getLogger(
                    "com.pawbridge.backend.community.internal.post.web.PostMultipartExceptionHandler",
                ) as Logger
                val logs = ListAppender<ILoggingEvent>()
                logs.start()
                logger.addAppender(logs)
                val parseCalls = AtomicInteger()
                val token = F3TestInputs.token(7, "ROLE_USER")
                val request: (String?) -> RequestBuilder = { accessToken ->
                    RequestBuilder { servletContext ->
                        val path = if (method == "POST") "/api/v1/posts" else "/api/v1/posts/42"
                        val input = object : MockHttpServletRequest(servletContext, method, path) {
                            @Throws(ServletException::class)
                            override fun getParts(): Collection<Part> {
                                parseCalls.incrementAndGet()
                                if (oversized) {
                                    throw MaxUploadSizeExceededException(100L * 1024 * 1024)
                                }
                                throw ServletException(PRIVATE_DETAIL)
                            }
                        }
                        input.servletPath = path
                        input.contentType = "multipart/form-data; boundary=synthetic-boundary"
                        if (accessToken != null) {
                            input.addHeader("Authorization", "Bearer $accessToken")
                        }
                        input
                    }
                }
                try {
                    mvc.perform(request(null)).andExpect(status().isUnauthorized())
                    assertThat(parseCalls.get()).isZero()
                    val result = mvc.perform(request(token))
                        .andExpect(status().isInternalServerError())
                        .andExpect(
                            content().json(
                                "{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}",
                                JsonCompareMode.STRICT,
                            ),
                        )
                        .andReturn()
                    assertThat(parseCalls.get()).isOne()
                    assertThat(result.handler).isNull()
                    assertThat(result.resolvedException).isInstanceOf(MultipartException::class.java)
                    assertThat(result.response.contentAsString).doesNotContain(PRIVATE_DETAIL, token)
                    assertThat(SecurityContextHolder.getContext().authentication).isNull()
                    assertThat(logs.list).hasSize(1).allSatisfy { event ->
                        assertThat(event.throwableProxy).isNull()
                        assertThat(event.argumentArray).hasSize(1).allSatisfy { value ->
                            assertThat(value).isInstanceOf(String::class.java)
                        }
                        assertThat(event.formattedMessage).doesNotContain(PRIVATE_DETAIL, token, "Bearer ")
                    }
                    assertThat(context.getBeansOfType(HikariDataSource::class.java)).hasSize(1).allSatisfy { _, pool ->
                        assertThat(pool.hikariPoolMXBean.activeConnections).isZero()
                        assertThat(pool.hikariPoolMXBean.totalConnections).isZero()
                    }
                } finally {
                    logger.detachAppender(logs)
                    logs.stop()
                }
            }
    }

    companion object {
        private const val PRIVATE_DETAIL = "synthetic-private-multipart-detail"
    }
}
