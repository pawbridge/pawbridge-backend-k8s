package com.pawbridge.animalservice.batch.writer;

import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.repository.AnimalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * Animal Entity를 DB에 저장하는 Writer
 * - Chunk 단위로 일괄 저장 (성능 최적화)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnimalItemWriter implements ItemWriter<Animal> {

    private final AnimalRepository animalRepository;

    @Override
    public void write(Chunk<? extends Animal> chunk) throws Exception {
        if (chunk.isEmpty()) {
            log.debug("저장할 Animal이 없습니다.");
            return;
        }

        try {
            org.springframework.util.StopWatch stopWatch = new org.springframework.util.StopWatch("APMS Writer 측정");
            stopWatch.start("2. MySQL Bulk Save");
            
            // Chunk 내의 모든 Animal을 일괄 저장
            animalRepository.saveAll(chunk.getItems());
            
            stopWatch.stop();

            log.info("Animal {} 건 저장 완료. [성능측정] DB 저장 소요시간 - {} ms", chunk.size(), stopWatch.getTotalTimeMillis());

        } catch (Exception e) {
            log.error("Animal 저장 중 오류 발생: chunk size={}", chunk.size(), e);
            throw e;
        }
    }
}
