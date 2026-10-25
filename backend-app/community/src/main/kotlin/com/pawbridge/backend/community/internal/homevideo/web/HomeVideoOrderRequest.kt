package com.pawbridge.backend.community.internal.homevideo.web

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * The container element constraint `List<@NotNull UUID>` is a type-use annotation. Kotlin 1.9 only writes it to the
 * class file with `-Xemit-jvm-type-annotations` (enabled for every module in the root build.gradle); without the
 * flag a null id would no longer be rejected here (400 turning into a 409 from the service).
 */
@JvmRecord
data class HomeVideoOrderRequest(
    @field:NotNull
    @field:Size(max = 3)
    val ids: List<@NotNull UUID>?,
    @field:Min(0)
    val revision: Long,
)
