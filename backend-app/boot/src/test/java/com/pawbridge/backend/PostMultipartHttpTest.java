package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings;
import com.pawbridge.backend.boot.configuration.PostWriteSettings;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

class PostMultipartHttpTest {
    private static final String PRIVATE_DETAIL="synthetic-private-multipart-detail";

    @ParameterizedTest
    @CsvSource({"POST,false","POST,true","PUT,false","PUT,true"})
    void given__multipart_parsing_failure__when__authenticating_before_handler_selection__then__reject_missing_token_or_return_safe_legacy_500(
            String method,boolean oversized) {
        var values=F3TestInputs.values();
        var reads=ReadDatabaseSettings.fromEnvironment(values::get);
        var writes=PostWriteSettings.fromEnvironment(values::get);
        new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PawBridgeApplication.class)
                .withInitializer(context->{
                    context.getBeanFactory().registerSingleton("readDatabaseSettings",reads);
                    context.getBeanFactory().registerSingleton("postWriteSettings",writes);
                })
                .withPropertyValues("spring.config.location=classpath:/application.properties")
                .run(context->{
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("multipartResolver")).isInstanceOf(StandardServletMultipartResolver.class);
                    var mvc=MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).apply(springSecurity()).build();
                    Logger logger=(Logger)LoggerFactory.getLogger("com.pawbridge.backend.community.internal.post.web.PostMultipartExceptionHandler");
                    var logs=new ListAppender<ILoggingEvent>();logs.start();logger.addAppender(logs);
                    var parseCalls=new AtomicInteger();
                    String token=F3TestInputs.token(7,"ROLE_USER");
                    Function<String,RequestBuilder> request=accessToken->servletContext->{
                        String path=method.equals("POST")?"/api/v1/posts":"/api/v1/posts/42";
                        var input=new MockHttpServletRequest(servletContext,method,path) {
                            @Override public Collection<Part> getParts() throws ServletException {
                                parseCalls.incrementAndGet();
                                if(oversized)throw new MaxUploadSizeExceededException(100L*1024*1024);
                                throw new ServletException(PRIVATE_DETAIL);
                            }
                        };
                        input.setServletPath(path);
                        input.setContentType("multipart/form-data; boundary=synthetic-boundary");
                        if(accessToken!=null)input.addHeader("Authorization","Bearer "+accessToken);
                        return input;
                    };
                    try {
                        mvc.perform(request.apply(null)).andExpect(status().isUnauthorized());
                        assertThat(parseCalls.get()).isZero();
                        var result=mvc.perform(request.apply(token))
                                .andExpect(status().isInternalServerError())
                                .andExpect(content().json("{\"code\":500,\"message\":\"서버 에러!\",\"data\":null}",JsonCompareMode.STRICT))
                                .andReturn();
                        assertThat(parseCalls.get()).isOne();
                        assertThat(result.getHandler()).isNull();
                        assertThat(result.getResolvedException()).isInstanceOf(MultipartException.class);
                        assertThat(result.getResponse().getContentAsString()).doesNotContain(PRIVATE_DETAIL,token);
                        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                        assertThat(logs.list).hasSize(1).allSatisfy(event->{
                            assertThat(event.getThrowableProxy()).isNull();
                            assertThat(event.getArgumentArray()).hasSize(1).allSatisfy(value->assertThat(value).isInstanceOf(String.class));
                            assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_DETAIL,token,"Bearer ");
                        });
                        assertThat(context.getBeansOfType(HikariDataSource.class)).hasSize(1).allSatisfy((name,pool)->{
                            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
                        });
                    } finally {logger.detachAppender(logs);logs.stop();}
                });
    }
}
