package com.pawbridge.animalservice.batch.listener;

import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.entity.Animal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.SkipListener;
import org.springframework.stereotype.Component;

/**
 * Batch Skip 발생 시 로그를 남기는 Listener
 * - faultTolerant() skipLimit 범위 내에서 skip 발생 시 자동 호출
 * - Read/Process/Write 단계별 skip 항목 추적용
 */
@Slf4j
@Component
public class BatchSkipListener implements SkipListener<ApmsAnimal, Animal> {

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("[BATCH SKIP] Read 단계 스킵 발생: {}", t.getMessage(), t);
    }

    @Override
    public void onSkipInProcess(ApmsAnimal item, Throwable t) {
        log.warn("[BATCH SKIP] Process 단계 스킵 발생: desertionNo={}, 원인={}",
                item.getDesertionNo(), t.getMessage(), t);
    }

    @Override
    public void onSkipInWrite(Animal item, Throwable t) {
        log.warn("[BATCH SKIP] Write 단계 스킵 발생: animalId={}, 원인={}",
                item.getId(), t.getMessage(), t);
    }
}
