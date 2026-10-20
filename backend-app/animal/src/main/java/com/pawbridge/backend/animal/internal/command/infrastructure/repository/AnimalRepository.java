package com.pawbridge.backend.animal.internal.command.infrastructure.repository;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AnimalRepository extends JpaRepository<Animal, Long> {
    long countByApmsNoticeNoStartingWith(String prefix);
}
