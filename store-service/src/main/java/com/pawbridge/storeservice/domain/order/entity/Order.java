package com.pawbridge.storeservice.domain.order.entity;

import com.pawbridge.storeservice.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String orderUuid;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private String deliveryAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus deliveryStatus;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> orderItems = new ArrayList<>();

    @Builder
    public Order(String orderUuid, Long userId, Long totalAmount, String deliveryAddress) {
        this.orderUuid = orderUuid;
        this.userId = userId;
        this.totalAmount = totalAmount;
        this.deliveryAddress = deliveryAddress;
        this.status = OrderStatus.PENDING;
        this.deliveryStatus = DeliveryStatus.READY;
    }

    public void completeOrder() {
        this.status = OrderStatus.COMPLETED;
    }

    public void paid() {
        this.status = OrderStatus.PAID;
    }

    public void cancelOrder() {
        this.status = OrderStatus.CANCELLED;
    }

    public void updateTotalAmount(Long amount) {
        this.totalAmount = amount;
    }
}
