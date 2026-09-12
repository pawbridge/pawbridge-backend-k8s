package com.pawbridge.userservice.shelter;

import com.pawbridge.userservice.entity.User;

public record AdminShelterApplicationResponse(ShelterApplicationResponse application,
        String applicantName, String applicantEmail, String reviewNote) {
    public static AdminShelterApplicationResponse from(ShelterApplication a, User user) {
        return new AdminShelterApplicationResponse(ShelterApplicationResponse.from(a),
                user == null ? "탈퇴 회원" : user.getName(), user == null ? null : user.getEmail(), a.getReviewNote());
    }
}
