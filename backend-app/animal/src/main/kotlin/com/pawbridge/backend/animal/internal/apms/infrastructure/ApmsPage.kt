package com.pawbridge.backend.animal.internal.apms.infrastructure

import com.pawbridge.backend.animal.internal.apms.infrastructure.client.ApmsApiClient
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsRootResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** A validated provider page shared by shelter preparation and animal ingestion. */
@JvmRecord
data class ApmsPage(
    val items: List<ApmsAnimal>,
    val last: Boolean,
    val total: Long,
    val countMatches: Boolean,
) {

    /** Only sanitized provider failures are recoverable; application defects still propagate. */
    class FetchException(reason: String) : IllegalStateException(reason)

    companion object {
        @JvmStatic
        fun fetch(
            client: ApmsApiClient,
            key: String?,
            page: Int,
            size: Int,
            query: ApmsSyncPlan.Query,
        ): ApmsPage {
            val root: ApmsRootResponse<ApmsAnimal>?
            try {
                root = client.getAbandonmentAnimals(
                    key,
                    page,
                    size,
                    format(query.intakeStart),
                    format(query.intakeEnd),
                    null,
                    null,
                    "json",
                    format(query.updatedStart),
                    format(query.updatedEnd),
                )
            } catch (exception: RuntimeException) {
                // Feign exceptions can contain the service key in the request URL.
                throw FetchException("REQUEST_FAILED")
            }
            val header = root?.response?.header
            if (root == null ||
                root.response == null ||
                header == null ||
                "00" != header.resultCode ||
                root.response!!.body == null
            ) {
                throw FetchException("UNSUCCESSFUL_RESPONSE")
            }
            val body = root.response!!.body!!
            val total: Long
            try {
                total = java.lang.Long.parseLong(body.totalCount)
                if (total < 0 || Integer.parseInt(body.pageNo) != page) {
                    throw NumberFormatException()
                }
            } catch (exception: NumberFormatException) {
                throw FetchException("INVALID_PAGINATION")
            }
            val items: List<ApmsAnimal?> = if (body.items == null || body.items!!.item == null) {
                java.util.List.of()
            } else {
                body.items!!.item!!
            }
            val offset = (page - 1).toLong() * size
            val expected = minOf(size.toLong(), maxOf(0L, total - offset))
            if (items.any { item -> item == null }) {
                throw IllegalStateException("APMS page is incomplete at page $page")
            }
            @Suppress("UNCHECKED_CAST")
            return ApmsPage(items as List<ApmsAnimal>, offset + size >= total, total, items.size.toLong() == expected)
        }

        private fun format(date: LocalDate?): String? = date?.format(DateTimeFormatter.BASIC_ISO_DATE)
    }
}
