package com.pawbridge.backend.payment.internal.confirmation.application

/** The order is not PENDING; raised before the provider is contacted. */
class PaymentOrderNotPayableException : RuntimeException("Order is not in PENDING state")
