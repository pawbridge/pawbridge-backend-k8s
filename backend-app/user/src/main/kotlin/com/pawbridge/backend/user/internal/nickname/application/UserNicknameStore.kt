package com.pawbridge.backend.user.internal.nickname.application

import java.util.Optional

interface UserNicknameStore {
    fun findNickname(userId: Long): Optional<String>

    fun existsByNickname(nickname: String?): Boolean

    fun updateNickname(userId: Long, nickname: String?)
}
