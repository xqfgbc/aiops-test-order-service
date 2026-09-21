package com.company.order.service;

public class QuotationException extends RuntimeException {
    public QuotationException(String message) {
        super(message);
    }

    public QuotationException(String message, Throwable cause) {
        super(message, cause);
    }
}
