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

    /**
     * 吐一批**同签名**的「瞬时故障、已自愈」ERROR —— 演示**诊断结论是「不是个问题」**的场景。
     *
     * <p>为什么必须是 ERROR、且必须同签名：日志监控的检测器是 {@code signature_aggregate}
     * （域配置 {@code {"signal":"ERROR","min_count":5}}，按签名聚合）——
     * **同签名不足 5 条不会开单**，也就触发不了诊断。低级别（WARN/INFO）同理不开单。
     *
     * <p>为什么不带堆栈：{@code signature()} 对无堆栈的日志**回退取 {@code message[:120}}**，
     * 所以"同一条消息重复 N 次"本身就是一个签名。刻意不带栈，是为了**不让栈帧指向本服务的
     * 代码** —— 这条日志要演示的正是"问题不在代码里"。
     *
     * <p>为什么文案是「已重试成功」：这**不是**缺陷 —— 下游抖了一下、应用自己恢复了。
     * 问题单会开出来（检测只看 ERROR 突增），而诊断/人应当判定**不用处理**。
     *
     * <p>⚠️ 消息必须是**常量**：带上 orderId 之类的变量会让每条签名都不同，
     * 于是聚合不出 LogAnomaly、一条单也开不出来。
     *
     * @return 实际写出的条数
     */
    public int emitTransientBurst(int count) {
        for (int i = 0; i < count; i++) {
            log.error("下游调用首次超时、重试已成功（瞬时抖动，非应用故障）: "
                    + "warranty-service read timed out");
            // ⚠️ **必须拉开间隔**：一次性连打 N 条会让它们的 `@timestamp` 完全相同，
            // 而采集是按 `@timestamp` 的水位线增量下推（`start=last_ts`）——
            // 同刻的 N 条会在窗口边界上被**劈开**，每轮只拿到一两条，
            // 于是永远凑不齐检测器要的 min_count，**一条单也开不出来**。
            // 实测踩过：6 条同一时刻的 ERROR → 两轮各采到 1 条 → anomaly_count=0。
            // 真实故障天然是"隔一会儿报一次"，这里照那个形状来。
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return count;
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
