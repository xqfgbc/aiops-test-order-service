package com.company.order.controller;

import com.company.order.service.QuotationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将 QuotationException 映射为可读业务错误响应（fix-6：磁盘满优雅降级，而非 500/挂起）。
 */
@RestControllerAdvice
public class QuotationExceptionHandler {

    @ExceptionHandler(QuotationException.class)
    public ResponseEntity<Map<String, Object>> handleQuotation(QuotationException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "QUOTATION_UNAVAILABLE", "message", e.getMessage()));
    }
}
