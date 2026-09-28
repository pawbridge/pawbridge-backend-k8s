package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.domain.entity.AnimalReport;
import com.pawbridge.communityservice.domain.entity.BoardType;
import com.pawbridge.communityservice.domain.repository.AnimalReportRepository;
import com.pawbridge.communityservice.dto.request.CreateAnimalReportRequest;
import com.pawbridge.communityservice.dto.request.CreatePostRequest;
import com.pawbridge.communityservice.dto.request.UpdatePostRequest;
import com.pawbridge.communityservice.dto.response.AnimalReportResponse;
import com.pawbridge.communityservice.dto.response.PostResponse;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnimalReportService {
    private static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024;

    private final PostService postService;
    private final AnimalReportRepository reportRepository;

    @Transactional
    public AnimalReportResponse create(CreateAnimalReportRequest request, MultipartFile[] photos, Long authorId) {
        validate(request, photos);
        PostResponse post = postService.createPost(
                new CreatePostRequest(title(request), request.description().trim(), boardType(request.kind())),
                photos, authorId);
        AnimalReport detail = reportRepository.save(new AnimalReport(
                post.postId(), request.kind(), request.occurredOn(), optional(request.approximateTime()),
                request.region().trim(), optional(request.landmark()), request.species().trim(),
                optional(request.animalName()), optional(request.coatColor()), optional(request.animalSize()),
                optional(request.distinguishingFeatures()), optional(request.direction())));
        return new AnimalReportResponse(post, detail, false);
    }

    @Transactional
    public AnimalReportResponse update(Long postId, CreateAnimalReportRequest request,
                                       MultipartFile[] photos, Long authorId) {
        validate(request, photos);
        if (photos != null && photos.length > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "등록 후 사진 교체는 아직 지원하지 않습니다.");
        }
        AnimalReport detail = reportRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (detail.getKind() != request.kind()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제보 종류는 변경할 수 없습니다.");
        }
        PostResponse post = postService.updatePost(postId,
                new UpdatePostRequest(title(request), request.description().trim()), null, authorId);
        detail.update(request.kind(), request.occurredOn(), optional(request.approximateTime()),
                request.region().trim(), optional(request.landmark()), request.species().trim(),
                optional(request.animalName()), optional(request.coatColor()), optional(request.animalSize()),
                optional(request.distinguishingFeatures()), optional(request.direction()));
        return new AnimalReportResponse(post, detail, false);
    }

    @Transactional(readOnly = true)
    public AnimalReportResponse get(Long postId) {
        PostResponse post = postService.getPost(postId);
        requireReportBoard(post);
        AnimalReport detail = reportRepository.findById(postId).orElse(null);
        return new AnimalReportResponse(post, detail, detail == null);
    }

    @Transactional(readOnly = true)
    public Page<AnimalReportResponse> list(Pageable pageable) {
        Page<PostResponse> posts = postService.getPostsByBoardTypes(List.of(BoardType.MISSING, BoardType.REPORT), pageable);
        Map<Long, AnimalReport> details = reportRepository.findAllById(
                posts.getContent().stream().map(PostResponse::postId).toList()).stream()
                .collect(Collectors.toMap(AnimalReport::getPostId, Function.identity()));
        return posts.map(post -> new AnimalReportResponse(
                post, details.get(post.postId()), !details.containsKey(post.postId())));
    }

    private static void requireReportBoard(PostResponse post) {
        if (post.boardType() != BoardType.MISSING && post.boardType() != BoardType.REPORT) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static BoardType boardType(AnimalReport.Kind kind) {
        return kind == AnimalReport.Kind.MISSING ? BoardType.MISSING : BoardType.REPORT;
    }

    private static String title(CreateAnimalReportRequest request) {
        String prefix = request.kind() == AnimalReport.Kind.MISSING ? "실종" : "목격 제보";
        return prefix + " · " + request.region().trim() + " · " + request.species().trim();
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
        if (title(request).length() > 200 || tooLong(request.approximateTime(), 40)
                || tooLong(request.landmark(), 200) || tooLong(request.animalName(), 80)
                || tooLong(request.coatColor(), 100) || tooLong(request.animalSize(), 40)
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
