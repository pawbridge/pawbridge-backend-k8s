package com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

@Entity
@Table(name = "orders")
class Order(
    orderUuid: String?,
    userId: Long?,
    totalAmount: Long?,
    deliveryAddress: String?,
    receiverName: String?,
    receiverPhone: String?,
    deliveryMessage: String?,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false, unique = true, length = 36)
    var orderUuid: String? = orderUuid
        protected set

    @Column(nullable = false)
    var userId: Long? = userId
        protected set

    @Column(nullable = false)
    var totalAmount: Long? = totalAmount
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OrderStatus? = OrderStatus.PENDING
        protected set

    @Column(nullable = false)
    var deliveryAddress: String? = deliveryAddress
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var deliveryStatus: DeliveryStatus? = DeliveryStatus.READY
        protected set

    @Column(nullable = false, length = 50)
    var receiverName: String? = receiverName
        protected set

    @Column(nullable = false, length = 20)
    var receiverPhone: String? = receiverPhone
        protected set

    @Column(length = 200)
    var deliveryMessage: String? = deliveryMessage
        protected set

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    var orderItems: MutableList<OrderItem> = ArrayList()
        protected set

    // Hibernate's constructor. Like the former protected Lombok constructor it leaves status and
    // deliveryStatus unset (only the public constructor starts an order as PENDING / READY).
    protected constructor() : this(null, null, null, null, null, null, null) {
        this.status = null
        this.deliveryStatus = null
    }

    fun completeOrder() {
        this.status = OrderStatus.COMPLETED
    }

    fun paid() {
        this.status = OrderStatus.PAID
    }

    fun cancelOrder() {
        this.status = OrderStatus.CANCELLED
    }

    fun updateTotalAmount(amount: Long?) {
        this.totalAmount = amount
    }

    /**
     * 주문 상태 변경 (관리자용)
     */
    fun updateStatus(status: OrderStatus?) {
        this.status = status
    }

    /**
     * 배송 상태 변경 (관리자용)
     */
    fun updateDeliveryStatus(deliveryStatus: DeliveryStatus?) {
        this.deliveryStatus = deliveryStatus
    }

    class OrderBuilder internal constructor() {
        private var orderUuid: String? = null
        private var userId: Long? = null
        private var totalAmount: Long? = null
        private var deliveryAddress: String? = null
        private var receiverName: String? = null
        private var receiverPhone: String? = null
        private var deliveryMessage: String? = null

        fun orderUuid(orderUuid: String?): OrderBuilder = apply {
            this.orderUuid = orderUuid
        }

        fun userId(userId: Long?): OrderBuilder = apply {
            this.userId = userId
        }

        fun totalAmount(totalAmount: Long?): OrderBuilder = apply {
            this.totalAmount = totalAmount
        }

        fun deliveryAddress(deliveryAddress: String?): OrderBuilder = apply {
            this.deliveryAddress = deliveryAddress
        }

        fun receiverName(receiverName: String?): OrderBuilder = apply {
            this.receiverName = receiverName
        }

        fun receiverPhone(receiverPhone: String?): OrderBuilder = apply {
            this.receiverPhone = receiverPhone
        }

        fun deliveryMessage(deliveryMessage: String?): OrderBuilder = apply {
            this.deliveryMessage = deliveryMessage
        }

        fun build(): Order = Order(
            orderUuid,
            userId,
            totalAmount,
            deliveryAddress,
            receiverName,
            receiverPhone,
            deliveryMessage,
        )
    }

    companion object {
        @JvmStatic
        fun builder(): OrderBuilder = OrderBuilder()
    }
}
