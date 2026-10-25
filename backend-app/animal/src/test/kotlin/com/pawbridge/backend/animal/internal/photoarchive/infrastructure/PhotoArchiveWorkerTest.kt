package com.pawbridge.backend.animal.internal.photoarchive.infrastructure

import com.pawbridge.backend.animal.anyNonNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class PhotoArchiveWorkerTest {
    private val store: PhotoArchiveStore = mock(PhotoArchiveStore::class.java)
    private val http: PhotoArchiveHttpClient = mock(PhotoArchiveHttpClient::class.java)
    private val storage: PhotoArchiveObjectStorage = mock(PhotoArchiveObjectStorage::class.java)
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

    private fun pending() {
        `when`(store.claim(eq(300), anyBoolean())).thenReturn(Optional.of(claim), Optional.empty())
        `when`(http.download(claim.sourceUrl)).thenReturn(raw)
        `when`(http.optimize(raw)).thenReturn(photo)
    }

    @Test
    fun verified_object_is_completed_only_after_readback() {
        pending()
        `when`(store.complete(claim, photo, properties.recheckSeconds)).thenReturn(true)

        assertThat(worker.runOnce().completed).isEqualTo(1)

        val order = inOrder(http, storage, store)
        order.verify(http).download(claim.sourceUrl)
        order.verify(http).optimize(raw)
        order.verify(storage).saveAndVerify(photo)
        order.verify(store).complete(claim, photo, properties.recheckSeconds)
        verify(store, never()).retry(anyNonNull<PhotoArchiveStore.Claim>(), anyString(), anyInt())
    }

    @Test
    fun failed_storage_leaves_retry_and_never_completes() {
        pending()
        doThrow(PhotoArchiveFailure("STORAGE_INTEGRITY", true)).`when`(storage).saveAndVerify(photo)

        assertThat(worker.runOnce().failed).isEqualTo(1)

        verify(store).retry(claim, "STORAGE_INTEGRITY", 86400)
        verify(store, never()).complete(
            anyNonNull<PhotoArchiveStore.Claim>(),
            anyNonNull<ArchivedPhoto>(),
            anyInt(),
        )
    }

    @Test
    fun wrapped_integrity_error_keeps_its_retry_classification() {
        pending()
        doThrow(IllegalStateException(PhotoArchiveFailure("STORAGE_INTEGRITY", true))).`when`(storage).saveAndVerify(photo)

        worker.runOnce()

        verify(store).retry(claim, "STORAGE_INTEGRITY", 86400)
    }

    @Test
    fun source_failure_does_not_stop_the_next_photo() {
        val second = PhotoArchiveStore.Claim(2, 1, 1, "two", "http://openapi.animal.go.kr/second", 0)
        `when`(store.claim(eq(300), anyBoolean()))
            .thenReturn(Optional.of(claim), Optional.of(second), Optional.empty())
        `when`(http.download(claim.sourceUrl)).thenThrow(PhotoArchiveFailure("SOURCE_HTTP_404", true))
        `when`(http.download(second.sourceUrl)).thenReturn(raw)
        `when`(http.optimize(raw)).thenReturn(photo)
        `when`(store.complete(second, photo, properties.recheckSeconds)).thenReturn(true)

        val result = worker.runOnce()

        assertThat(result.failed).isEqualTo(1)
        assertThat(result.completed).isEqualTo(1)
        verify(store).retry(claim, "SOURCE_HTTP_404", 86400)
    }

    @Test
    fun failed_db_completion_after_upload_is_retryable() {
        pending()
        `when`(store.complete(claim, photo, properties.recheckSeconds)).thenThrow(IllegalStateException())

        assertThat(worker.runOnce().failed).isEqualTo(1)

        verify(storage).saveAndVerify(photo)
        verify(store).retry(claim, "ARCHIVE_IO", 60)
    }

    @Test
    fun database_outage_leaves_lease_and_propagates_instead_of_reporting_success() {
        pending()
        doThrow(IllegalStateException()).`when`(storage).saveAndVerify(photo)
        `when`(store.retry(claim, "ARCHIVE_IO", 60)).thenThrow(IllegalStateException("database unavailable"))

        assertThatThrownBy { worker.runOnce() }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun old_completion_is_not_counted_as_success() {
        pending()

        val result = worker.runOnce()

        assertThat(result.completed).isZero()
        assertThat(result.superseded).isEqualTo(1)
        verify(store).retry(claim, "SOURCE_SUPERSEDED", 60)
    }

    @Test
    fun run_respects_photo_budget() {
        properties.maxPhotos = 1
        pending()

        worker.runOnce()

        verify(store, times(1)).claim(eq(300), anyBoolean())
    }

    @Test
    fun one_photo_budget_still_alternates_recent_and_old_across_runs() {
        properties.maxPhotos = 1
        `when`(store.claim(eq(300), anyBoolean())).thenReturn(Optional.of(claim))
        `when`(http.download(claim.sourceUrl)).thenReturn(raw)
        `when`(http.optimize(raw)).thenReturn(photo)
        `when`(store.complete(claim, photo, properties.recheckSeconds)).thenReturn(true)

        worker.runOnce()
        worker.runOnce()

        val order = inOrder(store)
        order.verify(store).claim(300, true)
        order.verify(store).claim(300, false)
    }

    @Test
    fun retry_delay_grows_but_is_capped() {
        assertThat(PhotoArchiveWorker.retryDelay(0)).isEqualTo(60)
        assertThat(PhotoArchiveWorker.retryDelay(1)).isEqualTo(120)
        assertThat(PhotoArchiveWorker.retryDelay(1000)).isEqualTo(3600)
    }

    @Test
    fun givenStopBeforeRun__whenInvoked__thenSkipAllWorkUntilIdleResume() {
        worker.requestStop()
        worker.requestStop()

        assertThat(worker.runOnce()).isEqualTo(PhotoArchiveWorker.RunResult(0, 0, 0, 0))

        verifyNoInteractions(store, http, storage)
        worker.resume()
        `when`(store.claim(eq(300), anyBoolean())).thenReturn(Optional.empty())
        assertThat(worker.runOnce()).isEqualTo(PhotoArchiveWorker.RunResult(0, 0, 0, 0))
        verify(store).discover(properties.scanSize)
        verify(store).claim(300, true)
        verifyNoInteractions(http, storage)
    }

    @Test
    @Timeout(15)
    fun givenStopDuringOnePhoto__whenReleased__thenCompleteItAndRejectActiveResumeAndFurtherClaims() {
        pending()
        `when`(store.complete(claim, photo, properties.recheckSeconds)).thenReturn(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(http.download(claim.sourceUrl)).thenAnswer {
            entered.countDown()
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw IllegalStateException("Synthetic photo release timed out")
            }
            raw
        }
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
        verify(store, times(1)).claim(eq(300), anyBoolean())
        verify(store).complete(claim, photo, properties.recheckSeconds)
        clearInvocations(store, http, storage)
        assertThat(worker.runOnce()).isEqualTo(PhotoArchiveWorker.RunResult(0, 0, 0, 0))
        verifyNoInteractions(store, http, storage)
        worker.resume()
    }

    @Test
    fun givenStopDuringAdmittedPhotoFailure__whenRunFinishes__thenPersistOriginalRetryAndClaimNothingElse() {
        pending()
        `when`(http.download(claim.sourceUrl)).thenAnswer {
            worker.requestStop()
            throw PhotoArchiveFailure("SOURCE_HTTP_404", true)
        }

        val result = worker.runOnce()

        assertThat(result.failed).isEqualTo(1)
        assertThat(result.completed).isZero()
        verify(store).retry(claim, "SOURCE_HTTP_404", 86400)
        verify(store, times(1)).claim(eq(300), anyBoolean())
        verify(store, never()).complete(
            anyNonNull<PhotoArchiveStore.Claim>(),
            anyNonNull<ArchivedPhoto>(),
            anyInt(),
        )
        verifyNoInteractions(storage)
    }

    @Test
    fun givenStopDuringDiscovery__whenScanReturns__thenKeepCountAndStartNoClaim() {
        `when`(store.discover(properties.scanSize)).thenAnswer {
            worker.requestStop()
            2
        }

        assertThat(worker.runOnce()).isEqualTo(PhotoArchiveWorker.RunResult(2, 0, 0, 0))

        verify(store, never()).claim(anyInt(), anyBoolean())
        verifyNoInteractions(http, storage)
    }
}
