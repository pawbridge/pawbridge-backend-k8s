package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.community.internal.post.application.PostPageReader
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts
import com.pawbridge.backend.community.internal.post.application.ReadPost
import com.pawbridge.backend.community.internal.post.web.AdminPostReadController
import com.pawbridge.backend.community.internal.post.web.AdminPostReadExceptionHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration(proxyBeanMethods = false)
class AdminPostReadConfiguration : WebMvcConfigurer {
    private val pageable = PageableHandlerMethodArgumentResolver()

    @Bean
    fun adminPostPageableResolver(): PageableHandlerMethodArgumentResolver {
        return pageable
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(pageable)
    }

    @Bean
    fun readAdminPosts(posts: PostPageReader, readPost: ReadPost): ReadAdminPosts {
        return ReadAdminPosts(posts, readPost)
    }

    @Bean
    fun adminPostReadController(posts: ReadAdminPosts, post: ReadPost): AdminPostReadController {
        return AdminPostReadController(posts, post)
    }

    @Bean
    fun adminPostReadExceptionHandler(): AdminPostReadExceptionHandler {
        return AdminPostReadExceptionHandler()
    }
}
