package com.pawbridge.backend.animal.internal.sheltercommand.infrastructure

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands

/** Selected original ShelterMapper fields; neither normalization nor defaults are added. */
internal class ShelterMapper {
    fun toEntity(request: ShelterCommands.Create): Shelter {
        return Shelter.builder()
            .careRegNo(request.careRegNo)
            .name(request.name)
            .phone(request.phone)
            .address(request.address)
            .ownerName(request.ownerName)
            .organizationName(request.organizationName)
            .email(request.email)
            .introduction(request.introduction)
            .adoptionProcedure(request.adoptionProcedure)
            .operatingHours(request.operatingHours)
            .build()
    }

    fun toDetailResponse(shelter: Shelter): ShelterCommands.Details {
        return ShelterCommands.Details(
            shelter.id,
            shelter.careRegNo,
            shelter.name,
            shelter.phone,
            shelter.address,
            shelter.ownerName,
            shelter.organizationName,
            shelter.email,
            shelter.introduction,
            shelter.adoptionProcedure,
            shelter.operatingHours,
            shelter.createdAt,
            shelter.updatedAt,
        )
    }
}
