package com.pawbridge.animalservice.dto.response;

import java.time.LocalDate;
import java.util.List;

public record ShelterDiscoveryResponse(long id, String careRegNo, String name, String address,
        String phone, long protectedCount, List<Preview> animals) {
    public record Preview(long id, String breed, String species, String gender,
                          Integer birthYear, String imageUrl, LocalDate happenDate) {}
}
