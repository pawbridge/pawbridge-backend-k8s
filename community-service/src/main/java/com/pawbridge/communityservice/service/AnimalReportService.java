package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.domain.entity.AnimalReport;
import com.pawbridge.communityservice.domain.repository.AnimalReportRepository;
import com.pawbridge.communityservice.dto.request.CreateAnimalReportRequest;
import com.pawbridge.communityservice.dto.response.AnimalReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AnimalReportService {
    private static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024;
    private static final Set<String> PROVINCES = Set.of("서울특별시", "부산광역시", "대구광역시",
            "인천광역시", "광주광역시", "대전광역시", "울산광역시", "세종특별자치시", "경기도",
            "강원특별자치도", "충청북도", "충청남도", "전북특별자치도", "전라남도",
            "경상북도", "경상남도", "제주특별자치도");

    private final AnimalReportRepository reportRepository;
    private final S3Service s3Service;
    private final UserServiceClient userServiceClient;

    @Transactional
    public AnimalReportResponse create(CreateAnimalReportRequest request, MultipartFile[] photos, Long authorId) {
        validate(request, photos);
        if (authorId == null || authorId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        List<String> imageUrls = s3Service.uploadReportImages(photos);
        try {
            AnimalReport report = new AnimalReport(
                    authorId, request.kind(), request.description().trim(), imageUrls,
                    request.occurredOn(), optional(request.approximateTime()), request.region().trim(),
                    optional(request.landmark()), request.species().trim(), optional(request.animalName()),
                    optional(request.coatColor()), optional(request.animalSize()),
                    optional(request.distinguishingFeatures()), optional(request.direction()));
            report.classify(optional(request.province()), optional(request.district()), request.animalType());
            AnimalReport saved = reportRepository.saveAndFlush(report);
            return response(saved);
        } catch (RuntimeException failure) {
            imageUrls.forEach(s3Service::deleteFile);
            throw failure;
        }
    }

    @Transactional
    public AnimalReportResponse update(Long reportId, CreateAnimalReportRequest request,
                                       MultipartFile[] photos, Long authorId) {
        validate(request, photos);
        if (photos != null && photos.length > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "등록 후 사진 교체는 아직 지원하지 않습니다.");
        }
        AnimalReport report = findVisible(reportId);
        requireAuthor(report, authorId);
        if (report.getKind() != request.kind()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제보 종류는 변경할 수 없습니다.");
        }
        report.update(request.description().trim(), request.occurredOn(), optional(request.approximateTime()),
                request.region().trim(), optional(request.landmark()), request.species().trim(),
                optional(request.animalName()), optional(request.coatColor()), optional(request.animalSize()),
                optional(request.distinguishingFeatures()), optional(request.direction()));
        // Older clients omit these fields. Their edits must not erase an existing classification.
        if (request.province() != null || request.district() != null || request.animalType() != null) {
            report.classify(optional(request.province()), optional(request.district()), request.animalType());
        }
        return response(report);
    }

    @Transactional
    public void delete(Long reportId, Long authorId) {
        AnimalReport report = findVisible(reportId);
        requireAuthor(report, authorId);
        report.delete();
    }

    @Transactional(readOnly = true)
    public AnimalReportResponse get(Long reportId) {
        return response(findVisible(reportId));
    }

    @Transactional(readOnly = true)
    public Page<AnimalReportResponse> list(AnimalReport.Kind kind, String keyword, Pageable pageable) {
        String normalized = keyword == null || keyword.isBlank() ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return reportRepository.searchVisible(kind, normalized, pageable).map(this::response);
    }

    @Transactional(readOnly = true)
    public Page<AnimalReportResponse> list(AnimalReport.Kind kind, String keyword,
                                          String province, String district, AnimalReport.AnimalType animalType,
                                          LocalDate from, LocalDate to, Pageable pageable) {
        validateClassification(province, district);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        if ((from != null && from.isAfter(today)) || (to != null && to.isAfter(today))
                || (from != null && to != null && from.isAfter(to))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "실종·목격 날짜 범위를 확인해 주세요.");
        }
        String normalized = keyword == null || keyword.isBlank() ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return reportRepository.searchVisible(kind, normalized, optional(province), optional(district),
                animalType, from, to, pageable).map(this::response);
    }

    private static void validateClassification(String province, String district) {
        String normalizedProvince = optional(province);
        String normalizedDistrict = optional(district);
        if ((normalizedProvince != null && !PROVINCES.contains(normalizedProvince))
                || tooLong(district, 40) || (normalizedDistrict != null && normalizedProvince == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시·도와 시·군·구 선택을 확인해 주세요.");
        }
    }

    private AnimalReport findVisible(Long reportId) {
        return reportRepository.findByReportIdAndDeletedAtIsNull(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static void requireAuthor(AnimalReport report, Long authorId) {
        if (authorId == null || !report.getAuthorId().equals(authorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private AnimalReportResponse response(AnimalReport report) {
        String nickname;
        try {
            nickname = userServiceClient.getUserNickname(report.getAuthorId());
        } catch (RuntimeException unavailable) {
            nickname = "사용자" + report.getAuthorId();
        }
        return AnimalReportResponse.from(report, nickname);
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validate(CreateAnimalReportRequest request, MultipartFile[] photos) {
        if (request == null || request.kind() == null || request.occurredOn() == null
                || request.occurredOn().isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")))
                || blankOrTooLong(request.region(), 120) || blankOrTooLong(request.species(), 40)
                || blankOrTooLong(request.description(), 10000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "필수 제보 정보를 확인해 주세요.");
        }
        validateClassification(request.province(), request.district());
        if (tooLong(request.approximateTime(), 40) || tooLong(request.landmark(), 200)
                || tooLong(request.animalName(), 80) || tooLong(request.coatColor(), 100)
                || tooLong(request.animalSize(), 40)
                || tooLong(request.distinguishingFeatures(), 500) || tooLong(request.direction(), 200)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제보 내용의 글자 수를 확인해 주세요.");
        }
        if (photos == null) return;
        if (photos.length > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진은 최대 5장까지 첨부할 수 있습니다.");
        }
        for (MultipartFile photo : photos) validatePhoto(photo);
    }

    private static boolean blankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.trim().length() > max;
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.trim().length() > max;
    }

    private static void validatePhoto(MultipartFile photo) {
        if (photo == null || photo.isEmpty() || photo.getSize() > MAX_PHOTO_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진은 각 10MB 이하로 첨부해 주세요.");
        }
        String type = photo.getContentType();
        String name = photo.getOriginalFilename();
        if (type == null || name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 형식을 확인해 주세요.");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        boolean extensionMatches = switch (type.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            case "image/png" -> lower.endsWith(".png");
            case "image/webp" -> lower.endsWith(".webp");
            default -> false;
        };
        if (!extensionMatches) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JPG, PNG, WebP 사진만 첨부할 수 있습니다.");
        }
        try (var stream = photo.getInputStream()) {
            byte[] header = stream.readNBytes(12);
            boolean jpeg = type.equalsIgnoreCase("image/jpeg") && header.length >= 3
                    && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff;
            boolean png = type.equalsIgnoreCase("image/png") && header.length >= 8
                    && (header[0] & 0xff) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                    && header[4] == 13 && header[5] == 10 && (header[6] & 0xff) == 0x1a && header[7] == 10;
            boolean webp = type.equalsIgnoreCase("image/webp") && header.length >= 12
                    && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
            if (!jpeg && !png && !webp) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 파일의 실제 형식을 확인해 주세요.");
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진을 읽을 수 없습니다.", e);
        }
    }
}
