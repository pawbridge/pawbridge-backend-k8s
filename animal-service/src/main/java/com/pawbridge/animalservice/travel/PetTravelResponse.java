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
    public record Places(String areaCode, List<Place> items, boolean previewOnly, Instant fetchedAt, String availability) {
        public Places(String areaCode, List<Place> items, boolean previewOnly, Instant fetchedAt) {
            this(areaCode, items, previewOnly, fetchedAt, "READY");
        }
    }
    public record Conditions(String areas, String allowedAnimals, String requirements, String otherInformation,
                             String risks, String facilities, String providedItems) {}
    public record Detail(Place place, String overview, Conditions conditions, boolean petInformationAvailable,
                         String source, Instant fetchedAt, Instant petInformationFetchedAt, String petInformationStatus) {
        public Detail(Place place,String overview,Conditions conditions,boolean petInformationAvailable,
                      String source,Instant fetchedAt,Instant petInformationFetchedAt) {
            this(place,overview,conditions,petInformationAvailable,source,fetchedAt,petInformationFetchedAt,
                    petInformationFetchedAt == null ? "PREPARING" : "READY");
        }
    }
}
