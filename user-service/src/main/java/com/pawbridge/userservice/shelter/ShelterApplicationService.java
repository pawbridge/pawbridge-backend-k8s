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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShelterApplicationService {
    private final ShelterApplicationRepository applications;
    private final UserRepository users;
    private final AnimalServiceClient animals;
    private final JwtProvider jwtProvider;
    private final EntityManager entityManager;

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
