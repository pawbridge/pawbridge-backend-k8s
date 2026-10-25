package com.pawbridge.backend.user.internal.oauth.infrastructure

import com.pawbridge.backend.user.internal.oauth.application.SocialLogin.SocialUser
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.user.OAuth2User

class SocialOAuthPrincipal(
    private val user: SocialUser,
    private val attributes: Map<String, Any>,
) : OAuth2User {

    fun user(): SocialUser = user

    override fun getAttributes(): Map<String, Any> = attributes

    override fun getAuthorities(): Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority(user.role))

    override fun getName(): String? = user.email

    override fun toString(): String {
        return "SocialOAuthPrincipal"
    }
}
