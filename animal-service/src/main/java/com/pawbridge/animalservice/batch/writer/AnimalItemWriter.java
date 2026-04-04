package com.pawbridge.animalservice.batch.writer;

import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.repository.AnimalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Animal Entity를 DB에 저장하는 Writer
 * - id==null (신규): saveAll() → IDENTITY persist → INSERT 직행 (SELECT 없음)
 * - id!=null (기존): @Modifying JPQL UPDATE → SELECT 없이 UPDATE만 실행
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
            StopWatch stopWatch = new StopWatch("APMS Writer 측정");
            stopWatch.start("2. MySQL Bulk Save");

            List<Animal> newAnimals = chunk.getItems().stream()
                    .filter(a -> a.getId() == null)
                    .collect(Collectors.toList());

            List<Animal> existingAnimals = chunk.getItems().stream()
                    .filter(a -> a.getId() != null)
                    .collect(Collectors.toList());

            if (!newAnimals.isEmpty()) {
                animalRepository.saveAll(newAnimals);
            }

            for (Animal existing : existingAnimals) {
                animalRepository.updateAnimalFromApms(
                        existing.getId(),
                        existing.getShelter(),
                        existing.getBreed(),
                        existing.getBirthYear(),
                        existing.getWeight(),
                        existing.getColor(),
                        existing.getGender(),
                        existing.getNeuterStatus(),
                        existing.getSpecialMark(),
                        existing.getApmsProcessState(),
                        existing.getNoticeStartDate(),
                        existing.getNoticeEndDate(),
                        existing.getApmsUpdatedAt(),
                        existing.getHappenDate(),
                        existing.getHappenPlace(),
                        existing.getImageUrl(),
                        existing.getImageUrl2(),
                        existing.getStatus()
                );
            }

            stopWatch.stop();
            log.info("Animal {} 건 처리 완료 (신규 INSERT: {}, 기존 UPDATE: {}). [성능측정] DB 소요시간 - {} ms",
                    chunk.size(), newAnimals.size(), existingAnimals.size(), stopWatch.getTotalTimeMillis());

        } catch (Exception e) {
            log.error("Animal 저장 중 오류 발생: chunk size={}", chunk.size(), e);
            throw e;
        }
    }
}
