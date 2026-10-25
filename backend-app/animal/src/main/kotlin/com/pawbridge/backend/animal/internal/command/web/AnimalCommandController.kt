package com.pawbridge.backend.animal.internal.command.web

import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.CreateAnimalRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalDescriptionRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalStatusRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/animals")
class AnimalCommandController(
    private val animals: AnimalCommandService,
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal verifiedUserId: Long?,
        @Valid @RequestBody request: CreateAnimalRequest,
    ): ResponseEntity<AnimalDetailResponse> {
        return ResponseEntity.status(201).body(animals.create(verifiedUserId, request))
    }

    @PatchMapping("/{id}/status")
    fun updateStatus(
        @AuthenticationPrincipal verifiedUserId: Long?,
        @PathVariable("id") id: Long?,
        @Valid @RequestBody request: UpdateAnimalStatusRequest,
    ): AnimalDetailResponse {
        return animals.updateStatus(verifiedUserId, id, request)
    }

    @PatchMapping("/{id}/description")
    fun updateDescription(
        @AuthenticationPrincipal verifiedUserId: Long?,
        @PathVariable("id") id: Long?,
        @Valid @RequestBody request: UpdateAnimalDescriptionRequest,
    ): AnimalDetailResponse {
        return animals.updateDescription(verifiedUserId, id, request)
    }

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal verifiedUserId: Long?,
        @PathVariable("id") id: Long?,
    ): ResponseEntity<Void> {
        animals.delete(verifiedUserId, id)
        return ResponseEntity.noContent().build()
    }
}
