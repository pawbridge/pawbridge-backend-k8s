package com.pawbridge.backend.animal.internal.apms.infrastructure.reader;

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot;
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ItemReader;

import java.util.Collections;
import java.util.Iterator;

public class ApmsItemReader implements ItemReader<ApmsAnimal>, StepExecutionListener {
    private final ApmsAnimalSnapshot snapshot;
    private Iterator<ApmsAnimal> items = Collections.emptyIterator();

    public ApmsItemReader(ApmsAnimalSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public synchronized ApmsAnimal read() {
        return items.hasNext() ? items.next() : null;
    }

    @Override
    public void beforeStep(StepExecution execution) {
        // Resolve the job-scoped snapshot on the job thread, never on chunk executor threads.
        items = snapshot.animals().iterator();
    }

    @Override
    public ExitStatus afterStep(StepExecution execution) {
        items = Collections.emptyIterator();
        return null;
    }
}
