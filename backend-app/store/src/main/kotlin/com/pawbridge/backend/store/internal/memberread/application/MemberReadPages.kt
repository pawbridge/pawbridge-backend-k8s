package com.pawbridge.backend.store.internal.memberread.application

import com.pawbridge.backend.store.api.StoreMemberReads
import java.util.function.LongSupplier

/** PageableExecutionUtils/PageImpl total semantics used by the original repository pages. */
object MemberReadPages {

    @JvmStatic
    fun <T> page(
        content: List<T>,
        request: StoreMemberReads.Request,
        count: LongSupplier,
    ): StoreMemberReads.Page<T> {
        var total: Long
        if (content.size < request.size() && (request.page() == 0 || content.isNotEmpty())) {
            total = request.offset() + content.size
        } else {
            total = count.asLong
        }
        if (content.isNotEmpty() && request.offset() + request.size() > total) {
            total = request.offset() + content.size
        }
        return StoreMemberReads.Page(content, request.page(), request.size(), total, request.sort())
    }
}
