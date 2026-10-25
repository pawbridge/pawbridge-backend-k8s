package com.pawbridge.backend.animal.internal.apms.infrastructure.reader

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.batch.core.StepExecution
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ApmsItemReaderTest {
    @Test
    fun givenSharedChunkThreads__whenReading__thenEachSnapshotAnimalIsReturnedOnce() {
        val snapshot = mock(ApmsAnimalSnapshot::class.java)
        val animals = (0 until 2000).map { i -> ApmsAnimal.builder().desertionNo("id-$i").build() }
        `when`(snapshot.animals()).thenReturn(animals)
        val reader = ApmsItemReader(snapshot)
        reader.beforeStep(mock(StepExecution::class.java))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val calls: List<Callable<ApmsAnimal?>> = (0 until 2000).map { Callable<ApmsAnimal?> { reader.read() } }
            val actual = ArrayList<ApmsAnimal?>()
            for (future in pool.invokeAll(calls)) {
                actual.add(future.get())
            }
            assertThat(actual).containsExactlyInAnyOrderElementsOf(animals)
            assertThat(reader.read()).isNull()
            verify(snapshot, times(1)).animals()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun givenFailedStepFollowedByRetry__whenReaderReinitialized__thenStartSnapshotFromBeginning() {
        val snapshot = mock(ApmsAnimalSnapshot::class.java)
        val one = ApmsAnimal.builder().desertionNo("one").build()
        val two = ApmsAnimal.builder().desertionNo("two").build()
        `when`(snapshot.animals()).thenReturn(listOf(one, two))
        val reader = ApmsItemReader(snapshot)
        reader.beforeStep(mock(StepExecution::class.java))
        assertThat(reader.read()).isSameAs(one)
        reader.afterStep(mock(StepExecution::class.java))
        assertThat(reader.read()).isNull()
        reader.beforeStep(mock(StepExecution::class.java))
        assertThat(reader.read()).isSameAs(one)
        assertThat(reader.read()).isSameAs(two)
        assertThat(reader.read()).isNull()
    }
}
