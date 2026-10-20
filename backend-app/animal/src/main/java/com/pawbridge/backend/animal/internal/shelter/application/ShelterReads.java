package com.pawbridge.backend.animal.internal.shelter.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/** Six read-only ShelterController/ShelterDiscoveryController contracts; no directory collector. */
public interface ShelterReads {
    Page<Summary> search(Search search, PageRequest page);
    Details findById(long id);
    Details findByCareRegNo(String careRegNo);
    long count();
    Page<Discovery> discover(String keyword, String address, LocalDate from, LocalDate to, int page, int size);
    List<Observation> observations(long shelterId, LocalDate from, LocalDate to);

    record Search(String name, String address, String organizationName, String keyword) { }
    record Order(String property, boolean ascending, boolean ignoreCase) { }
    record PageRequest(int number, int size, List<Order> orders) {
        public PageRequest { orders = List.copyOf(orders); }
        public long offset() { return (long) number * size; }
    }
    record Page<T>(List<T> content, long totalElements) {
        public Page { content = List.copyOf(content); }
    }
    record Summary(Long id, String careRegNo, String name, String phone, String address,
                   String organizationName, LocalDateTime createdAt) { }
    record Details(Long id, String careRegNo, String name, String phone, String address, String ownerName,
                   String organizationName, String email, String introduction, String adoptionProcedure,
                   String operatingHours, LocalDateTime createdAt, LocalDateTime updatedAt,
                   PublicInformation publicInformation) { }
    record PublicInformation(String source, String phone, Double latitude, Double longitude,
            String weekdayOpen, String weekdayClose, String weekendOpen, String weekendClose,
            String closedDays, String sourceUpdatedDate, LocalDateTime collectedAt) { }
    record Discovery(long id, String careRegNo, String name, String address, String phone,
                     long protectedCount, List<Preview> animals) { }
    record Preview(long id, String breed, String species, String gender, Integer birthYear,
                   String imageUrl, LocalDate happenDate) { }
    record Observation(LocalDate date, OffsetDateTime observedAt, long protectedCount) { }
}
