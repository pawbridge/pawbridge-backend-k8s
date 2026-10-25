package com.pawbridge.backend.user.api

import java.util.Optional

interface UserNicknameQuery {
    /**
     * Returns empty only when the user row is absent. Stored blank nicknames are preserved.
     */
    fun findNickname(userId: Long): Optional<String>
}
