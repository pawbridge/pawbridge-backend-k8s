package com.pawbridge.backend.user.api

/**
 * Contact projection without private profile or provider information.
 *
 * The Java record accessors (`userId()`, `nickname()`, ...) stay because other modules call them. They are
 * separate methods, not `@get:JvmName` renames, so the standard getters keep Jackson serialization unchanged.
 */
data class ContactMember(
    val userId: Long?,
    val nickname: String?,
    val active: Boolean,
    val deletionPending: Boolean,
) {
    fun userId(): Long? = userId

    fun nickname(): String? = nickname

    fun active(): Boolean = active

    fun deletionPending(): Boolean = deletionPending
}
