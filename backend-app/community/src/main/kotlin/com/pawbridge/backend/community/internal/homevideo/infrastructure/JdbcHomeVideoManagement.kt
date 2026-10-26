package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Board
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Metadata
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Order
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Publication
import com.pawbridge.backend.community.internal.homevideo.application.HomeVideoManagement.Save
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.server.ResponseStatusException

class JdbcHomeVideoManagement(
    private val repository: JdbcHomeVideoRepository,
    private val youtube: YouTubeVideoClient,
    private val clock: Clock,
    private val transaction: TransactionOperations,
) : HomeVideoManagement {

    private fun <T> atomic(action: () -> T): T? = transaction.execute { action() }

    private fun expiryCutoff(): Instant = clock.instant().minus(Duration.ofDays(29))

    override fun board(): Board =
        atomic {
            repository.lockBoard()
            snapshot()
        }!!

    private fun snapshot(): Board = Board(repository.revision(), repository.list())

    override fun preview(url: String?): Metadata {
        val videoId = YouTubeVideoUrl.id(url)
        val metadata = youtube.lookup(listOf(videoId))[videoId]
        if (metadata == null) {
            throw bad("공개된 YouTube 영상을 찾지 못했습니다.")
        }
        return metadata
    }

    override fun save(id: UUID?, request: Save): Board {
        // External I/O occurs before acquiring the board lock.
        val metadata = preview(request.url)
        if (request.published && !metadata.available) {
            throw bad("공개·외부 재생이 가능한 영상만 게시할 수 있습니다.")
        }
        return atomic {
            expectRevision(request.revision)
            val videos = repository.list()
            if (id != null) {
                find(videos, id)
            }
            if (videos.any { video -> video.videoId == metadata.videoId && video.id != id }) {
                throw conflict("이미 등록된 영상입니다.")
            }
            if (id == null && videos.size >= 100) {
                throw conflict("영상은 최대 100개까지 등록할 수 있습니다.")
            }
            checkSlots(videos, id, request.published)
            val target = id ?: UUID.randomUUID()
            if (id == null) {
                val position = (videos.maxOfOrNull { video -> video.position } ?: -1) + 1
                repository.insert(target, metadata.videoId, request.published, position, clock.instant())
            } else {
                repository.selection(id, metadata.videoId, request.published)
            }
            repository.metadata(target, metadata, clock.instant())
            repository.changed()
            snapshot()
        }!!
    }

    override fun publish(id: UUID, request: Publication): Board {
        // Hiding never needs the provider to be available.
        val selected = find(repository.list(), id)
        val metadata = if (request.published) {
            youtube.lookup(listOf(selected.videoId))[selected.videoId]
        } else {
            null
        }
        if (request.published && (metadata == null || !metadata.available)) {
            throw bad("재생 가능한 공개 영상인지 확인해 주세요.")
        }
        return atomic {
            expectRevision(request.revision)
            val videos = repository.list()
            val current = find(videos, id)
            if (current.videoId != selected.videoId) {
                throw conflict("영상이 변경되었습니다. 목록을 다시 불러와 주세요.")
            }
            checkSlots(videos, id, request.published)
            repository.publication(id, request.published)
            if (request.published) {
                repository.metadata(id, metadata, clock.instant())
            }
            repository.changed()
            snapshot()
        }!!
    }

    override fun reorder(request: Order): Board =
        atomic {
            expectRevision(request.revision)
            val published = repository.list()
                .filter { video -> video.published }
                .map { video -> video.id }
            val requestedIds = request.ids
            if (requestedIds == null ||
                requestedIds.size != HashSet(requestedIds).size ||
                HashSet(published) != HashSet(requestedIds)
            ) {
                throw conflict("게시 중인 영상 전체의 순서를 보내 주세요.")
            }
            var position = 0
            for (id in requestedIds) {
                repository.position(id, position++)
            }
            // Keep hidden videos behind the published set without tying publication to order.
            for (video in repository.list()) {
                if (!video.published) {
                    repository.position(video.id, position++)
                }
            }
            repository.changed()
            snapshot()
        }!!

    override fun recheck(id: UUID, revision: Long): Board {
        val selected = find(repository.list(), id)
        val metadata = youtube.lookup(listOf(selected.videoId))[selected.videoId]
        return atomic {
            expectRevision(revision)
            if (find(repository.list(), id).videoId != selected.videoId) {
                throw conflict("영상이 변경되었습니다.")
            }
            repository.metadata(id, metadata, clock.instant())
            repository.changed()
            snapshot()
        }!!
    }

    override fun refresh() {
        // Expiry cleanup must still commit if the provider is unavailable.
        transaction.executeWithoutResult {
            repository.lockBoard()
            if (repository.expire(expiryCutoff()) > 0) {
                repository.changed()
            }
        }
        val videos = repository.list().filter { video ->
            video.checkedAt == null || video.checkedAt.isBefore(clock.instant().minus(Duration.ofDays(1)))
        }
        var start = 0
        while (start < videos.size) {
            val batch = videos.subList(start, minOf(start + 50, videos.size))
            val metadata = youtube.lookup(batch.map { video -> video.videoId })
            transaction.executeWithoutResult {
                repository.lockBoard()
                val current = repository.list()
                for (video in batch) {
                    current
                        .firstOrNull { candidate ->
                            candidate.id == video.id &&
                                candidate.videoId == video.videoId &&
                                candidate.checkedAt == video.checkedAt
                        }
                        ?.let { candidate ->
                            repository.metadata(candidate.id, metadata[candidate.videoId], clock.instant())
                        }
                }
                repository.changed()
            }
            start += 50
        }
    }

    private fun expectRevision(expected: Long) {
        if (repository.lockBoard() != expected) {
            throw conflict("다른 관리자가 변경했습니다. 목록을 다시 불러와 주세요.")
        }
    }

    private companion object {
        private fun find(videos: List<HomeVideo>, id: UUID): HomeVideo =
            videos.firstOrNull { video -> video.id == id }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "영상을 찾지 못했습니다.")

        private fun checkSlots(videos: List<HomeVideo>, id: UUID?, publish: Boolean) {
            if (publish && videos.count { video -> video.published && video.id != id } >= 3) {
                throw conflict("홈에는 최대 3개까지 게시할 수 있습니다. 먼저 다른 영상을 숨겨 주세요.")
            }
        }

        private fun bad(message: String): ResponseStatusException =
            ResponseStatusException(HttpStatus.BAD_REQUEST, message)

        private fun conflict(message: String): ResponseStatusException =
            ResponseStatusException(HttpStatus.CONFLICT, message)
    }
}
