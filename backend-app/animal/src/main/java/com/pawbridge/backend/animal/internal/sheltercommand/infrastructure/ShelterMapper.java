package com.pawbridge.backend.animal.internal.sheltercommand.infrastructure;

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter;
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands;

/** Selected original ShelterMapper fields; neither normalization nor defaults are added. */
final class ShelterMapper {
    Shelter toEntity(ShelterCommands.Create request) {
        return Shelter.builder().careRegNo(request.careRegNo()).name(request.name()).phone(request.phone())
                .address(request.address()).ownerName(request.ownerName()).organizationName(request.organizationName())
                .email(request.email()).introduction(request.introduction()).adoptionProcedure(request.adoptionProcedure())
                .operatingHours(request.operatingHours()).build();
    }
    ShelterCommands.Details toDetailResponse(Shelter shelter) {
        return new ShelterCommands.Details(shelter.getId(), shelter.getCareRegNo(), shelter.getName(), shelter.getPhone(),
                shelter.getAddress(), shelter.getOwnerName(), shelter.getOrganizationName(), shelter.getEmail(),
                shelter.getIntroduction(), shelter.getAdoptionProcedure(), shelter.getOperatingHours(),
                shelter.getCreatedAt(), shelter.getUpdatedAt());
    }
}
