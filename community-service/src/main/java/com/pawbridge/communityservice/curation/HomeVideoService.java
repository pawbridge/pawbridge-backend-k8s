package com.pawbridge.communityservice.curation;

import static com.pawbridge.communityservice.curation.HomeVideoModels.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HomeVideoService {
    private final HomeVideoRepository repository;
    private final YouTubeVideoClient youtube;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public HomeVideoService(HomeVideoRepository repository, YouTubeVideoClient youtube, Clock clock,
                            PlatformTransactionManager manager) {
        this.repository = repository; this.youtube = youtube; this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }
    private <T> T atomic(Supplier<T> action) { return transaction.execute(status -> action.get()); }
    private Instant expiryCutoff() { return clock.instant().minus(Duration.ofDays(29)); }

    public List<Video> home() { return repository.visible(expiryCutoff()); }
    public Board board() {
        return atomic(() -> { repository.lockBoard(); return snapshot(); });
    }
    private Board snapshot() { return new Board(repository.revision(), repository.list()); }
    public Metadata preview(String url) {
        String videoId = YouTubeVideoUrl.id(url);
        Metadata metadata = youtube.lookup(List.of(videoId)).get(videoId);
        if (metadata == null) throw bad("공개된 YouTube 영상을 찾지 못했습니다.");
        return metadata;
    }
    public Board save(UUID id, Save request) {
        Metadata metadata = preview(request.url()); // External I/O occurs before acquiring the board lock.
        if (request.published() && !metadata.available()) throw bad("공개·외부 재생이 가능한 영상만 게시할 수 있습니다.");
        return atomic(() -> {
            expectRevision(request.revision());
            List<Video> videos = repository.list();
            if (id != null) find(videos, id);
            if (videos.stream().anyMatch(v -> v.videoId().equals(metadata.videoId()) && !v.id().equals(id)))
                throw conflict("이미 등록된 영상입니다.");
            if (id == null && videos.size() >= 100) throw conflict("영상은 최대 100개까지 등록할 수 있습니다.");
            checkSlots(videos, id, request.published());
            UUID target = id == null ? UUID.randomUUID() : id;
            if (id == null) {
                int position = videos.stream().mapToInt(Video::position).max().orElse(-1) + 1;
                repository.insert(target, metadata.videoId(), request.published(), position, clock.instant());
            } else repository.selection(id, metadata.videoId(), request.published());
            repository.metadata(target, metadata, clock.instant()); repository.changed();
            return snapshot();
        });
    }
    public Board publish(UUID id, Publication request) {
        // Hiding never needs the provider to be available.
        Video selected = find(repository.list(), id);
        Metadata metadata = request.published() ? youtube.lookup(List.of(selected.videoId())).get(selected.videoId()) : null;
        if (request.published() && (metadata == null || !metadata.available())) throw bad("재생 가능한 공개 영상인지 확인해 주세요.");
        return atomic(() -> {
            expectRevision(request.revision());
            List<Video> videos = repository.list();
            Video current = find(videos, id);
            if (!current.videoId().equals(selected.videoId())) throw conflict("영상이 변경되었습니다. 목록을 다시 불러와 주세요.");
            checkSlots(videos, id, request.published());
            repository.publication(id, request.published());
            if (request.published()) repository.metadata(id, metadata, clock.instant());
            repository.changed(); return snapshot();
        });
    }
    public Board reorder(Order request) {
        return atomic(() -> {
            expectRevision(request.revision());
            var published = repository.list().stream().filter(Video::published).map(Video::id).toList();
            if (request.ids() == null || request.ids().size() != new HashSet<>(request.ids()).size()
                    || !new HashSet<>(published).equals(new HashSet<>(request.ids())))
                throw conflict("게시 중인 영상 전체의 순서를 보내 주세요.");
            int position = 0;
            for (UUID id : request.ids()) repository.position(id, position++);
            // Keep hidden videos behind the published set without tying publication to order.
            for (Video video : repository.list()) if (!video.published()) repository.position(video.id(), position++);
            repository.changed(); return snapshot();
        });
    }
    public Board recheck(UUID id, long revision) {
        Video selected = find(repository.list(), id);
        Metadata metadata = youtube.lookup(List.of(selected.videoId())).get(selected.videoId());
        return atomic(() -> {
            expectRevision(revision);
            if (!find(repository.list(), id).videoId().equals(selected.videoId())) throw conflict("영상이 변경되었습니다.");
            repository.metadata(id, metadata, clock.instant()); repository.changed(); return snapshot();
        });
    }
    public void refresh() {
        // Expiry cleanup must still commit if the provider is unavailable.
        atomic(() -> { repository.lockBoard(); if (repository.expire(expiryCutoff()) > 0) repository.changed(); return null; });
        List<Video> videos = repository.list().stream()
                .filter(v -> v.checkedAt() == null || v.checkedAt().isBefore(clock.instant().minus(Duration.ofDays(1))))
                .toList();
        for (int start = 0; start < videos.size(); start += 50) {
            var batch = videos.subList(start, Math.min(start + 50, videos.size()));
            var metadata = youtube.lookup(batch.stream().map(Video::videoId).toList());
            atomic(() -> {
                repository.lockBoard();
                var current = repository.list();
                for (Video video : batch) {
                    current.stream().filter(v -> v.id().equals(video.id()) && v.videoId().equals(video.videoId())
                                    && Objects.equals(v.checkedAt(), video.checkedAt()))
                            .findFirst().ifPresent(v -> repository.metadata(v.id(), metadata.get(v.videoId()), clock.instant()));
                }
                repository.changed(); return null;
            });
        }
    }
    private void expectRevision(long expected) {
        if (repository.lockBoard() != expected) throw conflict("다른 관리자가 변경했습니다. 목록을 다시 불러와 주세요.");
    }
    private static Video find(List<Video> videos, UUID id) {
        return videos.stream().filter(v -> v.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "영상을 찾지 못했습니다."));
    }
    private static void checkSlots(List<Video> videos, UUID id, boolean publish) {
        if (publish && videos.stream().filter(v -> v.published() && !v.id().equals(id)).count() >= 3)
            throw conflict("홈에는 최대 3개까지 게시할 수 있습니다. 먼저 다른 영상을 숨겨 주세요.");
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
