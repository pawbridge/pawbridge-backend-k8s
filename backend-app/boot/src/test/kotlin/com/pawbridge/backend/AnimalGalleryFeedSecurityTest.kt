package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import io.mockk.confirmVerified
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["lost-gallery-feed.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
        AnimalGalleryFeedSecurityTest.Controllers::class,
    ],
)
class AnimalGalleryFeedSecurityTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var feed: LostGalleryFeed

    @MockkBean
    lateinit var snapshots: LostGallerySnapshots

    @TestConfiguration(proxyBeanMethods = false)
    class Controllers {
        @Bean
        fun feedController(feed: LostGalleryFeed): LostGalleryFeedController {
            return LostGalleryFeedController(feed, "synthetic-internal")
        }

        @Bean
        fun pagesController(snapshots: LostGallerySnapshots): LostGalleryPagesController {
            return LostGalleryPagesController(snapshots, "synthetic-internal")
        }
    }

    @ParameterizedTest
    @CsvSource(
        "GET,/internal/animals/lost-gallery",
        "POST,/internal/animals/lost-gallery/snapshots",
        "GET,/internal/animals/lost-gallery/snapshots/abc",
        "GET,/internal/animals/lost-gallery/snapshots/abc/pages?cursor=opaque",
        "DELETE,/internal/animals/lost-gallery/snapshots/abc",
    )
    fun `없거나 틀린 내부 키와 관리자 JWT__다섯 경로를 호출하면__업무 처리 전에 401을 돌려준다`(
        method: String,
        path: String,
    ) {
        // given
        val withAdminJwt = request(HttpMethod.valueOf(method), path)
            .header("Authorization", "Bearer " + F3TestInputs.token(7, "ADMIN"))
        val withWrongKey = request(HttpMethod.valueOf(method), path)
            .header("X-Internal-Api-Key", "wrong-synthetic")

        // when
        val adminJwtResult = mvc.perform(withAdminJwt)
        val wrongKeyResult = mvc.perform(withWrongKey)

        // then
        adminJwtResult.andExpect(status().isUnauthorized())
        wrongKeyResult.andExpect(status().isUnauthorized())
        confirmVerified(feed, snapshots)
    }

}
