package com.pawbridge.backend.animal.internal.command.infrastructure.repository;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ShelterRepository extends JpaRepository<Shelter, Long> {
    java.util.Optional<Shelter> findByCareRegNo(String careRegNo);
    java.util.List<Shelter> findByCareRegNoIn(java.util.List<String> careRegNos);
}
