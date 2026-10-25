package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.user.internal.storemypage.application.StoreMyPage
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageController
import com.pawbridge.backend.user.internal.storemypage.web.StoreMyPageExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["pawbridge.user-store-mypage.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        StoreMyPageController::class,
        StoreMyPageExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class UserStoreMyPageHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var myPage: StoreMyPage

    @Test
    fun given__verified_member_and_forged_identity__when__reading_all_three_routes__then__use_only_jwt_user_and_preserve_empty_cart_204() {
        whenever(myPage.wishlists(eq(7L), anyNonNull<StoreMyPage.Request>()))
            .thenReturn(StoreMyPage.Page(listOf(), 0, 20, 0, listOf()))
        whenever(myPage.orders(eq(7L), anyNonNull<StoreMyPage.Request>()))
            .thenReturn(StoreMyPage.Page(listOf(), 0, 20, 0, listOf()))
        val token = "Bearer " + F3TestInputs.token(7, "ROLE_USER")
        for (path in listOf("wishlists", "orders")) {
            mvc.perform(get(ROOT + path).header("Authorization", token).header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(header().doesNotExist("Set-Cookie"))
        }
        mvc.perform(get(ROOT + "cart").header("Authorization", token).header("X-User-Id", "999"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""))
        val defaults = StoreMyPage.Request(0, 20, "createdAt,desc")
        verify(myPage).wishlists(7, defaults)
        verify(myPage).orders(7, defaults)
        verify(myPage).cart(7)
        verifyNoMoreInteractions(myPage)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["wishlists", "orders", "cart"])
    fun given__missing_jwt_and_forged_headers__when__reading_own_data__then__reject_before_service(path: String) {
        mvc.perform(get(ROOT + path).header("X-User-Id", "7").header("X-User-Role", "ROLE_ADMIN"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
        verifyNoInteractions(myPage)
    }

    @Test
    fun given__an_authenticated_member__when__requesting_an_unknown_neighbor__then__deny_without_store_call() {
        mvc.perform(get(ROOT + "cart/items").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")))
            .andExpect(status().isForbidden())
        verifyNoInteractions(myPage)
    }

    companion object {
        private const val ROOT = "/api/v1/users/me/"
    }
}

@WebMvcTest(
    properties = ["pawbridge.user-store-mypage.enabled=false"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        StoreMyPageController::class,
        StoreMyPageExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class UserStoreMyPageDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var myPage: StoreMyPage

    @ParameterizedTest
    @ValueSource(strings = ["wishlists", "orders", "cart"])
    fun given__feature_disabled__when__requesting_each_own_route__then__deny_even_with_valid_jwt(path: String) {
        mvc.perform(
            get("/api/v1/users/me/$path").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(myPage)
    }
}
