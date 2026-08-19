package com.company.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 结账时调 warranty-service 查询三包期（场景2 的跨服务调用）。
 * url 直连 K8s DNS，不引 Eureka/Nacos。
 */
@FeignClient(name = "warranty-service", url = "${warranty.service-url:http://warranty-service:8080}")
public interface WarrantyClient {

    @PostMapping("/checkWarranty")
    Map<String, Object> checkWarranty(@RequestParam("orderId") String orderId);
}
