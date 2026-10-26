package com.pawbridge.backend.user.internal.nickname.application

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import org.springframework.transaction.annotation.Transactional

// Explicitly open: the method-level @Transactional is proxied by CGLIB, and kotlin-spring only opens
// classes that carry a class-level Spring stereotype or @Transactional.
open class ChangeUserNickname(private val users: UserNicknameStore) {

    @Transactional(transactionManager = "writeTransactionManager", timeout = 10)
    open fun change(userId: Long, nickname: String?) {
        val current = users.findNickname(userId).orElseThrow { UserNotFoundException() }
        if (current == nickname) {
            return
        }
        // The legacy database query includes this user's own row, including case-only changes.
        if (users.existsByNickname(nickname)) {
            throw NicknameDuplicateException()
        }
        users.updateNickname(userId, nickname)
    }
}
