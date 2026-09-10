package com.pawbridge.animalservice.batch.reader;

import com.pawbridge.animalservice.batch.ApmsAnimalSnapshot;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApmsItemReaderTest {
    @Test
    void givenSharedChunkThreads__whenReading__thenEachSnapshotAnimalIsReturnedOnce() throws Exception {
        var snapshot = mock(ApmsAnimalSnapshot.class);
        var animals = IntStream.range(0, 2000).mapToObj(i -> ApmsAnimal.builder().desertionNo("id-" + i).build()).toList();
        when(snapshot.animals()).thenReturn(animals);
        var reader = new ApmsItemReader(snapshot);
        reader.beforeStep(null);
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<ApmsAnimal>> calls = IntStream.range(0, 2000).mapToObj(i -> (Callable<ApmsAnimal>) reader::read).toList();
            var actual = new java.util.ArrayList<ApmsAnimal>();
            for (var future : pool.invokeAll(calls)) actual.add(future.get());
            assertThat(actual).containsExactlyInAnyOrderElementsOf(animals);
            assertThat(reader.read()).isNull();
            verify(snapshot, times(1)).animals();
        } finally { pool.shutdownNow(); }
    }

    @Test
    void givenFailedStepFollowedByRetry__whenReaderReinitialized__thenStartSnapshotFromBeginning() {
        var snapshot = mock(ApmsAnimalSnapshot.class);
        var one = ApmsAnimal.builder().desertionNo("one").build();
        var two = ApmsAnimal.builder().desertionNo("two").build();
        when(snapshot.animals()).thenReturn(List.of(one, two));
        var reader = new ApmsItemReader(snapshot);
        reader.beforeStep(null);
        assertThat(reader.read()).isSameAs(one);
        reader.afterStep(null);
        assertThat(reader.read()).isNull();
        reader.beforeStep(null);
        assertThat(reader.read()).isSameAs(one);
        assertThat(reader.read()).isSameAs(two);
        assertThat(reader.read()).isNull();
    }
}
