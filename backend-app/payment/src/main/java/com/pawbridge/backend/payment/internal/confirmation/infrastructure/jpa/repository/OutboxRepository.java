package com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository;

import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {
}
