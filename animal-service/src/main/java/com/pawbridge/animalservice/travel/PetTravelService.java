package com.pawbridge.animalservice.travel;

import java.util.Map;
import java.net.URI;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class PetTravelService {
    private final PetTravelCatalog repository;

    public PetTravelService(PetTravelCatalog repository) { this.repository = repository; }

    public PetTravelResponse.Regions regions() {
        var data = repository.regions();
        var items = data.stream().map(row -> new PetTravelResponse.Region(row.code(), row.name())).toList();
        return new PetTravelResponse.Regions(items, data.stream().map(PetTravelCatalog.Region::fetchedAt)
                .min(java.time.Instant::compareTo).orElse(null),
                data.isEmpty() ? (repository.collectionState().errorCode() == null ? "PREPARING" : "FAILED") : "READY");
    }

    public PetTravelResponse.Places places(String areaCode) {
        if (areaCode == null || !areaCode.matches("[0-9]{2,5}")) invalid();
        var region = repository.regions().stream().filter(row -> row.code().equals(areaCode)).findFirst()
                .orElseThrow(() -> new PetTravelException(PetTravelException.Code.INVALID_REQUEST));
        var data = repository.places(areaCode);
        var state = repository.collectionState();
        boolean failed = "COLLECTION_FAILED".equals(state.errorCode());
        String availability = region.completedAt() == null ? (data.isEmpty() ? (failed ? "FAILED" : "PREPARING") : "PARTIAL") : (failed ? "STALE" : "READY");
        return new PetTravelResponse.Places(areaCode, data.stream().map(row -> place(row.common())).toList(),
                true, region.completedAt(), availability);
    }

    public PetTravelResponse.Detail detail(String contentId) {
        if (contentId == null || !contentId.matches("[0-9]{1,20}")) invalid();
        var snapshot = repository.detail(contentId)
                .orElseThrow(() -> new PetTravelException(PetTravelException.Code.NOT_FOUND));
        var row = snapshot.common();
        var conditions = snapshot.pet();
        return new PetTravelResponse.Detail(place(row), value(row, "overview"),
                new PetTravelResponse.Conditions(value(conditions, "acmpyTypeCd"), value(conditions, "acmpyPsblCpam"),
                        value(conditions, "acmpyNeedMtr"), value(conditions, "etcAcmpyInfo"),
                        value(conditions, "relaAcdntRiskMtr"), value(conditions, "relaPosesFclty"), value(conditions, "relaFrnshPrdlst")),
                Stream.of("acmpyTypeCd", "acmpyPsblCpam", "acmpyNeedMtr", "etcAcmpyInfo",
                        "relaAcdntRiskMtr", "relaPosesFclty", "relaFrnshPrdlst")
                        .anyMatch(field -> value(conditions, field) != null),
                "KOREA_TOURISM_ORGANIZATION", snapshot.basicFetchedAt() == null ? snapshot.publishedAt() : snapshot.basicFetchedAt(),
                snapshot.publishedAt(),snapshot.detailStatus());
    }

    private PetTravelResponse.Place place(Map<String, String> row) {
        return new PetTravelResponse.Place(required(row, "contentid"), required(row, "title"), value(row, "addr1"), imageUrl(row));
    }

    // This API publishes Type1/Type3 representative photos. Do not infer a missing license
    // from another record or fetch an image per card. Frontend supplies attribution.
    private static String imageUrl(Map<String, String> row) {
        var copyright = value(row, "cpyrhtDivCd");
        if (!"Type1".equals(copyright) && !"Type3".equals(copyright)) return null;
        var image = value(row, "firstimage");
        if (image == null) return null;
        try {
            var uri = URI.create(image);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || !"tong.visitkorea.or.kr".equals(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() == null
                    || !uri.getRawPath().matches("/cms/resource/[0-9]+/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp|gif)")) return null;
            // Upgrade only the verified provider image host, never arbitrary HTTP URLs.
            return "https://tong.visitkorea.or.kr" + uri.getRawPath();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String value(Map<String, String> row, String key) {
        var value = row.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    private static String required(Map<String, String> row, String key) {
        var value = value(row, key);
        if (value == null) throw PetTravelException.unavailable();
        return value;
    }

    private static void invalid() { throw new PetTravelException(PetTravelException.Code.INVALID_REQUEST); }
}
