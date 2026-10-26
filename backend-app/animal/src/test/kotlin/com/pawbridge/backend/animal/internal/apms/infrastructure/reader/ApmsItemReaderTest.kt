package com.pawbridge.backend.animal.internal.apms.infrastructure.reader

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.StepExecution
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ApmsItemReaderTest {
    @Test
    fun `청크 스레드가 공유할 때__읽으면__스냅샷의 각 동물을 한 번씩만 돌려준다`() {
        // given
        val snapshot = mockk<ApmsAnimalSnapshot>()
        val animals = (0 until 2000).map { i -> ApmsAnimal.builder().desertionNo("id-$i").build() }
        val reader = ApmsItemReader(snapshot)
        val pool = Executors.newFixedThreadPool(4)

        every { snapshot.animals() } returns animals

        try {
            reader.beforeStep(mockk<StepExecution>())
            val calls: List<Callable<ApmsAnimal?>> = (0 until 2000).map { Callable<ApmsAnimal?> { reader.read() } }

            // when
            val actual = ArrayList<ApmsAnimal?>()
            for (future in pool.invokeAll(calls)) {
                actual.add(future.get())
            }

            // then
            assertThat(actual).containsExactlyInAnyOrderElementsOf(animals)
            assertThat(reader.read()).isNull()
            verify(exactly = 1) { snapshot.animals() }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `실패한 단계 뒤 재시도할 때__리더를 다시 초기화하면__스냅샷을 처음부터 시작한다`() {
        // given
        val snapshot = mockk<ApmsAnimalSnapshot>()
        val one = ApmsAnimal.builder().desertionNo("one").build()
        val two = ApmsAnimal.builder().desertionNo("two").build()
        val reader = ApmsItemReader(snapshot)

        every { snapshot.animals() } returns listOf(one, two)

        // when
        reader.beforeStep(mockk<StepExecution>())
        val firstRead = reader.read()
        reader.afterStep(mockk<StepExecution>())
        val readAfterStep = reader.read()
        reader.beforeStep(mockk<StepExecution>())
        val restartedFirst = reader.read()
        val restartedSecond = reader.read()
        val restartedEnd = reader.read()

        // then
        assertThat(firstRead).isSameAs(one)
        assertThat(readAfterStep).isNull()
        assertThat(restartedFirst).isSameAs(one)
        assertThat(restartedSecond).isSameAs(two)
        assertThat(restartedEnd).isNull()
    }
}
