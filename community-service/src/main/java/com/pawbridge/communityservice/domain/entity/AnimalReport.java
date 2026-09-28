package com.pawbridge.communityservice.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "animal_reports")
@Getter
@NoArgsConstructor
public class AnimalReport {
    public enum Kind { MISSING, SIGHTING }

    @Id
    @Column(name = "post_id")
    private Long postId;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Column(name = "approximate_time", length = 40)
    private String approximateTime;

    @Column(name = "region", nullable = false, length = 120)
    private String region;

    @Column(name = "landmark", length = 200)
    private String landmark;

    @Column(name = "species", nullable = false, length = 40)
    private String species;

    @Column(name = "animal_name", length = 80)
    private String animalName;

    @Column(name = "coat_color", length = 100)
    private String coatColor;

    @Column(name = "animal_size", length = 40)
    private String animalSize;

    @Column(name = "distinguishing_features", length = 500)
    private String distinguishingFeatures;

    @Column(name = "direction", length = 200)
    private String direction;

    public AnimalReport(Long postId, Kind kind, LocalDate occurredOn, String approximateTime,
                        String region, String landmark, String species, String animalName,
                        String coatColor, String animalSize, String distinguishingFeatures, String direction) {
        this.postId = postId;
        this.kind = kind;
        this.occurredOn = occurredOn;
        this.approximateTime = approximateTime;
        this.region = region;
        this.landmark = landmark;
        this.species = species;
        this.animalName = animalName;
        this.coatColor = coatColor;
        this.animalSize = animalSize;
        this.distinguishingFeatures = distinguishingFeatures;
        this.direction = direction;
    }

    public void update(Kind kind, LocalDate occurredOn, String approximateTime,
                       String region, String landmark, String species, String animalName,
                       String coatColor, String animalSize, String distinguishingFeatures, String direction) {
        this.kind = kind;
        this.occurredOn = occurredOn;
        this.approximateTime = approximateTime;
        this.region = region;
        this.landmark = landmark;
        this.species = species;
        this.animalName = animalName;
        this.coatColor = coatColor;
        this.animalSize = animalSize;
        this.distinguishingFeatures = distinguishingFeatures;
        this.direction = direction;
    }
}
