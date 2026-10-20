package com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository;

import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.model.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {
}
