package com.pawbridge.backend.user.internal.deletion.application

fun interface UserDeletion {
    fun delete(userId: Long)
}
