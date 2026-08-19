package com.company.order.controller;

import com.company.order.client.WarrantyClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CheckoutController {

    private final WarrantyClient warrantyClient;

    public CheckoutController(WarrantyClient warrantyClient) {
        this.warrantyClient = warrantyClient;
    }

    @PostMapping("/checkout")
    public Map<String, Object> checkout(@RequestParam String orderId) {
        // 结账前同步查询三包期（场景2：这里调 warranty-service）
        Map<String, Object> warranty = warrantyClient.checkWarranty(orderId);
        return Map.of(
                "orderId", orderId,
                "status", "checked_out",
                "warranty", warranty
        );
    }
}
