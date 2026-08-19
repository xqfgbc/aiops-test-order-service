package com.company.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 稳态日志生成器：模拟业务不断产生日志，让 ES 持续有数据供 log-analyst 查询。
 */
@Component
public class LogGenerator {

    private static final Logger log = LoggerFactory.getLogger(LogGenerator.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong count = new AtomicLong();
    private ScheduledExecutorService exec;

    public void start(int rate, String level) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        exec = Executors.newSingleThreadScheduledExecutor();
        long interval = 1000 / Math.max(1, rate);   // rate 条/秒
        exec.scheduleAtFixedRate(() -> emit(level), 0, interval, TimeUnit.MILLISECONDS);
    }

    private void emit(String level) {
        long n = count.incrementAndGet();
        switch (level.toUpperCase()) {
            case "WARN" -> log.warn("三包期查询耗时偏高 {}ms orderId={}", 2000 + n % 3000, "ORD" + n);
            case "ERROR" -> log.error("报价单生成失败: java.io.IOException: No space left on device");
            default -> log.info("收到报价单生成请求 orderId={} fin={}", "ORD" + (1000 + n % 9000), randFin(n));
        }
    }

    public void stop() {
        if (running.getAndSet(false) && exec != null) {
            exec.shutdownNow();
        }
    }

    public long getCount() {
        return count.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    private String randFin(long n) {
        return "VIN" + String.format("%09d", n);
    }
}
