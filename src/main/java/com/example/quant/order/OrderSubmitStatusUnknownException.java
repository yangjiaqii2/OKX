package com.example.quant.order;

public class OrderSubmitStatusUnknownException extends RuntimeException {
    public OrderSubmitStatusUnknownException(String message) {
        super(message);
    }

    public OrderSubmitStatusUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
