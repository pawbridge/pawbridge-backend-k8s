package com.pawbridge.backend.store.internal.orderadministration.application;

import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import java.util.List;

public interface AdminOrders {
    record Query(String status,String deliveryStatus,Long userId,String keyword,String sortBy,String sortOrder,int page,int size) { }
    record Page(List<DirectOrders.OrderDetails> content,int number,int size,long totalElements,String sortBy,boolean ascending) { }
    Page list(long verifiedAdministratorId,Query query);
    DirectOrders.OrderDetails get(long verifiedAdministratorId,long orderId);
    DirectOrders.OrderDetails updateDelivery(long verifiedAdministratorId,long orderId,String deliveryStatus);
}
