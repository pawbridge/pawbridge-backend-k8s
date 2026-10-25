package com.pawbridge.backend.animal.internal.command.infrastructure.service

import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import org.slf4j.LoggerFactory
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 수동 등록 동물의 공고번호 생성기
 * - 형식: MAN-{YYMMDD}-{6자리순번}
 * - 예: MAN-251230-000001
 */
class NoticeNumberGenerator(
    private val animalRepository: AnimalRepository,
) {

    /**
     * 수동 등록용 공고번호 생성
     * @return 고유한 공고번호
     */
    fun generate(): String {
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ) {
            throw IllegalStateException("수동 공고번호는 쓰기 트랜잭션 안에서 생성해야 합니다.")
        }
        val isolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()
        if (isolationLevel != TransactionDefinition.ISOLATION_READ_COMMITTED) {
            throw IllegalStateException("수동 공고번호에는 명시적인 READ_COMMITTED 격리 수준이 필요합니다.")
        }

        val date = LocalDate.now()
        val today = date.format(DATE_FORMAT)
        val prefix = PREFIX + "-" + today + "-"

        // Separate statements: MAX must obtain its snapshot after the preceding creator commits.
        animalRepository.lockManualNoticePrefix(LOCK_NAMESPACE, today.toInt())
        val maximum = animalRepository.findMaxManualNoticeSuffix(prefix + "%", "^" + prefix + "([0-9]{6})$")
        if (maximum >= 999999) {
            throw IllegalStateException("수동 등록 공고번호의 일일 한도를 초과했습니다.")
        }
        val nextNumber = maximum + 1L

        // 6자리 순번 (000001 ~ 999999)
        val noticeNo = prefix + String.format("%06d", nextNumber)

        log.info("수동 등록 공고번호 생성: {}", noticeNo)
        return noticeNo
    }

    private companion object {
        private val log = LoggerFactory.getLogger(NoticeNumberGenerator::class.java)

        private const val PREFIX = "MAN"

        // Stable database-wide namespace (PBAN); the second key is the same yyMMdd used in the notice.
        private const val LOCK_NAMESPACE = 0x5042414E
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyMMdd")
    }
}
