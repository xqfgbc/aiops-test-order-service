package com.company.order.controller;

import com.company.order.LogGenerator;
import com.company.order.service.QuotationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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

    // fix-3：leak 接口默认下线；需显式设置 test.leak-enabled=true 且携带内部令牌才可用
    @Value("${test.leak-enabled:false}")
    private boolean leakEnabled;

    @Value("${test.leak-token:}")
    private String leakToken;

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
    public ResponseEntity<Map<String, Object>> leak(@RequestParam(defaultValue = "200") int count,
                                                    @RequestParam(defaultValue = "1") int sizeMb,
                                                    @RequestHeader(value = "X-Internal-Token", required = false) String token) throws IOException {
        // fix-3：未启用或未携带内部令牌一律拒绝（防外部无鉴权触发填盘）
        if (!leakEnabled || leakToken.isEmpty() || !leakToken.equals(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "forbidden", "message", "leak 接口已下线或需要内部令牌"));
        }
        int created = quotationService.leakFiles(count, sizeMb);
        return ResponseEntity.ok(Map.of("status", "leaked", "created", created, "sizeMb", sizeMb));
    }
}
