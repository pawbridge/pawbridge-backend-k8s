package com.pawbridge.backend.animal.internal.sheltercommand.web

import com.fasterxml.jackson.annotation.JsonInclude
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/v1/shelters")
class ShelterCommandController(
    private val shelters: ShelterCommands,
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal verifiedUserId: Long?,
        @Valid @RequestBody request: CreateShelterRequest,
    ): ResponseEntity<Details> {
        val command = ShelterCommands.Create(
            request.careRegNo,
            request.name,
            request.phone,
            request.address,
            request.ownerName,
            request.organizationName,
            request.email,
            request.introduction,
            request.adoptionProcedure,
            request.operatingHours,
        )
        return ResponseEntity.status(201).body(Details.from(shelters.create(verifiedUserId, command)))
    }

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal verifiedUserId: Long?,
        @PathVariable("id") id: Long,
        @Valid @RequestBody request: UpdateShelterRequest,
    ): Details {
        val command = ShelterCommands.Update(
            request.phone,
            request.email,
            request.introduction,
            request.adoptionProcedure,
            request.operatingHours,
        )
        return Details.from(shelters.update(verifiedUserId, id, command))
    }

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal verifiedUserId: Long?,
        @PathVariable("id") id: Long,
    ): ResponseEntity<Void> {
        shelters.delete(verifiedUserId, id)
        return ResponseEntity.noContent().build()
    }

    @JvmRecord
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Details(
        val id: Long?,
        val careRegNo: String?,
        val name: String?,
        val phone: String?,
        val address: String?,
        val ownerName: String?,
        val organizationName: String?,
        val email: String?,
        val introduction: String?,
        val adoptionProcedure: String?,
        val operatingHours: String?,
        val createdAt: LocalDateTime?,
        val updatedAt: LocalDateTime?,
    ) {
        internal companion object {
            internal fun from(value: ShelterCommands.Details): Details {
                return Details(
                    value.id,
                    value.careRegNo,
                    value.name,
                    value.phone,
                    value.address,
                    value.ownerName,
                    value.organizationName,
                    value.email,
                    value.introduction,
                    value.adoptionProcedure,
                    value.operatingHours,
                    value.createdAt,
                    value.updatedAt,
                )
            }
        }
    }
}
