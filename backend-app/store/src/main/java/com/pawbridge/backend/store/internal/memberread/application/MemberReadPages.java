package com.pawbridge.backend.store.internal.memberread.application;

import com.pawbridge.backend.store.api.StoreMemberReads;
import java.util.List;
import java.util.function.LongSupplier;

/** PageableExecutionUtils/PageImpl total semantics used by the original repository pages. */
public final class MemberReadPages {
    private MemberReadPages() { }

    public static <T> StoreMemberReads.Page<T> page(List<T> content,StoreMemberReads.Request request,LongSupplier count) {
        long total;
        if(content.size()<request.size() && (request.page()==0 || !content.isEmpty())) {
            total=request.offset()+content.size();
        } else {
            total=count.getAsLong();
        }
        if(!content.isEmpty() && request.offset()+request.size()>total) {
            total=request.offset()+content.size();
        }
        return new StoreMemberReads.Page<>(content,request.page(),request.size(),total,request.sort());
    }
}
