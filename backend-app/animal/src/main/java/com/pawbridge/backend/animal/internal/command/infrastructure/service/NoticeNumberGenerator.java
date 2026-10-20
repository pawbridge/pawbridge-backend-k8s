package com.pawbridge.backend.animal.internal.command.infrastructure.service;

import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 수동 등록 동물의 공고번호 생성기
 * - 형식: MAN-{YYMMDD}-{6자리순번}
 * - 예: MAN-251230-000001
 */
@Slf4j
@RequiredArgsConstructor
public class NoticeNumberGenerator {

    private static final String PREFIX = "MAN";
    // Stable database-wide namespace (PBAN); the second key is the same yyMMdd used in the notice.
    private static final int LOCK_NAMESPACE = 0x5042414E;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyMMdd");

    private final AnimalRepository animalRepository;

    /**
     * 수동 등록용 공고번호 생성
     * @return 고유한 공고번호
     */
    public String generate() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("수동 공고번호는 쓰기 트랜잭션 안에서 생성해야 합니다.");
        }
        if (!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
                .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("수동 공고번호에는 명시적인 READ_COMMITTED 격리 수준이 필요합니다.");
        }

        LocalDate date = LocalDate.now();
        String today = date.format(DATE_FORMAT);
        String prefix = PREFIX + "-" + today + "-";

        // Separate statements: MAX must obtain its snapshot after the preceding creator commits.
        animalRepository.lockManualNoticePrefix(LOCK_NAMESPACE, Integer.parseInt(today));
        int maximum = animalRepository.findMaxManualNoticeSuffix(prefix + "%", "^" + prefix + "([0-9]{6})$");
        if (maximum >= 999999) {
            throw new IllegalStateException("수동 등록 공고번호의 일일 한도를 초과했습니다.");
        }
        long nextNumber = maximum + 1L;

        // 6자리 순번 (000001 ~ 999999)
        String noticeNo = prefix + String.format("%06d", nextNumber);

        log.info("수동 등록 공고번호 생성: {}", noticeNo);
        return noticeNo;
    }
}
