package com.pawbridge.animalservice.travel;

import java.util.Map;
import java.net.URI;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

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
        return places(areaCode, 0);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PetTravelResponse.Places places(String areaCode, int page) {
        if (page < 0) invalid();
        if (areaCode == null || !areaCode.matches("[0-9]{2,5}")) invalid();
        var region = repository.regions().stream().filter(row -> row.code().equals(areaCode)).findFirst()
                .orElseThrow(() -> new PetTravelException(PetTravelException.Code.INVALID_REQUEST));
        long total = repository.countPlaces(areaCode);
        var data = (long) page * 10 < total ? repository.places(areaCode, page) : java.util.List.<PetTravelCatalog.Place>of();
        var state = repository.collectionState();
        boolean failed = "COLLECTION_FAILED".equals(state.errorCode());
        String availability = region.completedAt() == null ? (total == 0 ? (failed ? "FAILED" : "PREPARING") : "PARTIAL") : (failed ? "STALE" : "READY");
        return new PetTravelResponse.Places(areaCode, data.stream().map(row -> place(row.common())).toList(),
                true, region.completedAt(), availability, page, 10, total, (total + 9) / 10);
    }

    public PetTravelResponse.Detail detail(String contentId) {
        if (contentId == null || !contentId.matches("[0-9]{1,20}")) invalid();
        var snapshot = repository.detail(contentId)
                .orElseThrow(() -> new PetTravelException(PetTravelException.Code.NOT_FOUND));
        var row = snapshot.common();
        var conditions = snapshot.pet();
        var intro=snapshot.intro();
        var additional=snapshot.information().stream()
                .map(item->new PetTravelResponse.InformationItem(value(item,"infoname"),value(item,"infotext")))
                .filter(item->item.name()!=null && item.text()!=null).toList();
        var visitInformation=new PetTravelResponse.VisitInformation(value(row,"contenttypeid"),
                first(intro,"infocenter","infocenterculture","infocenterleports"),
                first(intro,"opendate","openperiod"),
                first(intro,"usetime","usetimeculture","usetimeleports"),
                first(intro,"restdate","restdateculture","restdateleports"),
                first(intro,"parking","parkingculture","parkingleports"),
                first(intro,"parkingfee","parkingfeeleports"),first(intro,"usefee","usefeeleports"),
                value(intro,"reservation"),first(intro,"expagerange","expagerangeleports"),
                value(intro,"expguide"),value(intro,"useseason"),first(intro,"scale","scaleleports"),
                value(intro,"spendtime"),value(intro,"discountinfo"),additional,snapshot.visitInformationStatus(),
                latest(snapshot.introFetchedAt(),snapshot.informationFetchedAt()));
        var images=snapshot.images().stream().map(image -> new PetTravelResponse.Image(
                        providerImageUrl(image.originalUrl(),image.copyrightType()),
                        providerImageUrl(image.thumbnailUrl(),image.copyrightType()),image.name(),image.copyrightType()))
                .filter(image->image.originalUrl()!=null).toList();
        return new PetTravelResponse.Detail(place(row), value(row, "overview"),
                new PetTravelResponse.Conditions(value(conditions, "acmpyTypeCd"), value(conditions, "acmpyPsblCpam"),
                        value(conditions, "acmpyNeedMtr"), value(conditions, "etcAcmpyInfo"),
                        value(conditions, "relaAcdntRiskMtr"), value(conditions, "relaPosesFclty"), value(conditions, "relaFrnshPrdlst")),
                Stream.of("acmpyTypeCd", "acmpyPsblCpam", "acmpyNeedMtr", "etcAcmpyInfo",
                        "relaAcdntRiskMtr", "relaPosesFclty", "relaFrnshPrdlst")
                        .anyMatch(field -> value(conditions, field) != null),
                "KOREA_TOURISM_ORGANIZATION", snapshot.basicFetchedAt() == null ? snapshot.publishedAt() : snapshot.basicFetchedAt(),
                snapshot.publishedAt(),snapshot.detailStatus(),visitInformation,images,snapshot.imagesStatus(),snapshot.imagesFetchedAt());
    }

    private PetTravelResponse.Place place(Map<String, String> row) {
        return new PetTravelResponse.Place(required(row, "contentid"), required(row, "title"), value(row, "addr1"), imageUrl(row));
    }

    // This API publishes Type1/Type3 representative photos. Do not infer a missing license
    // from another record or fetch an image per card. Frontend supplies attribution.
    private static String imageUrl(Map<String, String> row) {
        var copyright = value(row, "cpyrhtDivCd");
        return providerImageUrl(value(row,"firstimage"),copyright);
    }

    private static String providerImageUrl(String image, String copyright) {
        if (!"Type1".equals(copyright) && !"Type3".equals(copyright)) return null;
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

    private static String first(Map<String,String> row, String... keys) {
        for (var key : keys) {
            var value=value(row,key);
            if (value!=null) return value;
        }
        return null;
    }

    private static java.time.Instant latest(java.time.Instant first, java.time.Instant second) {
        if (first==null) return second;
        if (second==null) return first;
        return first.isAfter(second)?first:second;
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
