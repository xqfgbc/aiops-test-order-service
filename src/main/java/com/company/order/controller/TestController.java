package com.company.order.controller;

import com.company.order.LogGenerator;
import com.company.order.service.QuotationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * 测试辅助接口：日志生成、填盘、健康检查。
 */
@RestController
@RequestMapping("/test")
public class TestController {

    private final LogGenerator logGenerator;
    private final QuotationService quotationService;

    public TestController(LogGenerator logGenerator, QuotationService quotationService) {
        this.logGenerator = logGenerator;
        this.quotationService = quotationService;
    }

    @PostMapping("/logs/start")
    public Map<String, Object> startLogs(@RequestParam(defaultValue = "10") int rate,
                                         @RequestParam(defaultValue = "INFO") String level) {
        logGenerator.start(rate, level);
        return Map.of("status", "started", "rate", rate, "level", level);
    }

    @PostMapping("/logs/stop")
    public Map<String, Object> stopLogs() {
        logGenerator.stop();
        return Map.of("status", "stopped");
    }

    @GetMapping("/logs/stats")
    public Map<String, Object> logStats() {
        return Map.of("count", logGenerator.getCount(), "running", logGenerator.isRunning());
    }

    @PostMapping("/leak")
    public Map<String, Object> leak(@RequestParam(defaultValue = "200") int count,
                                    @RequestParam(defaultValue = "1") int sizeMb) throws IOException {
        int created = quotationService.leakFiles(count, sizeMb);
        return Map.of("status", "leaked", "created", created, "sizeMb", sizeMb);
    }
}
