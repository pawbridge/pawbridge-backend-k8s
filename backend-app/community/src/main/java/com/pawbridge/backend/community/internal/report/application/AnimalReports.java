package com.pawbridge.backend.community.internal.report.application;

import com.pawbridge.backend.community.internal.post.application.PostUpload;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** The existing report workflow; storage and framework transactions stay in its adapter. */
public interface AnimalReports {
    Slice list(Filter filter);
    Details get(long reportId);
    Details create(Draft request, List<PostUpload> photos, Long authorId);
    Details update(long reportId, Draft request, List<PostUpload> photos, Long authorId);
    void delete(long reportId, Long authorId);

    enum Kind { MISSING, SIGHTING }
    enum AnimalType { DOG, CAT, OTHER }
    record Draft(Kind kind, LocalDate occurredOn, String approximateTime, String region,
                 String landmark, String species, String animalName, String coatColor,
                 String animalSize, String distinguishingFeatures, String direction,
                 String description, String province, String district, AnimalType animalType) {}
    record Filter(int page, int size, Kind kind, String keyword, String province,
                  String district, AnimalType animalType, LocalDate from, LocalDate to) {}
    record Slice(List<Details> content, long total) {}
    record Details(Long reportId, Long authorId, String authorNickname, String title,
                   String description, List<String> imageUrls, Kind kind, LocalDate occurredOn,
                   String approximateTime, String region, String landmark, String species,
                   String animalName, String coatColor, String animalSize,
                   String distinguishingFeatures, String direction, LocalDateTime createdAt,
                   LocalDateTime updatedAt, String province, String district, AnimalType animalType) {}
}
