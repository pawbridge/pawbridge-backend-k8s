package com.pawbridge.backend.animal.internal.apms.infrastructure

import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import java.time.LocalDate
import java.util.TreeMap

/**
 * A fixed KST collection window shared by every step in one execution.
 *
 * Java record와 같은 접근자(`recentStart()` 등)와 equals/hashCode/toString을 가진다.
 * compact 생성자가 `Map.copyOf`로 인자를 바꾸므로 @JvmRecord data class 대신 직접 구현했다.
 */
class ApmsSyncPlan(
    recentStart: LocalDate?,
    historyStart: LocalDate?,
    updatedStart: LocalDate?,
    end: LocalDate?,
    monthlyStarts: Map<LocalDate, LocalDate>?,
) {
    @get:JvmName("recentStart")
    val recentStart: LocalDate

    @get:JvmName("historyStart")
    val historyStart: LocalDate

    @get:JvmName("updatedStart")
    val updatedStart: LocalDate

    @get:JvmName("end")
    val end: LocalDate

    @get:JvmName("monthlyStarts")
    val monthlyStarts: Map<LocalDate, LocalDate>

    constructor(
        recentStart: LocalDate?,
        historyStart: LocalDate?,
        updatedStart: LocalDate?,
        end: LocalDate?,
    ) : this(recentStart, historyStart, updatedStart, end, java.util.Map.of())

    init {
        val copiedMonthlyStarts: Map<LocalDate, LocalDate> = java.util.Map.copyOf(monthlyStarts)
        if (recentStart == null ||
            historyStart == null ||
            updatedStart == null ||
            end == null ||
            recentStart.isAfter(end) ||
            historyStart.isAfter(recentStart) ||
            updatedStart.isAfter(end)
        ) {
            throw IllegalArgumentException("Invalid APMS collection window")
        }
        for (entry in copiedMonthlyStarts.entries) {
            if (entry.key.dayOfMonth != 1 ||
                entry.key.isBefore(historyStart.withDayOfMonth(1)) ||
                entry.key.isAfter(end) ||
                entry.value.isBefore(updatedStart) ||
                entry.value.isAfter(end)
            ) {
                throw IllegalArgumentException("Invalid APMS monthly window")
            }
        }
        this.recentStart = recentStart
        this.historyStart = historyStart
        this.updatedStart = updatedStart
        this.end = end
        this.monthlyStarts = copiedMonthlyStarts
    }

    fun parameters(): JobParameters {
        val builder = JobParametersBuilder()
            .addString(CONTRACT_KEY, CONTRACT)
            .addString("apms.sync.recentStart", recentStart.toString())
            .addString("apms.sync.historyStart", historyStart.toString())
            .addString("apms.sync.updatedStart", updatedStart.toString())
            .addString(END_KEY, end.toString())
        // Separate parameters avoid the Batch metadata column's per-value length limit.
        TreeMap(monthlyStarts).forEach { (month, start) ->
            builder.addString(MONTH_PREFIX + month, start.toString())
        }
        return builder.toJobParameters()
    }

    fun queries(): List<Query> {
        val result = ArrayList<Query>()
        // Keep the original intake query, including new animals without updTm.
        result.add(Query(recentStart, end, null, null))
        var month = historyStart.withDayOfMonth(1)
        while (!month.isAfter(end)) {
            val monthEnd = month.plusMonths(1).minusDays(1)
            result.add(
                Query(
                    month,
                    if (monthEnd.isAfter(end)) end else monthEnd,
                    monthlyStarts.getOrDefault(month, updatedStart),
                    end,
                ),
            )
            month = month.plusMonths(1)
        }
        return java.util.List.copyOf(result)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ApmsSyncPlan) {
            return false
        }
        return recentStart == other.recentStart &&
            historyStart == other.historyStart &&
            updatedStart == other.updatedStart &&
            end == other.end &&
            monthlyStarts == other.monthlyStarts
    }

    override fun hashCode(): Int {
        var hash = recentStart.hashCode()
        hash = 31 * hash + historyStart.hashCode()
        hash = 31 * hash + updatedStart.hashCode()
        hash = 31 * hash + end.hashCode()
        hash = 31 * hash + monthlyStarts.hashCode()
        return hash
    }

    override fun toString(): String {
        return "ApmsSyncPlan[recentStart=$recentStart, historyStart=$historyStart, updatedStart=$updatedStart, " +
            "end=$end, monthlyStarts=$monthlyStarts]"
    }

    @JvmRecord
    data class Query(
        val intakeStart: LocalDate,
        val intakeEnd: LocalDate,
        val updatedStart: LocalDate?,
        val updatedEnd: LocalDate?,
    )

    companion object {
        const val CONTRACT: String = "query-v2"
        const val LEGACY_CONTRACT: String = "updated-v1"
        private const val MONTH_PREFIX: String = "apms.sync.month."
        const val CONTRACT_KEY: String = "apms.sync.contract"
        const val END_KEY: String = "apms.sync.end"

        @JvmStatic
        fun from(parameters: JobParameters): ApmsSyncPlan {
            if (CONTRACT != parameters.getString(CONTRACT_KEY) &&
                LEGACY_CONTRACT != parameters.getString(CONTRACT_KEY)
            ) {
                throw IllegalArgumentException("Missing APMS collection contract")
            }
            val months: MutableMap<LocalDate, LocalDate> = TreeMap()
            if (CONTRACT == parameters.getString(CONTRACT_KEY)) {
                for (key in parameters.parameters.keys) {
                    if (key.startsWith(MONTH_PREFIX)) {
                        months[LocalDate.parse(key.substring(MONTH_PREFIX.length))] =
                            LocalDate.parse(parameters.getString(key))
                    }
                }
            }

            return ApmsSyncPlan(
                LocalDate.parse(parameters.getString("apms.sync.recentStart")),
                LocalDate.parse(parameters.getString("apms.sync.historyStart")),
                LocalDate.parse(parameters.getString("apms.sync.updatedStart")),
                LocalDate.parse(parameters.getString(END_KEY)),
                months,
            )
        }
    }
}
