package com.pawbridge.animalservice.batch.reader;

import com.pawbridge.animalservice.batch.ApmsPage;
import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ApmsItemReader implements ItemReader<ApmsAnimal>, StepExecutionListener {
    private final ApmsApiClient apmsApiClient;

    @Value("${apms.api.service-key}")
    private String serviceKey;
    private static final int PAGE_SIZE = 1000;

    private int currentPage = 1;
    private List<ApmsAnimal> currentItems = List.of();
    private int currentIndex;
    private boolean lastPage;
    private String beginDate;
    private String endDate;

    // The existing chunk executor shares this reader across threads.
    @Override
    public synchronized ApmsAnimal read() {
        if (currentIndex >= currentItems.size()) {
            if (lastPage) {
                return null;
            }
            var page = ApmsPage.fetch(apmsApiClient, serviceKey, currentPage, PAGE_SIZE, beginDate, endDate);
            currentItems = page.items();
            lastPage = page.last();
            currentIndex = 0;
            currentPage++;
        }
        return currentItems.isEmpty() ? null : currentItems.get(currentIndex++);
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        currentPage = 1;
        currentItems = List.of();
        currentIndex = 0;
        lastPage = false;
        LocalDate today = LocalDate.now();
        beginDate = today.minusDays(30).format(DateTimeFormatter.BASIC_ISO_DATE);
        endDate = today.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
