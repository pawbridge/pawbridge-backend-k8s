package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.internal.wishlist.application.WishlistCommands
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandController
import com.pawbridge.backend.store.internal.wishlist.web.WishlistCommandExceptionHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["pawbridge.store-wishlist.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        WishlistCommandController::class,
        WishlistCommandExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreWishlistHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var commands: WishlistCommands

    @ParameterizedTest
    @CsvSource("POST,ROLE_USER", "POST,ROLE_ADMIN", "DELETE,ROLE_USER", "DELETE,ROLE_ADMIN")
    fun given__flag_on_and_verified_member__when__commanding_exact_route__then__existing_jwt_principal_overrides_forged_identity(
        method: String,
        role: String,
    ) {
        whenever(commands.add(7, 80L))
            .thenReturn(WishlistCommands.Added(33L, 7L, 80L, "SKU", 9L, "item", null, 0L, mutableMapOf(), null))
        val request = request(HttpMethod.valueOf(method), "/api/v1/wishlists")
            .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
            .header("X-User-Id", "99")
            .header("X-User-Role", "ROLE_ADMIN")
            .param("userId", "99")
            .param("skuId", "80")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"skuId\":80,\"userId\":99}")
        mvc.perform(request)
            .andExpect(status().`is`(if (method == "POST") 201 else 204))
            .andExpect(header().doesNotExist("Set-Cookie"))
        if (method == "POST") {
            verify(commands).add(7, 80L)
        } else {
            verify(commands).removeByMemberAndSku(7, 80)
        }
        verifyNoMoreInteractions(commands)
    }

    @ParameterizedTest
    @CsvSource("POST,missing", "POST,invalid", "DELETE,missing", "DELETE,invalid")
    fun given__flag_on_but_missing_or_invalid_jwt__when__forging_user_headers__then__existing_filter_returns_401_before_command(
        method: String,
        token: String,
    ) {
        val request = request(HttpMethod.valueOf(method), "/api/v1/wishlists")
            .param("skuId", "80")
            .header("X-User-Id", "99")
            .header("X-User-Role", "ROLE_ADMIN")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"skuId\":80,\"userId\":99}")
        if (token == "invalid") {
            request.header("Authorization", "Bearer invalid-token")
        }
        mvc.perform(request).andExpect(status().isUnauthorized())
        verifyNoInteractions(commands)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN"])
    fun given__verified_member__when__deleting_wishlist_id__then__use_principal_and_return_no_content(role: String) {
        mvc.perform(
            delete("/api/v1/wishlists/33")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role))
                .header("X-User-Id", "99")
                .header("X-User-Role", "ROLE_ADMIN")
                .param("userId", "99")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":99}"),
        )
            .andExpect(status().isNoContent())
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(commands).removeById(7, 33)
        verifyNoMoreInteractions(commands)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "invalid"])
    fun given__no_valid_jwt__when__deleting_wishlist_id_with_forged_headers__then__reject_before_command(token: String) {
        val request = delete("/api/v1/wishlists/33").header("X-User-Id", "99").header("X-User-Role", "ROLE_ADMIN")
        if (token == "invalid") {
            request.header("Authorization", "Bearer invalid-token")
        }
        mvc.perform(request).andExpect(status().isUnauthorized())
        verifyNoInteractions(commands)
    }

    @ParameterizedTest
    @CsvSource(
        "GET,/api/v1/wishlists", "PUT,/api/v1/wishlists", "PATCH,/api/v1/wishlists", "POST,/api/v1/wishlists/33",
        "GET,/api/v1/wishlists/33", "PUT,/api/v1/wishlists/33", "PATCH,/api/v1/wishlists/33",
        "DELETE,/api/v1/wishlists/33/extra", "DELETE,/api/wishlists/33", "POST,/api/wishlists",
    )
    fun given__verified_admin__when__attempting_unported_neighbors__then__deny_without_command(
        method: String,
        path: String,
    ) {
        mvc.perform(
            request(HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(commands)
    }
}

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [
        WishlistCommandController::class,
        WishlistCommandExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreWishlistDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var commands: WishlistCommands

    @Test
    fun given__no_explicit_flag__when__deleting_wishlist_id__then__deny_before_application() {
        mvc.perform(
            delete("/api/v1/wishlists/33").header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(commands)
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "DELETE"])
    fun given__no_explicit_flag__when__verified_admin_commands_exact_routes__then__deny_before_application(
        method: String,
    ) {
        mvc.perform(
            request(HttpMethod.valueOf(method), "/api/v1/wishlists")
                .param("skuId", "80")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":80}"),
        )
            .andExpect(status().isForbidden())
        verifyNoInteractions(commands)
    }
}
