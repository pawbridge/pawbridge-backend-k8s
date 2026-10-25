package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementController
import com.pawbridge.backend.community.internal.homevideo.web.HomeVideoManagementExceptionHandler
import com.ninjasquad.springmockk.MockkBean
import io.mockk.confirmVerified
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The container constraint List<@NotNull UUID> is a type-use annotation. Kotlin only writes it to the class file with
 * -Xemit-jvm-type-annotations, so this fails (200 or a 409 from the service) if that compiler flag is removed.
 */
@WebMvcTest(
    controllers = [HomeVideoManagementController::class],
    properties = ["pawbridge.home-videos.management-enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        HomeVideoManagementController::class,
        HomeVideoManagementExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class HomeVideoOrderRequestValidationHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var videos: HomeVideoManagement

    @Test
    fun `ids에 null 요소가 있을 때__순서를 바꾸면__서비스 전에 400으로 거절한다`() {
        // given
        val request = put("/api/v1/admin/videos/order")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[null],\"revision\":5}")
            .header("Authorization", HomeVideoManagementHttpTest.admin())

        // when
        val result = mvc.perform(request)

        // then
        result.andExpect(status().isBadRequest())
        confirmVerified(videos)
    }
}
