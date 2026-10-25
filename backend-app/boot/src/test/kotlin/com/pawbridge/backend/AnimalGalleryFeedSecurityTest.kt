package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var feed: LostGalleryFeed

    @MockitoBean
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
    fun givenMissingOrWrongInternalKeyAndAdminJwt_whenCallingFiveRoutes_then401BeforeBusiness(
        method: String,
        path: String,
    ) {
        mvc.perform(request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ADMIN")))
            .andExpect(status().isUnauthorized())
        mvc.perform(request(HttpMethod.valueOf(method), path).header("X-Internal-Api-Key", "wrong-synthetic"))
            .andExpect(status().isUnauthorized())
        verifyNoInteractions(feed, snapshots)
    }

    @Test
    fun givenOnlyExactFiveRoutesEnabled_whenCallingNeighborsOrWrongMethods_then403BeforeBusiness() {
        val pairs = arrayOf(
            "POST /internal/animals/lost-gallery",
            "GET /internal/animals/lost-gallery/snapshots",
            "PATCH /internal/animals/lost-gallery/snapshots/abc",
            "POST /internal/animals/lost-gallery/snapshots/abc/pages",
            "GET /internal/animals/other",
            "GET /internal/animals/lost-gallery/snapshots/abc/other",
        )
        for (pair in pairs) {
            val parts = pair.split(" ", limit = 2)
            mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("X-Internal-Api-Key", "synthetic-internal"))
                .andExpect(status().isForbidden())
        }
        verifyNoInteractions(feed, snapshots)
    }
}

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
        AnimalGalleryFeedSecurityTest.Controllers::class,
    ],
)
class AnimalGalleryFeedDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var feed: LostGalleryFeed

    @MockitoBean
    lateinit var snapshots: LostGallerySnapshots

    @Test
    fun givenFeatureDisabledAndCorrectKey_whenCallingAllFiveRoutes_then403BeforeBusiness() {
        val pairs = arrayOf(
            "GET /internal/animals/lost-gallery",
            "POST /internal/animals/lost-gallery/snapshots",
            "GET /internal/animals/lost-gallery/snapshots/abc",
            "GET /internal/animals/lost-gallery/snapshots/abc/pages?cursor=opaque",
            "DELETE /internal/animals/lost-gallery/snapshots/abc",
        )
        for (pair in pairs) {
            val parts = pair.split(" ", limit = 2)
            mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("X-Internal-Api-Key", "synthetic-internal"))
                .andExpect(status().isForbidden())
        }
        verifyNoInteractions(feed, snapshots)
    }
}
