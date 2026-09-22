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

    /**
     * 一键场景：**瞬时故障、已自愈** —— 期望的诊断结论是「不是个问题」。
     *
     * <p>用法：{@code curl -XPOST 'localhost:18080/test/scenario/transient'}（默认 8 条）
     * 之后等一轮采集（日志监控端点的 {@code interval_sec=60}），APM 会开出一条
     * warning 级的问题单，在 Diagnosis Center 里点「Analyze」即可跑诊断。
     *
     * <p>{@code count} 默认 8 —— **日志检测器的 {@code min_count} 是 5**，低于它不会开单
     * （域配置 {@code {"signal":"ERROR","min_count":5}}）。传更小会照发日志但开不出单。
     * <p>整条数会**拉开 400ms 间隔**发射，理由见 {@link LogGenerator#emitTransientBurst}。
     *
     * <p>为什么要清窗：连续跑不同场景会互相污染（上一条的 ERROR 还在 ES 的查询窗口里），
     * 换场景前先清：{@code curl -XDELETE localhost:19200/app-logs}。
     */
    @PostMapping("/scenario/transient")
    public Map<String, Object> transientScenario(@RequestParam(defaultValue = "8") int count) {
        int emitted = logGenerator.emitTransientBurst(count);
        return Map.of(
                "status", "emitted",
                "level", "ERROR",
                "count", emitted,
                "note", "同签名 ERROR —— 日志检测器按 signature_aggregate 聚合，"
                        + "min_count=5（域配置决定），低于它不会开单"
        );
    }

    @PostMapping("/leak")
    public Map<String, Object> leak(@RequestParam(defaultValue = "200") int count,
                                    @RequestParam(defaultValue = "1") int sizeMb) throws IOException {
        int created = quotationService.leakFiles(count, sizeMb);
        return Map.of("status", "leaked", "created", created, "sizeMb", sizeMb);
    }
}
