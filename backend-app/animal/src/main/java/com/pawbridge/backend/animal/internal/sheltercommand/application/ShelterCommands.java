package com.pawbridge.backend.animal.internal.sheltercommand.application;

import java.time.LocalDateTime;

public interface ShelterCommands {
    Details create(Long verifiedUserId, Create request);
    Details update(Long verifiedUserId, Long id, Update request);
    void delete(Long verifiedUserId, Long id);

    record Create(String careRegNo, String name, String phone, String address, String ownerName,
                  String organizationName, String email, String introduction, String adoptionProcedure,
                  String operatingHours) { }
    record Update(String phone, String email, String introduction, String adoptionProcedure, String operatingHours) { }
    record Details(Long id, String careRegNo, String name, String phone, String address, String ownerName,
                   String organizationName, String email, String introduction, String adoptionProcedure,
                   String operatingHours, LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
