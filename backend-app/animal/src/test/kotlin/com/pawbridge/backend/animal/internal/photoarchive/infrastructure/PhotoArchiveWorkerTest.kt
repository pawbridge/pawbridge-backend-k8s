package com.pawbridge.backend.animal.internal.photoarchive.infrastructure

import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class PhotoArchiveWorkerTest {
    private val store: PhotoArchiveStore = mockk<PhotoArchiveStore>()
    private val http: PhotoArchiveHttpClient = mockk<PhotoArchiveHttpClient>()
    private val storage: PhotoArchiveObjectStorage = mockk<PhotoArchiveObjectStorage>()
    private val properties = PhotoArchiveProperties()
    private val worker = PhotoArchiveWorker(store, http, storage, properties)
    private val claim = PhotoArchiveStore.Claim(1, 1, 1, "token", "http://openapi.animal.go.kr/photo", 0)
    private val raw = byteArrayOf(1, 2, 3)
    private val photo = ArchivedPhoto(
        raw,
        ArchivedPhoto.sha256(raw),
        ArchivedPhoto.sha256(raw),
        "image/png",
        1,
        1,
        "original-v1",
    )

    @Test
    fun `대기 중인 사진이 있을 때__한 번 실행하면__읽기 검증 뒤에만 완료 처리한다`() {
        // given
        every { store.discover(any()) } returns 0
        every { store.claim(any(), any()) } returnsMany listOf(Optional.of(claim), Optional.empty())
        every { http.download(any()) } returns raw
        every { http.optimize(any()) } returns photo
        justRun { storage.saveAndVerify(any()) }
        every { store.complete(any(), any(), any()) } returns true

        // when
        val result = worker.runOnce()

        // then
        assertThat(result.completed).isEqualTo(1)
        verifyOrder {
            http.download(claim.sourceUrl)
            http.optimize(raw)
            storage.saveAndVerify(photo)
            store.complete(claim, photo, properties.recheckSeconds)
        }
        verify(exactly = 2) { store.claim(300, any()) }
        verify(exactly = 0) { store.retry(any(), any(), any()) }
    }

    @Test
    fun `저장소 검증이 실패할 때__한 번 실행하면__재시도로 남기고 완료 처리하지 않는다`() {
        // given
        every { store.discover(any()) } returns 0
        every { store.claim(any(), any()) } returnsMany listOf(Optional.of(claim), Optional.empty())
        every { http.download(any()) } returns raw
        every { http.optimize(any()) } returns photo
        every { storage.saveAndVerify(any()) } throws PhotoArchiveFailure("STORAGE_INTEGRITY", true)
        every { store.retry(any(), any(), any()) } returns true

        // when
        val result = worker.runOnce()

        // then
        assertThat(result.failed).isEqualTo(1)
        verify(exactly = 1) { storage.saveAndVerify(photo) }
        verify(exactly = 1) { store.retry(claim, "STORAGE_INTEGRITY", 86400) }
        verify(exactly = 0) { store.complete(any(), any(), any()) }
    }

    @Test
    fun `무결성 오류가 다른 예외에 감싸여 있을 때__한 번 실행하면__재시도 분류를 그대로 유지한다`() {
        // given
        every { store.discover(any()) } returns 0
        every { store.claim(any(), any()) } returnsMany listOf(Optional.of(claim), Optional.empty())
        every { http.download(any()) } returns raw
        every { http.optimize(any()) } returns photo
        every { storage.saveAndVerify(any()) } throws IllegalStateException(PhotoArchiveFailure("STORAGE_INTEGRITY", true))
        every { store.retry(any(), any(), any()) } returns true

        // when
        worker.runOnce()

        // then
        verify(exactly = 1) { storage.saveAndVerify(photo) }
        verify(exactly = 1) { store.retry(claim, "STORAGE_INTEGRITY", 86400) }
    }

    @Test
    fun `첫 사진의 원본 내려받기가 실패할 때__한 번 실행하면__다음 사진은 계속 처리한다`() {
        // given
        val second = PhotoArchiveStore.Claim(2, 1, 1, "two", "http://openapi.animal.go.kr/second", 0)

        every { store.discover(any()) } returns 0
        every { store.claim(any(), any()) } returnsMany listOf(
            Optional.of(claim),
            Optional.of(second),
            Optional.empty(),
        )
        every { http.download(claim.sourceUrl) } throws PhotoArchiveFailure("SOURCE_HTTP_404", true)
        every { http.download(second.sourceUrl) } returns raw
        every { http.optimize(any()) } returns photo
        justRun { storage.saveAndVerify(any()) }
        every { store.complete(any(), any(), any()) } returns true
        every { store.retry(any(), any(), any()) } returns true

        // when
        val result = worker.runOnce()

        // then
        assertThat(result.failed).isEqualTo(1)
        assertThat(result.completed).isEqualTo(1)
        verify(exactly = 1) { store.complete(second, photo, properties.recheckSeconds) }
        verify(exactly = 1) { store.retry(claim, "SOURCE_HTTP_404", 86400) }
    }

    @Test
    fun `업로드 뒤 데이터베이스 완료 처리가 실패할 때__한 번 실행하면__재시도 가능한 실패로 남긴다`() {
        // given
        every { store.discover(any()) } returns 0
        every { store.claim(any(), any()) } returnsMany listOf(Optional.of(claim), Optional.empty())
        every { http.download(any()) } returns raw
        every { http.optimize(any()) } returns photo
        justRun { storage.saveAndVerify(any()) }
        every { store.complete(any(), any(), any()) } throws IllegalStateException()
        every { store.retry(any(), any(), any()) } returns true

        // when
        val result = worker.runOnce()

        // then
        assertThat(result.failed).isEqualTo(1)
        verify(exactly = 1) { storage.saveAndVerify(photo) }
        verify(exactly = 1) { store.complete(claim, photo, properties.recheckSeconds) }
        verify(exactly = 1) { store.retry(claim, "ARCHIVE_IO", 60) }
    }

    @Test
    fun `재시도 기록마저 데이터베이스 장애로 실패할 때__한 번 실행하면__성공으로 보고하지 않고 예외를 전파한다`() {
        // given
        every { store.discover(any()) } returns 0
        every { store.claim(any(), any()) } returnsMany listOf(Optional.of(claim), Optional.empty())
        every { http.download(any()) } returns raw
        every { http.optimize(any()) } returns photo
        every { storage.saveAndVerify(any()) } throws IllegalStateException()
        every { store.retry(any(), any(), any()) } throws IllegalStateException("database unavailable")

        // when
        val thrown = assertThatThrownBy { worker.runOnce() }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 1) { store.retry(claim, "ARCHIVE_IO", 60) }
    }

    @Test
    fun `완료 처리가 거절될 때__한 번 실행하면__옛 완료를 성공으로 세지 않고 대체됨으로 센다`() {
        // given
        every { store.discover(any()) } returns 0
        every { store.claim(any(), any()) } returnsMany listOf(Optional.of(claim), Optional.empty())
        every { http.download(any()) } returns raw
        every { http.optimize(any()) } returns photo
        justRun { storage.saveAndVerify(any()) }
        every { store.complete(any(), any(), any()) } returns false
        every { store.retry(any(), any(), any()) } returns true

        // when
        val result = worker.runOnce()

        // then
        assertThat(result.completed).isZero()
        assertThat(result.superseded).isEqualTo(1)
        verify(exactly = 1) { store.retry(claim, "SOURCE_SUPERSEDED", 60) }
    }

    @Test
    fun `사진 한도가 1장일 때__한 번 실행하면__한 장만 가져온다`() {
        // given
        properties.maxPhotos = 1

        every { store.discover(any()) } returns 0
        every { store.claim(any(), any()) } returnsMany listOf(Optional.of(claim), Optional.empty())
        every { http.download(any()) } returns raw
        every { http.optimize(any()) } returns photo
        justRun { storage.saveAndVerify(any()) }
        every { store.complete(any(), any(), any()) } returns false
        every { store.retry(any(), any(), any()) } returns true

        // when
        worker.runOnce()

        // then
        verify(exactly = 1) { store.claim(300, any()) }
    }

    @Test
    fun `사진 한도가 1장일 때__여러 번 실행하면__실행마다 최근 것과 오래된 것을 번갈아 가져온다`() {
        // given
        properties.maxPhotos = 1

        every { store.discover(any()) } returns 0
        every { store.claim(any(), any()) } returns Optional.of(claim)
        every { http.download(any()) } returns raw
        every { http.optimize(any()) } returns photo
        justRun { storage.saveAndVerify(any()) }
        every { store.complete(any(), any(), any()) } returns true

        // when
        worker.runOnce()
        worker.runOnce()

        // then
        verifyOrder {
            store.claim(300, true)
            store.claim(300, false)
        }
        verify(exactly = 2) { store.claim(300, any()) }
        verify(exactly = 2) { store.complete(claim, photo, properties.recheckSeconds) }
    }

    @Test
    fun `시도 횟수가 늘어날 때__재시도 지연을 계산하면__늘어나다가 상한에서 멈춘다`() {
        // given
        // when
        // then
        assertThat(PhotoArchiveWorker.retryDelay(0)).isEqualTo(60)
        assertThat(PhotoArchiveWorker.retryDelay(1)).isEqualTo(120)
        assertThat(PhotoArchiveWorker.retryDelay(1000)).isEqualTo(3600)
    }

    @Test
    fun `실행 전에 중지가 요청됐을 때__실행을 호출하면__작업 없이 건너뛰고 유휴 상태에서 재개한다`() {
        // given
        every { store.discover(any()) } returns 0
        every { store.claim(any(), any()) } returns Optional.empty()

        // when
        worker.requestStop()
        worker.requestStop()
        val stopped = worker.runOnce()

        // then
        assertThat(stopped).isEqualTo(PhotoArchiveWorker.RunResult(0, 0, 0, 0))
        confirmVerified(store, http, storage)

        // when
        worker.resume()
        val resumed = worker.runOnce()

        // then
        assertThat(resumed).isEqualTo(PhotoArchiveWorker.RunResult(0, 0, 0, 0))
        verify(exactly = 1) { store.discover(properties.scanSize) }
        verify(exactly = 1) { store.claim(300, true) }
        confirmVerified(http, storage)
    }

    @Test
    @Timeout(15)
    fun `사진 한 장을 처리하는 중에 중지가 요청됐을 때__해제하면__그 사진은 끝내고 실행 중 재개와 이후 가져오기는 거부한다`() {
        // given
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        every { store.discover(any()) } returns 0
        every { store.claim(any(), any()) } returnsMany listOf(Optional.of(claim), Optional.empty())
        every { http.download(any()) } answers {
            entered.countDown()
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw IllegalStateException("Synthetic photo release timed out")
            }
            raw
        }
        every { http.optimize(any()) } returns photo
        justRun { storage.saveAndVerify(any()) }
        every { store.complete(any(), any(), any()) } returns true

        // when
        val execution = FutureTask(Callable { worker.runOnce() })
        val thread = Thread(execution, "photo-archive-owned-unit-worker")
        thread.isDaemon = true
        thread.start()
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            worker.requestStop()
            assertThatThrownBy { worker.resume() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("PHOTO_ARCHIVE_RUNNING")
            assertThat(worker.runOnce()).isEqualTo(PhotoArchiveWorker.RunResult(0, 0, 0, 0))
            release.countDown()
            assertThat(execution.get(5, TimeUnit.SECONDS).completed).isEqualTo(1)
        } finally {
            release.countDown()
            thread.join(5000)
            assertThat(thread.isAlive).isFalse()
        }

        // then
        verify(exactly = 1) { store.claim(300, any()) }
        verify(exactly = 1) { http.download(claim.sourceUrl) }
        verify(exactly = 1) { store.complete(claim, photo, properties.recheckSeconds) }
        clearMocks(store, http, storage, answers = false)
        assertThat(worker.runOnce()).isEqualTo(PhotoArchiveWorker.RunResult(0, 0, 0, 0))
        confirmVerified(store, http, storage)
        worker.resume()
    }

    @Test
    fun `이미 가져온 사진을 처리하다 실패하며 중지가 요청됐을 때__실행이 끝나면__원래 재시도를 기록하고 더는 가져오지 않는다`() {
        // given
        every { store.discover(any()) } returns 0
        every { store.claim(any(), any()) } returnsMany listOf(Optional.of(claim), Optional.empty())
        every { http.download(any()) } answers {
            worker.requestStop()
            throw PhotoArchiveFailure("SOURCE_HTTP_404", true)
        }
        every { store.retry(any(), any(), any()) } returns true

        // when
        val result = worker.runOnce()

        // then
        assertThat(result.failed).isEqualTo(1)
        assertThat(result.completed).isZero()
        verify(exactly = 1) { store.retry(claim, "SOURCE_HTTP_404", 86400) }
        verify(exactly = 1) { store.claim(300, any()) }
        verify(exactly = 0) { store.complete(any(), any(), any()) }
        confirmVerified(storage)
    }

    @Test
    fun `사진 탐색 중에 중지가 요청됐을 때__탐색이 돌아오면__탐색 수는 유지하고 가져오기는 시작하지 않는다`() {
        // given
        every { store.discover(any()) } answers {
            worker.requestStop()
            2
        }

        // when
        val result = worker.runOnce()

        // then
        assertThat(result).isEqualTo(PhotoArchiveWorker.RunResult(2, 0, 0, 0))
        verify(exactly = 0) { store.claim(any(), any()) }
        confirmVerified(http, storage)
    }
}
