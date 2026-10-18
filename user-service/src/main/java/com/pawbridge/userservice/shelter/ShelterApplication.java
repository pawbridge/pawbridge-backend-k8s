package com.pawbridge.userservice.shelter;

import com.pawbridge.userservice.exception.common.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "shelter_applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShelterApplication {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private Long userId;
    @Column(nullable = false, length = 100, updatable = false)
    private String shelterName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ShelterApplicationStatus status;
    @Column(nullable = false, updatable = false)
    private LocalDateTime requestedAt;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    @Column(length = 1000)
    private String reviewNote;
    @Column(length = 50)
    private String careRegNo;

    public static ShelterApplication request(Long userId, String name) {
        ShelterApplication application = new ShelterApplication();
        application.userId = userId;
        application.shelterName = requiredText(name, 100);
        application.status = ShelterApplicationStatus.PENDING;
        application.requestedAt = LocalDateTime.now();
        return application;
    }

    public void requirePending() {
        if (status != ShelterApplicationStatus.PENDING) {
            throw new ShelterApplicationException(ErrorCode.SHELTER_APPLICATION_CONFLICT);
        }
    }

    public void approve(Long administratorId, String registration, String note) {
        requirePending();
        String validRegistration = requiredText(registration, 50);
        String validNote = requiredText(note, 1000);
        careRegNo = validRegistration;
        review(administratorId, validNote, ShelterApplicationStatus.APPROVED);
    }

    public void reject(Long administratorId, String reason) {
        requirePending();
        review(administratorId, requiredText(reason, 1000), ShelterApplicationStatus.REJECTED);
    }

    private void review(Long administratorId, String note, ShelterApplicationStatus decision) {
        status = decision;
        reviewedBy = administratorId;
        reviewedAt = LocalDateTime.now();
        reviewNote = note;
    }

    static String requiredText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new ShelterApplicationException(ErrorCode.SHELTER_APPLICATION_INVALID);
        }
        return value.strip();
    }
}
