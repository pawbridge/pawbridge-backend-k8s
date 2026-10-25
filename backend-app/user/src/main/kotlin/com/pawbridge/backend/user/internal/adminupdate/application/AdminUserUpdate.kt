package com.pawbridge.backend.user.internal.adminupdate.application

interface AdminUserUpdate {
    fun update(userId: Long, changes: Changes)

    @JvmRecord
    data class Changes(
        val nickname: String?,
        val role: Role?,
        val careRegNo: String?,
    )

    enum class Role { ROLE_USER, ROLE_ADMIN, ROLE_SHELTER }
}
