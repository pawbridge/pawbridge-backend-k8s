package com.pawbridge.userservice.shelter;

public record ShelterMemberResponse(Long userId, String name, String email, Long approvalApplicationId) {}
