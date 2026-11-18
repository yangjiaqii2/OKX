package com.example.quant.okxtrade;

/** Signals that order preparation failed before the trade order request was sent. */
public class OrderNotSubmittedException extends IllegalStateException {
    public OrderNotSubmittedException(String message, Throwable cause) {
        super(message, cause);
    }
}
