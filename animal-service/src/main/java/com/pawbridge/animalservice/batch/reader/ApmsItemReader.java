package com.pawbridge.animalservice.batch.reader;

import com.pawbridge.animalservice.batch.ApmsAnimalSnapshot;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;

import lombok.RequiredArgsConstructor;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Iterator;

@Component
@RequiredArgsConstructor
public class ApmsItemReader implements ItemReader<ApmsAnimal>, StepExecutionListener {
    private final ApmsAnimalSnapshot snapshot;
    private Iterator<ApmsAnimal> items = Collections.emptyIterator();

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
