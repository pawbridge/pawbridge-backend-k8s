package com.pawbridge.backend.user.internal.shelter.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface ShelterMembership {
    enum Status { PENDING, APPROVED, REJECTED }
    record Application(Long id,Long userId,String shelterName,Status status,LocalDateTime requestedAt,
            LocalDateTime reviewedAt,Long reviewedBy,String reason,String careRegNo) { }
    record AdminApplication(Application application,String applicantName,String applicantEmail,String reviewNote) { }
    record Member(Long userId,String name,String email,Long approvalApplicationId) { }
    record Daily(LocalDate date,long count) { }
    record Statistics(LocalDate startDate,LocalDate endDate,List<Daily> daily,long previousDayCount,
            long currentPending,long approvedCount,long rejectedCount) { }
    record Page<T>(List<T> content,long total,int number,int size) { }
    record Shelter(Long id,String careRegNo) { }
    @FunctionalInterface interface ShelterLookup { Shelter find(String careRegNo); }
    final class MissingShelter extends RuntimeException { }
    Application submit(long currentUserId,String shelterName);
    Page<Application> mine(long currentUserId,int page,int size);
    Page<AdminApplication> list(long currentUserId,Status status,int page,int size);
    AdminApplication detail(long currentUserId,long id);
    AdminApplication approve(long currentUserId,long id,String careRegNo,String note);
    AdminApplication reject(long currentUserId,long id,String reason);
    Page<Member> members(long currentUserId,String careRegNo,int page,int size);
    Statistics statistics(long currentUserId,LocalDate startDate,LocalDate endDate);
}
