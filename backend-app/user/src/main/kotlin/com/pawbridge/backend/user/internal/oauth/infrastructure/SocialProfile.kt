package com.pawbridge.backend.user.internal.oauth.infrastructure

import com.pawbridge.backend.user.internal.oauth.application.SocialLoginRejectedException

/**
 * Provider-neutral view of the user-info response.
 *
 * Google returns a flat map (`sub`, `email`, `name`). Naver wraps the same facts in
 * `response` (`id`, `email`, `name`); its `id` is unique per Naver application.
 */
@JvmRecord
internal data class SocialProfile(
    val provider: String?,
    val providerId: String?,
    val email: String?,
    val name: String?,
) {
    fun identifiedByProviderId(): Boolean = NAVER == provider

    companion object {
        const val GOOGLE = "GOOGLE"
        const val NAVER = "NAVER"

        @JvmStatic
        fun from(registrationId: String?, attributes: Map<String, Any?>): SocialProfile {
            if ("google".equals(registrationId, ignoreCase = true)) {
                return withEmail(GOOGLE, text(attributes, "sub"), text(attributes, "email"), text(attributes, "name"))
            }
            if ("naver".equals(registrationId, ignoreCase = true)) {
                val nested = attributes["response"]
                val response: Map<*, *> = if (nested is Map<*, *>) nested else emptyMap<Any, Any>()
                val providerId = text(response, "id")
                if (providerId == null || providerId.isEmpty()) {
                    throw SocialLoginRejectedException("로그인 처리 중 오류가 발생했습니다")
                }
                return withEmail(NAVER, providerId, text(response, "email"), text(response, "name"))
            }
            throw SocialLoginRejectedException("지원하지 않는 로그인 방식입니다: " + registrationId)
        }

        private fun withEmail(provider: String, providerId: String?, email: String?, name: String?): SocialProfile {
            if (email == null || email.isEmpty()) {
                throw SocialLoginRejectedException("이메일 정보를 가져올 수 없습니다")
            }
            return SocialProfile(provider, providerId, email, name)
        }

        @Suppress("UNCHECKED_CAST")
        private fun text(source: Map<*, *>, key: String): String? {
            val value = (source as Map<Any?, Any?>)[key]
            return if (value is String) value else null
        }
    }
}
