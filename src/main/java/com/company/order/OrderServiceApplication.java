package com.company.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} 是给 {@code QuotationService#cleanExpiredFiles} 用的 ——
 * 那个定时清理任务是场景5（ABBA 死锁）的另一半：没有它，生成路径永远等不到
 * 「持有 fileLock 的对手」，死锁也就无从形成。
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
