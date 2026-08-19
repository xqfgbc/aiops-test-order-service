package com.company.order.controller;

import com.company.order.service.QuotationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
