package com.pawbridge.backend.store.internal.memberread.application;

import static org.junit.jupiter.api.Assertions.*;
import com.pawbridge.backend.store.api.StoreMemberReads;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;

class MemberReadPagesTest {
    @ParameterizedTest @MethodSource("pages")
    void given__repository_page_content__when__assembling__then__match_real_spring_page_total_and_count_execution(
            int page,int size,int elements,long count) {
        List<Integer> content=IntStream.range(0,elements).boxed().toList();
        var request=new StoreMemberReads.Request(page,size,List.of(new StoreMemberReads.Order("createdAt",false,false)));
        var originalCalls=new AtomicInteger();var candidateCalls=new AtomicInteger();
        var original=PageableExecutionUtils.getPage(content,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt")),()->{ originalCalls.incrementAndGet();return count; });
        var candidate=MemberReadPages.page(content,request,()->{candidateCalls.incrementAndGet();return count;});
        assertEquals(original.getContent(),candidate.content());assertEquals(original.getNumber(),candidate.number());
        assertEquals(original.getSize(),candidate.size());assertEquals(original.getTotalElements(),candidate.totalElements());
        assertEquals(originalCalls.get(),candidateCalls.get());assertEquals(request.sort(),candidate.sort());
    }
    static Stream<Arguments> pages() { return Stream.of(
            Arguments.of(0,20,0,999L),Arguments.of(0,20,3,999L),Arguments.of(0,2,2,9L),
            Arguments.of(2,2,1,999L),Arguments.of(2,2,0,2L),Arguments.of(2,2,2,5L),Arguments.of(2,2,2,9L)); }
}
