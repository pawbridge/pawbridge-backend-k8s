package com.pawbridge.backend.store.internal.orderadministration.web;

import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.OrderStatus;
import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.DeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/orders")
public final class AdminOrderController {
    private final AdminOrders orders;
    public AdminOrderController(AdminOrders orders) {this.orders=orders;}
    @GetMapping public ResponseEntity<Page<DirectOrders.OrderDetails>> list(@AuthenticationPrincipal Long user,
            @RequestParam(name="status",required=false) OrderStatus status,@RequestParam(name="deliveryStatus",required=false) DeliveryStatus delivery,
            @RequestParam(name="userId",required=false) Long member,@RequestParam(name="keyword",required=false) String keyword,
            @RequestParam(name="sortBy",defaultValue="createdAt") String sortBy,@RequestParam(name="sortOrder",defaultValue="desc") String sortOrder,
            @PageableDefault(size=20) Pageable pageable) {
        var page=orders.list(current(user),new AdminOrders.Query(status==null?null:status.name(),delivery==null?null:delivery.name(),member,keyword,sortBy,sortOrder,pageable.getPageNumber(),pageable.getPageSize()));
        return ResponseEntity.ok(new PageImpl<>(page.content(),PageRequest.of(page.number(),page.size(),Sort.by(page.ascending()?Sort.Direction.ASC:Sort.Direction.DESC,page.sortBy())),page.totalElements()));
    }
    @GetMapping("/{orderId}") public ResponseEntity<DirectOrders.OrderDetails> get(@AuthenticationPrincipal Long user,@PathVariable("orderId") long id) {return ResponseEntity.ok(orders.get(current(user),id));}
    @PatchMapping("/{orderId}/delivery-status") public ResponseEntity<DirectOrders.OrderDetails> update(@AuthenticationPrincipal Long user,@PathVariable("orderId") long id,@RequestBody DeliveryStatusUpdateRequest request) {
        return ResponseEntity.ok(orders.updateDelivery(current(user),id,request.getDeliveryStatus()==null?null:request.getDeliveryStatus().name()));
    }
    @PatchMapping("/{orderId}/status") public ResponseEntity<DirectOrders.OrderDetails> updateStatus(@AuthenticationPrincipal Long user,@PathVariable("orderId") long id,@RequestBody OrderStatusUpdateRequest request) {
        return ResponseEntity.ok(orders.updateStatus(current(user),id,request.getStatus()==null?null:request.getStatus().name()));
    }
    private static long current(Long user) {if(user==null)throw new AuthenticationCredentialsNotFoundException("An authenticated member is required");return user;}
}
