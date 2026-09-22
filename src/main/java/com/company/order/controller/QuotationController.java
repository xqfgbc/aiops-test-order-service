package com.company.order.controller;

import com.company.order.service.QuotationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class QuotationController {

    private final QuotationService quotationService;

    public QuotationController(QuotationService quotationService) {
        this.quotationService = quotationService;
    }

    @GetMapping("/quotation")
    public ResponseEntity<byte[]> quotation(@RequestParam String orderId) {
        byte[] content = quotationService.generateQuotation(orderId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=quotation_" + orderId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(content);
    }

    /**
     * 报价单摘要（场景3 被测）：service 内模板为 null，未判空 → NullPointerException。
     * 期望表现：HTTP 500，日志中可见 QuotationService.quotationSummary 的 NPE 堆栈。
     */
    @GetMapping("/quotation/exception")
    public Map<String, Object> exception(@RequestParam String orderId) {
        String summary = quotationService.quotationSummary(orderId);
        return Map.of("orderId", orderId, "summary", summary);
    }
    
}
