package com.pawbridge.backend.animal.internal.command.infrastructure.repository;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

}
