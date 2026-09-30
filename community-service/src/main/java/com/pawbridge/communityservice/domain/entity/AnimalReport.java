package com.pawbridge.communityservice.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "animal_reports")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class AnimalReport {
    public enum Kind { MISSING, SIGHTING }
    public enum AnimalType { DOG, CAT, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Convert(converter = StringListConverter.class)
    @Column(name = "image_urls", columnDefinition = "JSON")
    private List<String> imageUrls = new ArrayList<>();

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Column(name = "approximate_time", length = 40)
    private String approximateTime;

    @Column(name = "region", nullable = false, length = 120)
    private String region;

    @Column(name = "province", length = 40)
    private String province;

    @Column(name = "district", length = 40)
    private String district;

    @Enumerated(EnumType.STRING)
    @Column(name = "animal_type", length = 16)
    private AnimalType animalType;

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

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public AnimalReport(Long authorId, Kind kind, String description, List<String> imageUrls,
                        LocalDate occurredOn, String approximateTime, String region, String landmark,
                        String species, String animalName, String coatColor, String animalSize,
                        String distinguishingFeatures, String direction) {
        this.authorId = authorId;
        this.kind = kind;
        this.description = description;
        this.imageUrls = new ArrayList<>(imageUrls);
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

    public void update(String description, LocalDate occurredOn, String approximateTime,
                       String region, String landmark, String species, String animalName,
                       String coatColor, String animalSize, String distinguishingFeatures, String direction) {
        this.description = description;
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

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    public void classify(String province, String district, AnimalType animalType) {
        this.province = province;
        this.district = district;
        this.animalType = animalType;
    }
}
