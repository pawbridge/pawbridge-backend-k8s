package com.pawbridge.animalservice.travel;

import java.time.Instant;
import java.util.List;

public final class PetTravelResponse {
    private PetTravelResponse() {}
    public record Region(String code, String name) {}
    public record Regions(List<Region> items, Instant fetchedAt, String availability) {
        public Regions(List<Region> items, Instant fetchedAt) { this(items, fetchedAt, "READY"); }
    }
    // imageUrl is a provider-hosted Type1/Type3 photo or null; consumers must show
    // attribution and preserve the complete image without cropping or modification.
    public record Place(String contentId, String title, String address, String imageUrl) {}
    // previewOnly is retained for older clients; paged clients use the page metadata.
    public record Places(String areaCode, List<Place> items, boolean previewOnly, Instant fetchedAt, String availability,
                         int page, int size, long totalElements, long totalPages) {
        public Places(String areaCode, List<Place> items, boolean previewOnly, Instant fetchedAt) {
            this(areaCode, items, previewOnly, fetchedAt, "READY", 0, 10, items.size(), items.isEmpty() ? 0 : 1);
        }
    }
    public record Conditions(String areas, String allowedAnimals, String requirements, String otherInformation,
                             String risks, String facilities, String providedItems) {}
    public record InformationItem(String name, String text) {}
    public record VisitInformation(String contentTypeId, String informationCenter, String openPeriod,
                                   String usageHours, String restDate, String parking, String parkingFee,
                                   String usageFee, String reservation, String ageRange, String experienceGuide,
                                   String usageSeason, String scale, String estimatedDuration,
                                   String discountInformation, List<InformationItem> additionalItems,
                                   String status, Instant fetchedAt) {}
    public record Image(String originalUrl, String thumbnailUrl, String name, String copyrightType) {}
    public record Detail(Place place, String overview, Conditions conditions, boolean petInformationAvailable,
                         String source, Instant fetchedAt, Instant petInformationFetchedAt, String petInformationStatus,
                         VisitInformation visitInformation, List<Image> images, String imagesStatus,
                         Instant imagesFetchedAt) {
        public Detail(Place place,String overview,Conditions conditions,boolean petInformationAvailable,
                      String source,Instant fetchedAt,Instant petInformationFetchedAt) {
            this(place,overview,conditions,petInformationAvailable,source,fetchedAt,petInformationFetchedAt,
                    petInformationFetchedAt == null ? "PREPARING" : "READY",null,List.of(),"PREPARING",null);
        }
        public Detail(Place place,String overview,Conditions conditions,boolean petInformationAvailable,
                      String source,Instant fetchedAt,Instant petInformationFetchedAt,String petInformationStatus) {
            this(place,overview,conditions,petInformationAvailable,source,fetchedAt,petInformationFetchedAt,
                    petInformationStatus,null,List.of(),"PREPARING",null);
        }
    }
}
