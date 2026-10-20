package com.pawbridge.backend.animal.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Three Animal reads used by already-authorized User activities and shelter membership. */
public interface AnimalMemberReads extends AnimalShelterReads {
    record Animal(Long id, String apmsNoticeNo, String species, String breed, String gender,
            Integer birthYear, Integer age, String specialMark, String status, LocalDate noticeEndDate,
            String imageUrl, Integer favoriteCount, Long shelterId, String shelterName, LocalDateTime createdAt) { }
    /** sort is the single flattened HTTP sort value emitted by the existing User controller. */
    record PageRequest(int page, int size, String sort) { }
    record Page(List<Animal> content, int number, int size, long totalElements, int totalPages, boolean last) { }
    List<Animal> batch(List<Long> ids);
    Page byShelter(Long shelterId, PageRequest request);
}
