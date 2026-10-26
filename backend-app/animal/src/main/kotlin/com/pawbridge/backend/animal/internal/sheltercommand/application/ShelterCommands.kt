package com.pawbridge.backend.animal.internal.sheltercommand.application

import java.time.LocalDateTime

interface ShelterCommands {
    fun create(verifiedUserId: Long?, request: Create): Details

    fun update(verifiedUserId: Long?, id: Long?, request: Update): Details

    fun delete(verifiedUserId: Long?, id: Long?)

    @JvmRecord
    data class Create(
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
    )

    @JvmRecord
    data class Update(
        val phone: String?,
        val email: String?,
        val introduction: String?,
        val adoptionProcedure: String?,
        val operatingHours: String?,
    )

    @JvmRecord
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
    )
}
