package com.pawbridge.backend.community.internal.homevideo.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Kept in Java on purpose: the container element constraint {@code List<@NotNull UUID>} is a type-use annotation,
 * which Kotlin 1.9 does not write to class files without -Xemit-jvm-type-annotations, so a Kotlin version would
 * silently stop rejecting null ids (400 turning into a 409 from the service).
 */
public record HomeVideoOrderRequest(
        @NotNull @Size(max = 3) List<@NotNull UUID> ids,
        @Min(0) long revision) {
}
