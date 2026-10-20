package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.post.application.PostPageReader;
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.community.internal.post.web.AdminPostReadController;
import com.pawbridge.backend.community.internal.post.web.AdminPostReadExceptionHandler;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods=false)
public class AdminPostReadConfiguration implements WebMvcConfigurer {
    private final PageableHandlerMethodArgumentResolver pageable = new PageableHandlerMethodArgumentResolver();
    @Bean PageableHandlerMethodArgumentResolver adminPostPageableResolver() { return pageable; }
    @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(pageable);
    }
    @Bean ReadAdminPosts readAdminPosts(PostPageReader posts, ReadPost readPost) { return new ReadAdminPosts(posts, readPost); }
    @Bean AdminPostReadController adminPostReadController(ReadAdminPosts posts, ReadPost post) { return new AdminPostReadController(posts, post); }
    @Bean AdminPostReadExceptionHandler adminPostReadExceptionHandler() { return new AdminPostReadExceptionHandler(); }
}
