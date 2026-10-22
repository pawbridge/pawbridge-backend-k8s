package com.pawbridge.backend.animal.internal.image.infrastructure;

import com.pawbridge.backend.animal.api.AnimalCommandActors;
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Original image routes behind the former Gateway checks. Single upload and delete matched its non-USER
 * path rule; the two-segment multiple upload matched no role rule and stays open to any signed member.
 */
public final class AnimalImageService {
    // The original service rejected multipart requests above 10MB before any controller code ran.
    private static final long ORIGINAL_REQUEST_LIMIT_BYTES = 10L * 1024 * 1024;

    private final S3AnimalImages images;
    private final AnimalCommandActors actors;
    private final TransactionTemplate writes;

    public AnimalImageService(S3AnimalImages images, AnimalCommandActors actors, TransactionTemplate writes) {
        this.images = Objects.requireNonNull(images);
        this.actors = Objects.requireNonNull(actors);
        this.writes = Objects.requireNonNull(writes);
    }

    public String uploadImage(Long verifiedUserId, MultipartFile file) {
        requireNonUser(verifiedUserId);
        requireOriginalRequestLimit(file == null ? 0 : file.getSize());
        return images.uploadImage(file);
    }

    public List<String> uploadImages(MultipartFile[] files) {
        long total = 0;
        if (files != null) for (MultipartFile file : files) total += file == null ? 0 : file.getSize();
        requireOriginalRequestLimit(total);

        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("업로드할 파일이 없습니다.");
        }

        if (files.length > 5) {
            throw new IllegalArgumentException("최대 5개의 이미지만 업로드할 수 있습니다.");
        }

        List<String> imageUrls = new ArrayList<>();
        for (MultipartFile file : files) {
            imageUrls.add(images.uploadImage(file));
        }
        return imageUrls;
    }

    public void deleteImage(Long verifiedUserId, String imageUrl) {
        requireNonUser(verifiedUserId);
        images.deleteImage(imageUrl);
    }

    private void requireNonUser(Long verifiedUserId) {
        if (verifiedUserId == null) throw new AnimalImageForbiddenException();
        // Commit the current-account lookup before storage I/O so the member row lock is not held.
        Actor actor = writes.execute(status -> actors.find(verifiedUserId));
        if (actor == null || actor.userId() != verifiedUserId || !actor.active() || actor.deleted()
                || !("ROLE_ADMIN".equals(actor.role()) || "ROLE_SHELTER".equals(actor.role())))
            throw new AnimalImageForbiddenException();
    }

    private static void requireOriginalRequestLimit(long bytes) {
        if (bytes > ORIGINAL_REQUEST_LIMIT_BYTES) throw new AnimalImageTooLargeException();
    }
}
