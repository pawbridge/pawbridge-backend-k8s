package com.pawbridge.paymentservice.common.repository;

import com.pawbridge.paymentservice.common.entity.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {
}
