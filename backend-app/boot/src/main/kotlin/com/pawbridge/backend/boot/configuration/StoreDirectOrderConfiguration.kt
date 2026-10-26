package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/** Only the direct-purchase POST is owned by the Direct flag. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.store-direct-orders", name = ["enabled"], havingValue = "true")
@Import(StoreOrderSupportConfiguration::class)
class StoreDirectOrderConfiguration {
    @Bean
    fun directOrderController(orders: DirectOrders): DirectOrderController {
        return DirectOrderController(orders)
    }
}
