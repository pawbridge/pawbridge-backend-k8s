package com.pawbridge.backend.animal.internal.photoarchive.infrastructure;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhotoArchiveWorkerTest {
    private final PhotoArchiveStore store = mock(PhotoArchiveStore.class);
    private final PhotoArchiveHttpClient http = mock(PhotoArchiveHttpClient.class);
    private final PhotoArchiveObjectStorage storage = mock(PhotoArchiveObjectStorage.class);
    private final PhotoArchiveProperties properties = new PhotoArchiveProperties();
    private final PhotoArchiveWorker worker = new PhotoArchiveWorker(store,http,storage,properties);
    private final PhotoArchiveStore.Claim claim = new PhotoArchiveStore.Claim(1,1,1,"token","http://openapi.animal.go.kr/photo",0);
    private final byte[] raw = {1,2,3};
    private final ArchivedPhoto photo = new ArchivedPhoto(raw,ArchivedPhoto.sha256(raw),ArchivedPhoto.sha256(raw),"image/png",1,1,"original-v1");

    private void pending() {
        when(store.claim(eq(300),anyBoolean())).thenReturn(Optional.of(claim),Optional.empty());
        when(http.download(claim.sourceUrl())).thenReturn(raw);
        when(http.optimize(raw)).thenReturn(photo);
    }

    @Test void verified_object_is_completed_only_after_readback() {
        pending(); when(store.complete(claim,photo,properties.getRecheckSeconds())).thenReturn(true);
        assertThat(worker.runOnce().completed()).isEqualTo(1);
        var order=inOrder(http,storage,store);
        order.verify(http).download(claim.sourceUrl()); order.verify(http).optimize(raw);
        order.verify(storage).saveAndVerify(photo); order.verify(store).complete(claim,photo,properties.getRecheckSeconds());
        verify(store,never()).retry(any(),anyString(),anyInt());
    }
    @Test void failed_storage_leaves_retry_and_never_completes() {
        pending(); doThrow(new PhotoArchiveFailure("STORAGE_INTEGRITY",true)).when(storage).saveAndVerify(photo);
        assertThat(worker.runOnce().failed()).isEqualTo(1);
        verify(store).retry(claim,"STORAGE_INTEGRITY",86400);
        verify(store,never()).complete(any(),any(),anyInt());
    }
    @Test void wrapped_integrity_error_keeps_its_retry_classification() {
        pending(); doThrow(new IllegalStateException(new PhotoArchiveFailure("STORAGE_INTEGRITY",true))).when(storage).saveAndVerify(photo);
        worker.runOnce(); verify(store).retry(claim,"STORAGE_INTEGRITY",86400);
    }
    @Test void source_failure_does_not_stop_the_next_photo() {
        var second=new PhotoArchiveStore.Claim(2,1,1,"two","http://openapi.animal.go.kr/second",0);
        when(store.claim(eq(300),anyBoolean())).thenReturn(Optional.of(claim),Optional.of(second),Optional.empty());
        when(http.download(claim.sourceUrl())).thenThrow(new PhotoArchiveFailure("SOURCE_HTTP_404",true));
        when(http.download(second.sourceUrl())).thenReturn(raw); when(http.optimize(raw)).thenReturn(photo);
        when(store.complete(second,photo,properties.getRecheckSeconds())).thenReturn(true);
        var result=worker.runOnce(); assertThat(result.failed()).isEqualTo(1); assertThat(result.completed()).isEqualTo(1);
        verify(store).retry(claim,"SOURCE_HTTP_404",86400);
    }
    @Test void failed_db_completion_after_upload_is_retryable() {
        pending(); when(store.complete(claim,photo,properties.getRecheckSeconds())).thenThrow(new IllegalStateException());
        assertThat(worker.runOnce().failed()).isEqualTo(1);
        verify(storage).saveAndVerify(photo); verify(store).retry(claim,"ARCHIVE_IO",60);
    }
    @Test void database_outage_leaves_lease_and_propagates_instead_of_reporting_success() {
        pending(); doThrow(new IllegalStateException()).when(storage).saveAndVerify(photo);
        when(store.retry(claim,"ARCHIVE_IO",60)).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(worker::runOnce).isInstanceOf(IllegalStateException.class);
    }
    @Test void old_completion_is_not_counted_as_success() {
        pending(); var result=worker.runOnce();
        assertThat(result.completed()).isZero(); assertThat(result.superseded()).isEqualTo(1);
        verify(store).retry(claim,"SOURCE_SUPERSEDED",60);
    }
    @Test void run_respects_photo_budget() {
        properties.setMaxPhotos(1); pending(); worker.runOnce(); verify(store,times(1)).claim(eq(300),anyBoolean());
    }
    @Test void one_photo_budget_still_alternates_recent_and_old_across_runs() {
        properties.setMaxPhotos(1);
        when(store.claim(eq(300),anyBoolean())).thenReturn(Optional.of(claim));
        when(http.download(claim.sourceUrl())).thenReturn(raw); when(http.optimize(raw)).thenReturn(photo);
        when(store.complete(claim,photo,properties.getRecheckSeconds())).thenReturn(true);
        worker.runOnce(); worker.runOnce();
        var order=inOrder(store); order.verify(store).claim(300,true); order.verify(store).claim(300,false);
    }

    @Test void retry_delay_grows_but_is_capped() {
        assertThat(PhotoArchiveWorker.retryDelay(0)).isEqualTo(60);
        assertThat(PhotoArchiveWorker.retryDelay(1)).isEqualTo(120);
        assertThat(PhotoArchiveWorker.retryDelay(1000)).isEqualTo(3600);
    }

    @Test void givenStopBeforeRun__whenInvoked__thenSkipAllWorkUntilIdleResume() {
        worker.requestStop(); worker.requestStop();
        assertThat(worker.runOnce()).isEqualTo(new PhotoArchiveWorker.RunResult(0,0,0,0));
        verifyNoInteractions(store,http,storage);
        worker.resume();
        when(store.claim(eq(300),anyBoolean())).thenReturn(Optional.empty());
        assertThat(worker.runOnce()).isEqualTo(new PhotoArchiveWorker.RunResult(0,0,0,0));
        verify(store).discover(properties.getScanSize()); verify(store).claim(300,true);
        verifyNoInteractions(http,storage);
    }

    @Test @Timeout(15)
    void givenStopDuringOnePhoto__whenReleased__thenCompleteItAndRejectActiveResumeAndFurtherClaims() throws Exception {
        pending(); when(store.complete(claim,photo,properties.getRecheckSeconds())).thenReturn(true);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(http.download(claim.sourceUrl())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic photo release timed out");
            return raw;
        });
        var execution = new FutureTask<>(worker::runOnce);
        var thread = new Thread(execution,"photo-archive-owned-unit-worker"); thread.setDaemon(true); thread.start();
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            worker.requestStop();
            assertThatThrownBy(worker::resume).isInstanceOf(IllegalStateException.class).hasMessage("PHOTO_ARCHIVE_RUNNING");
            assertThat(worker.runOnce()).isEqualTo(new PhotoArchiveWorker.RunResult(0,0,0,0));
            release.countDown();
            assertThat(execution.get(5,TimeUnit.SECONDS).completed()).isEqualTo(1);
        } finally {
            release.countDown(); thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        }
        verify(store,times(1)).claim(eq(300),anyBoolean());
        verify(store).complete(claim,photo,properties.getRecheckSeconds());
        clearInvocations(store,http,storage);
        assertThat(worker.runOnce()).isEqualTo(new PhotoArchiveWorker.RunResult(0,0,0,0));
        verifyNoInteractions(store,http,storage);
        worker.resume();
    }

    @Test void givenStopDuringAdmittedPhotoFailure__whenRunFinishes__thenPersistOriginalRetryAndClaimNothingElse() {
        pending();
        when(http.download(claim.sourceUrl())).thenAnswer(call -> {
            worker.requestStop(); throw new PhotoArchiveFailure("SOURCE_HTTP_404",true);
        });
        var result = worker.runOnce();
        assertThat(result.failed()).isEqualTo(1); assertThat(result.completed()).isZero();
        verify(store).retry(claim,"SOURCE_HTTP_404",86400);
        verify(store,times(1)).claim(eq(300),anyBoolean());
        verify(store,never()).complete(any(),any(),anyInt());
        verifyNoInteractions(storage);
    }

    @Test void givenStopDuringDiscovery__whenScanReturns__thenKeepCountAndStartNoClaim() {
        when(store.discover(properties.getScanSize())).thenAnswer(call -> { worker.requestStop(); return 2; });
        assertThat(worker.runOnce()).isEqualTo(new PhotoArchiveWorker.RunResult(2,0,0,0));
        verify(store,never()).claim(anyInt(),anyBoolean()); verifyNoInteractions(http,storage);
    }
}
