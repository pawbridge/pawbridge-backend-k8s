package com.pawbridge.storeservice.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // Common
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "Invalid Input Value"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C002", "Method Not Allowed"),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "C003", "Entity Not Found"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C004", "Server Error"),
    INVALID_TYPE_VALUE(HttpStatus.BAD_REQUEST, "C005", "Invalid Type Value"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "C006", "Access is Denied"),

    // Product
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "Product not found"),
    OPTION_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "P002", "Option Group not found"),
    OPTION_VALUE_NOT_FOUND(HttpStatus.NOT_FOUND, "P003", "Option Value not found"),
    SKU_NOT_FOUND(HttpStatus.NOT_FOUND, "P004", "SKU not found"),
    OUT_OF_STOCK(HttpStatus.CONFLICT, "P005", "Out of stock"),
    
    // Order
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "O001", "Order not found"),
    INVALID_ORDER_STATUS(HttpStatus.BAD_REQUEST, "O002", "Invalid order status");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
