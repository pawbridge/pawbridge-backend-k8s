package com.pawbridge.backend.animal.internal.apms.infrastructure.reader

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.StepExecutionListener
import org.springframework.batch.item.ItemReader

open class ApmsItemReader(
    private val snapshot: ApmsAnimalSnapshot,
) : ItemReader<ApmsAnimal>, StepExecutionListener {

    private var items: Iterator<ApmsAnimal> = emptyList<ApmsAnimal>().iterator()

    @Synchronized
    override fun read(): ApmsAnimal? {
        return if (items.hasNext()) items.next() else null
    }

    override fun beforeStep(stepExecution: StepExecution) {
        // Resolve the job-scoped snapshot on the job thread, never on chunk executor threads.
        items = snapshot.animals().iterator()
    }

    override fun afterStep(stepExecution: StepExecution): ExitStatus? {
        items = emptyList<ApmsAnimal>().iterator()
        return null
    }
}
