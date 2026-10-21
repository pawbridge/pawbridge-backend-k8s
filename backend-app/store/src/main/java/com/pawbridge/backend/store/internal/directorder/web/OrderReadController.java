package com.pawbridge.backend.store.internal.directorder.web;

import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class OrderReadController {
    private final DirectOrders orders;
    public OrderReadController(DirectOrders orders) { this.orders=orders; }
    @GetMapping("/api/v1/orders")
    public ResponseEntity<Page<DirectOrders.OrderDetails>> list(@AuthenticationPrincipal Long current,
            @RequestParam(name="status",required=false) OrderStatus status,@PageableDefault(size=10) Pageable pageable) {
        var request=new StoreMemberReads.Request(pageable.getPageNumber(),pageable.getPageSize(),pageable.getSort().stream()
                .map(order->new StoreMemberReads.Order(order.getProperty(),order.isAscending(),order.isIgnoreCase())).toList());
        var page=orders.list(current(current),status==null?null:status.name(),request);
        var sort=Sort.by(page.sort().stream().map(order->new Sort.Order(
                order.ascending()?Sort.Direction.ASC:Sort.Direction.DESC,order.property(),order.ignoreCase(),Sort.NullHandling.NATIVE)).toList());
        return ResponseEntity.ok(new PageImpl<>(page.content(),PageRequest.of(page.number(),page.size(),sort),page.totalElements()));
    }
    @GetMapping("/api/v1/orders/{orderId}")
    public ResponseEntity<DirectOrders.OrderDetails> get(@AuthenticationPrincipal Long current,@PathVariable("orderId") long orderId) {
        return ResponseEntity.ok(orders.get(current(current),orderId));
    }
    private static long current(Long member) {
        if(member==null) throw new AuthenticationCredentialsNotFoundException("An authenticated member is required");
        return member;
    }
}
