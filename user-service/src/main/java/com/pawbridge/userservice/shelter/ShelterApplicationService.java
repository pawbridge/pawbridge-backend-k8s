package com.pawbridge.userservice.shelter;

import com.pawbridge.userservice.client.AnimalServiceClient;
import com.pawbridge.userservice.dto.response.ShelterResponse;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.exception.common.ErrorCode;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.pawbridge.userservice.repository.UserRepository;
import feign.FeignException;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.Map;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ShelterApplicationService {
    private final ShelterApplicationRepository applications;
    private final UserRepository users;
    private final AnimalServiceClient animals;
    private final JwtProvider jwtProvider;
    private final EntityManager entityManager;
    private final Clock clock;

    @Autowired
    public ShelterApplicationService(ShelterApplicationRepository applications, UserRepository users,
            AnimalServiceClient animals, JwtProvider jwtProvider, EntityManager entityManager) {
        this(applications, users, animals, jwtProvider, entityManager, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    ShelterApplicationService(ShelterApplicationRepository applications, UserRepository users,
            AnimalServiceClient animals, JwtProvider jwtProvider, EntityManager entityManager, Clock clock) {
        this.applications = applications;
        this.users = users;
        this.animals = animals;
        this.jwtProvider = jwtProvider;
        this.entityManager = entityManager;
        this.clock = clock.withZone(ZoneId.of("Asia/Seoul"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShelterApplicationStatsResponse statistics(String authorization, LocalDate startDate, LocalDate endDate) {
        administrator(authorization);
        if (startDate == null || endDate == null || startDate.isAfter(endDate)
                || endDate.isAfter(LocalDate.now(clock)) || ChronoUnit.DAYS.between(startDate, endDate) >= 366) {
            throw new ShelterApplicationException(ErrorCode.INVALID_INPUT);
        }
        LocalDate previousDate = startDate.minusDays(1);
        var rows = applications.countDailyRequests(previousDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());
        var reviews = applications.countReviews(startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay());
        return new ShelterApplicationStatsResponse(startDate, endDate,
                rows.stream().filter(row -> !row.date().isBefore(startDate)).toList(),
                rows.stream().filter(row -> row.date().equals(previousDate)).mapToLong(DailyShelterApplicationStats::count).sum(),
                applications.countByStatus(ShelterApplicationStatus.PENDING),
                reviews.stream().filter(row -> row.status() == ShelterApplicationStatus.APPROVED).mapToLong(ShelterReviewStats::count).sum(),
                reviews.stream().filter(row -> row.status() == ShelterApplicationStatus.REJECTED).mapToLong(ShelterReviewStats::count).sum());
    }

    private Long authenticatedId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ShelterApplicationException(ErrorCode.TOKEN_INVALID);
        }
        return jwtProvider.getAccessUserId(authorization.substring(7));
    }

    private User authenticatedUser(String authorization) {
        return users.findById(authenticatedId(authorization))
                .orElseThrow(() -> new ShelterApplicationException(ErrorCode.TOKEN_INVALID));
    }

    private User administrator(String authorization) {
        User user = authenticatedUser(authorization);
        if (user.getRole() != Role.ROLE_ADMIN) {
            throw new ShelterApplicationException(ErrorCode.SHELTER_APPLICATION_FORBIDDEN);
        }
        return user;
    }

    private Pageable page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ShelterApplicationException(ErrorCode.SHELTER_APPLICATION_INVALID);
        }
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    }

    @Transactional
    public ShelterApplicationResponse submit(String authorization, String shelterName) {
        User user = users.findByIdForUpdate(authenticatedId(authorization))
                .orElseThrow(() -> new ShelterApplicationException(ErrorCode.TOKEN_INVALID));
        if (user.getRole() != Role.ROLE_USER) {
            throw new ShelterApplicationException(ErrorCode.SHELTER_APPLICATION_FORBIDDEN);
        }
        if (applications.existsByUserIdAndStatus(user.getUserId(), ShelterApplicationStatus.PENDING)) {
            throw new ShelterApplicationException(ErrorCode.SHELTER_APPLICATION_CONFLICT);
        }
        return ShelterApplicationResponse.from(applications.save(ShelterApplication.request(user.getUserId(), shelterName)));
    }

    public Page<ShelterApplicationResponse> mine(String authorization, int page, int size) {
        User user = authenticatedUser(authorization);
        return applications.findByUserId(user.getUserId(), page(page, size)).map(ShelterApplicationResponse::from);
    }

    public Page<AdminShelterApplicationResponse> list(String authorization, ShelterApplicationStatus status, int page, int size) {
        administrator(authorization);
        Page<ShelterApplication> result = status == null ? applications.findAll(page(page, size))
                : applications.findByStatus(status, page(page, size));
        Map<Long, User> applicants = users.findAllById(result.map(ShelterApplication::getUserId).getContent())
                .stream().collect(Collectors.toMap(User::getUserId, Function.identity()));
        return result.map(a -> AdminShelterApplicationResponse.from(a, applicants.get(a.getUserId())));
    }

    public Page<ShelterMemberResponse> members(String authorization, String careRegNo, int page, int size) {
        administrator(authorization);
        String registration = ShelterApplication.requiredText(careRegNo, 50);
        Pageable validated = page(page, size);
        Pageable membersPage = PageRequest.of(validated.getPageNumber(), validated.getPageSize(),
                Sort.by(Sort.Direction.DESC, "userId"));
        return users.findByCareRegNoAndRole(registration, Role.ROLE_SHELTER, membersPage).map(user ->
                new ShelterMemberResponse(user.getUserId(), user.getName(), user.getEmail(),
                        applications.findFirstByUserIdAndCareRegNoAndStatusOrderByIdDesc(
                                user.getUserId(), registration, ShelterApplicationStatus.APPROVED)
                                .map(ShelterApplication::getId).orElse(null)));
    }

    public AdminShelterApplicationResponse detail(String authorization, Long id) {
        administrator(authorization);
        ShelterApplication a = find(id);
        return AdminShelterApplicationResponse.from(a, users.findById(a.getUserId()).orElse(null));
    }

    private ShelterApplication find(Long id) {
        return applications.findById(id)
                .orElseThrow(() -> new ShelterApplicationException(ErrorCode.SHELTER_APPLICATION_NOT_FOUND));
    }

    // All submissions and decisions for one applicant use the same user row lock.
    // Refresh after acquiring it: the first lookup may precede a concurrent decision.
    private ShelterApplication lockApplication(Long id) {
        ShelterApplication a = find(id);
        users.findByIdForUpdate(a.getUserId())
                .orElseThrow(() -> new ShelterApplicationException(ErrorCode.SHELTER_APPLICATION_NOT_FOUND));
        entityManager.refresh(a, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        a.requirePending();
        return a;
    }

    @Transactional
    public AdminShelterApplicationResponse approve(String authorization, Long id, String careRegNo, String note) {
        User admin = administrator(authorization);
        String registration = ShelterApplication.requiredText(careRegNo, 50);
        String reviewNote = ShelterApplication.requiredText(note, 1000);
        ShelterApplication a = lockApplication(id);
        User user = users.findById(a.getUserId()).orElseThrow();
        if (user.getRole() != Role.ROLE_USER) {
            throw new ShelterApplicationException(ErrorCode.SHELTER_APPLICATION_CONFLICT);
        }
        ShelterResponse shelter;
        try {
            shelter = animals.getShelterByCareRegNo(registration);
        } catch (FeignException.NotFound e) {
            throw new ShelterApplicationException(ErrorCode.SHELTER_NOT_FOUND);
        } catch (RuntimeException e) {
            throw new ShelterApplicationException(ErrorCode.SHELTER_SERVICE_UNAVAILABLE);
        }
        if (shelter == null || shelter.getId() == null || !registration.equals(shelter.getCareRegNo())) {
            throw new ShelterApplicationException(ErrorCode.SHELTER_SERVICE_UNAVAILABLE);
        }
        a.approve(admin.getUserId(), registration, reviewNote);
        user.updateRole(Role.ROLE_SHELTER);
        user.updateCareRegNo(registration);
        return AdminShelterApplicationResponse.from(a, user);
    }

    @Transactional
    public AdminShelterApplicationResponse reject(String authorization, Long id, String reason) {
        User admin = administrator(authorization);
        ShelterApplication a = lockApplication(id);
        a.reject(admin.getUserId(), reason);
        return AdminShelterApplicationResponse.from(a, users.findById(a.getUserId()).orElse(null));
    }
}
