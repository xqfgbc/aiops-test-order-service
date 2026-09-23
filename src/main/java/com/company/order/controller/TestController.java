package com.company.order.controller;

import com.company.order.LogGenerator;
import com.company.order.service.QuotationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试辅助接口：日志生成、填盘、并发爆发、健康检查。
 */
@RestController
@RequestMapping("/test")
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);

    /** 单请求超时：死锁后请求永不返回，靠它把驱动线程放出来。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    /** 端点自身最多等这么久就返回，**绝不等所有请求回来**（死锁后它们永远不会回来）。 */
    private static final long RETURN_AFTER_MS = 2000;
    /** 线程数/轮次上限：避免演示时手滑把 pod 打爆。 */
    private static final int MAX_THREADS = 200;
    private static final int MAX_ROUNDS = 1000;

    private static final ThreadFactory BURST_THREADS = r -> {
        Thread t = new Thread(r, "quotation-burst");
        t.setDaemon(true);
        return t;
    };

    private final LogGenerator logGenerator;
    private final QuotationService quotationService;

    /** 本服务自己的端口：爆发用 HTTP 自调，见 {@link #concurrentBurst} 的说明。 */
    private final int port;

    /** 驱动爆发的 HTTP 客户端；executor 显式用 daemon 线程，避免拖住 JVM 退出。 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .executor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "quotation-burst-http");
                t.setDaemon(true);
                return t;
            }))
            .build();

    public TestController(LogGenerator logGenerator,
                          QuotationService quotationService,
                          @Value("${server.port:8080}") int port) {
        this.logGenerator = logGenerator;
        this.quotationService = quotationService;
        this.port = port;
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

    /**
     * 并发爆发：**场景4（SimpleDateFormat 竞态）与场景5（ABBA 死锁）的触发器**。
     *
     * <p>这两个场景都是并发型，**单发请求永远不触发** —— 需要一个"在途请求 ≥ 2"的瞬间。
     * 为什么不用 Postman Collection Runner：Runner 是**串行**的，一个个发，永远造不出并发。
     * 所以这里由服务自己起一批线程打自己。
     *
     * <p>用法（括号里是实测值，{@code quotation.line-items=100} 的本机环境）：
     * <ul>
     *   <li><b>场景4</b> 短爆发 {@code ?threads=8&rounds=10}（80 请求，跑不到一秒）→ {@code http5xx}
     *       8~16（约 10%~20% 的请求撞上竞态），且**不会**惊动死锁。</li>
     *   <li><b>场景5</b> 持续爆发 {@code ?threads=50&rounds=50}（负载持续超过一个清理周期）→
     *       {@code timedOut>0}，随后单发 {@code /quotation} 无响应、而 {@code /actuator/health} 仍是 200
     *       （服务已卡死，只能重启进程）。</li>
     * </ul>
     * <p>⚠️ 两者会互相干扰：**持续时间超过一个清理周期(默认 2s)的爆发基本必然撞出死锁**，
     * 于是场景4 也就测不成了。想看干净的场景4 就用短爆发，撞上了重启再试。
     *
     * <p>三个刻意的设计：
     * <ol>
     *   <li><b>走 HTTP 打自己，而不是直接调 service 方法</b>：这样每个请求都过 {@code TraceFilter}，
     *       日志与链路都带 traceId，与外部压测**完全等价**；直调的话驱动线程没有 traceId，
     *       诊断时反而比真实故障少一截信息。（另：外部请求同样会被死锁拦住，
     *       因为抢的是同一批锁、卡的是同一批 Tomcat 线程。）</li>
     *   <li><b>最多等 {@value #RETURN_AFTER_MS}ms 就返回</b>，不等请求回来：死锁后请求永不返回，
     *       端点在等的话自己也会挂住，反而看不出问题。{@code timedOut} 暴涨本身就是触发成功的证据。</li>
     *   <li><b>不逐请求打日志</b>：只为给 ES 添一条新签名、给诊断添噪声。结束一条 INFO 足够。</li>
     * </ol>
     *
     * @param threads 并发线程数（上限 {@value #MAX_THREADS}）
     * @param rounds  每线程打几轮（上限 {@value #MAX_ROUNDS}）
     */
    @PostMapping("/scenario/concurrent-burst")
    public Map<String, Object> concurrentBurst(@RequestParam(defaultValue = "50") int threads,
                                               @RequestParam(defaultValue = "20") int rounds)
            throws InterruptedException {
        int t = Math.min(Math.max(1, threads), MAX_THREADS);
        int r = Math.min(Math.max(1, rounds), MAX_ROUNDS);
        int dispatched = t * r;

        AtomicInteger completed = new AtomicInteger();
        AtomicInteger timedOut = new AtomicInteger();
        AtomicInteger http5xx = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(t, BURST_THREADS);
        try {
            for (int i = 0; i < t; i++) {
                final int tid = i;
                pool.execute(() -> {
                    for (int round = 0; round < r && !Thread.currentThread().isInterrupted(); round++) {
                        int code = fireQuotation(tid, round);
                        if (code == 0) {
                            timedOut.incrementAndGet();
                        } else {
                            completed.incrementAndGet();
                            if (code >= 500) {
                                http5xx.incrementAndGet();
                            }
                        }
                    }
                });
            }
            Thread.sleep(RETURN_AFTER_MS);
        } finally {
            // 还在途的请求**不会**被中断掉（服务端该卡还是卡着，那正是我们要观察的状态），
            // 这里只是把驱动线程放掉，让端点能返回。
            pool.shutdownNow();
            // ⚠️ 必须等它们把「没等到响应」记进计数器再读数字，否则读到的是快照：
            // 请求都还挂在 send() 里，计数看着像 0，返回值就没法当触发证据用。
            try {
                pool.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("concurrent-burst dispatched={} completed={} timedOut={} http5xx={}",
                dispatched, completed.get(), timedOut.get(), http5xx.get());
        return Map.of(
                "dispatched", dispatched,
                "completed", completed.get(),
                "timedOut", timedOut.get(),
                "http5xx", http5xx.get(),
                "note", "dispatched 是**投递**数（并发上限 threads，短窗口内只跑得完一部分）；"
                        + "http5xx > 0 = SimpleDateFormat 竞态抛异常；"
                        + "timedOut > 0 且随后单发请求无响应 = ABBA 死锁已触发"
        );
    }

    /**
     * 发一个 /quotation 请求。
     *
     * @return HTTP 状态码；{@code 0} 表示超时/连接失败（死锁时绝大多数落到这里）
     */
    private int fireQuotation(int tid, int round) {
        String url = "http://localhost:" + port + "/quotation?orderId=ORD" + (100000 + tid * 1000 + round);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @PostMapping("/leak")
    public Map<String, Object> leak(@RequestParam(defaultValue = "200") int count,
                                    @RequestParam(defaultValue = "1") int sizeMb) throws IOException {
        int created = quotationService.leakFiles(count, sizeMb);
        return Map.of("status", "leaked", "created", created, "sizeMb", sizeMb);
    }
}
