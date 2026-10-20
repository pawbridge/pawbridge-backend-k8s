package com.pawbridge.backend.animal.internal.discovery.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Public animal discovery only; collection, projection and writes keep their existing owner. */
public interface AnimalDiscovery {
    Page search(Search request, PageRequest page);
    Details findById(long id);

    enum Species { DOG, CAT, ETC }
    enum Gender { MALE, FEMALE, UNKNOWN }
    enum NeuterStatus { YES, NO, UNKNOWN }
    enum AnimalStatus {
        NOTICE, PROTECT, ADOPTION_PENDING, ADOPTED, EUTHANIZED, NATURAL_DEATH,
        RETURNED, DONATED, RELEASED, ESCAPED, UNKNOWN
    }
    enum ApiSource { APMS_ANIMAL, GYEONGGI, MANUAL, UNKNOWN }

    record Search(Species species, String breed, Gender gender, NeuterStatus neuterStatus,
                  AnimalStatus status, Integer minAge, Integer maxAge, String region, String city,
                  String keyword, String noticeNo, Long shelterId, LocalDate intakeFrom, LocalDate intakeTo) {
        public static Search empty() {
            return new Search(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    record Order(String property, boolean ascending) { }

    record PageRequest(int number, int size, List<Order> orders, boolean paged) {
        public PageRequest { orders = List.copyOf(orders); }
        public long offset() { return (long) number * size; }
    }

    record Page(List<Summary> content, int number, int size, long totalElements,
                int totalPages, boolean first, boolean last) {
        public Page { content = List.copyOf(content); }
        public static Page from(List<Summary> content, PageRequest request, long total) {
            // Same last-page total adaptation as the source PageImpl.
            long adjusted = !content.isEmpty() && request.offset() + request.size() > total
                    ? request.offset() + content.size() : total;
            int pages = (int) Math.ceil((double) adjusted / request.size());
            return new Page(content, request.number(), request.size(), adjusted, pages,
                    request.number() == 0, request.number() >= pages - 1);
        }
    }

    record Summary(Long id, String apmsNoticeNo, Species species, String breed, Gender gender,
                   Integer birthYear, Integer age, String specialMark, AnimalStatus status,
                   LocalDate noticeEndDate, String imageUrl, Integer favoriteCount,
                   Long shelterId, String shelterName, LocalDateTime createdAt) { }

    record Details(Long id, String apmsNoticeNo, Species species, String breed, Gender gender,
                   NeuterStatus neuterStatus, Integer birthYear, Integer age, String weight, String color,
                   String specialMark, AnimalStatus status, LocalDate noticeStartDate, LocalDate noticeEndDate,
                   String happenPlace, LocalDate happenDate, String imageUrl, String imageUrl2,
                   String description, Integer favoriteCount, Long shelterId, String shelterName,
                   String apmsDesertionNo, String apmsProcessState, LocalDateTime apmsUpdatedAt,
                   ApiSource apiSource, LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
