package com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository;

import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.PaymentCompensationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCompensationRecordRepository extends JpaRepository<PaymentCompensationRecord, Long> {
}
