package com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository

import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.PaymentCompensationRecord
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentCompensationRecordRepository : JpaRepository<PaymentCompensationRecord, Long>
