package com.pawbridge.storeservice.common.repository;

import com.pawbridge.storeservice.common.entity.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {
}
