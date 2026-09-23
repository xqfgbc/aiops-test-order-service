package com.company.order.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QuotationService 单元测试（场景1、4、5、6、7 的被测服务）。
 *
 * <p>不起 Spring 上下文：{@code @Value} 字段直接注，测的是**类自己的行为**，毫秒级完成。
 * 要测装配/HTTP 层应另开 {@code @SpringBootTest}。
 *
 * <p><b>这些是"特征化测试"</b>（characterization）：它们**钉住注入 bug 的现状**，
 * 而不是断言它「正确」—— 例如断言「没清理临时文件」「打了 10 条日志」「锁会互相卡死」。
 * 要验证修复，应当反过来断言期望行为（清理干净、日志收敛到 1 条、不再死锁）。
 *
 * <p>场景4（SimpleDateFormat 竞态）**本质是概率性的**：低并发下不复现，
 * 单测不跑竞态，只用反射钉住"共享 static 实例"这个注入事实。
 */
class QuotationServiceTest {

    /** 测试里把明细行数压到 10：默认 500 会让每条例打 500 行日志，又慢又吵。 */
    private static final int TEST_LINE_ITEMS = 10;

    /**
     * 测试里把清理任务的锁内窗口拉到 100ms：生产默认是 5ms（为了不让单发请求误伤服务），
     * 但 5ms 会让死锁那条用例的时序变脆 —— 那里靠轮询捕获"清理线程正在锁内 sleep"的状态。
     */
    private static final long TEST_CLEANUP_SCAN_HOLD_MS = 100;

    private static QuotationService service(Path tempDir, boolean tempLeak) {
        QuotationService svc = new QuotationService();
        ReflectionTestUtils.setField(svc, "tempDir", tempDir.toString());
        ReflectionTestUtils.setField(svc, "tempLeak", tempLeak);
        ReflectionTestUtils.setField(svc, "lineItems", TEST_LINE_ITEMS);
        ReflectionTestUtils.setField(svc, "cleanupScanHoldMs", TEST_CLEANUP_SCAN_HOLD_MS);
        return svc;
    }

    private static long countOf(Path dir, String prefix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        }
    }

    private static ListAppender<ILoggingEvent> attach() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger().addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        logger().detachAppender(appender);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(QuotationService.class);
    }

    private static Thread daemon(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }

    @Test
    void generateQuotation_returnsContentMentioningTheOrder(@TempDir Path tmp) {
        byte[] pdf = service(tmp, false).generateQuotation("ORD-1001");

        assertThat(new String(pdf, StandardCharsets.UTF_8)).contains("ORD-1001");
    }

    @Test
    void generateQuotation_cleansUpItsTempFileByDefault(@TempDir Path tmp) throws IOException {
        service(tmp, false).generateQuotation("ORD-1002");

        // 正常路径：finally 里删掉临时文件，目录不该留东西
        assertThat(countOf(tmp, "quotation_")).isZero();
    }

    @Test
    void generateQuotation_leavesTempFileWhenLeakSwitchIsOn(@TempDir Path tmp) throws IOException {
        // 场景1 的 bug 开关（QUOTATION_TEMP_LEAK=true）：finally 不清理 → 反复调用写满磁盘。
        // 这是**特征化测试**：钉住开关的现状，而不是断言它「正确」——
        // 要验证修复，应当反过来断言 leak 开启时也不留文件。
        service(tmp, true).generateQuotation("ORD-1003");

        assertThat(countOf(tmp, "quotation_")).isEqualTo(1);
    }

    @Test
    void generateQuotation_createsTempDirWhenMissing(@TempDir Path tmp) {
        Path nested = tmp.resolve("not/created/yet");
        assertThat(nested).doesNotExist();

        service(nested, false).generateQuotation("ORD-1004");

        assertThat(nested).exists();
    }

    @Test
    void leakFiles_createsRequestedNumberOfFiles(@TempDir Path tmp) throws IOException {
        int created = service(tmp, false).leakFiles(3, 1);

        assertThat(created).isEqualTo(3);
        assertThat(countOf(tmp, "leak_")).isEqualTo(3);
        // 与报价单是两套文件，别互相混淆
        assertThat(countOf(tmp, "quotation_")).isZero();
    }

    @Test
    void generateQuotation_rejectsOrderIdWithoutOrdPrefixButLogsItAsError(@TempDir Path tmp) {
        // 场景6 特征化：这是**误报**演示 —— 调用方的错（本该 400、本不该记 ERROR），
        // 这里记成 ERROR 并抛 IllegalArgumentException 走 500。
        // 断言"消息里没有任何插值"是刻意的：带变量会让每条签名都不同，
        // 基于 signature_aggregate 的日志检测就聚合不出 LogAnomaly（同 LogGenerator 的注释）。
        ListAppender<ILoggingEvent> appender = attach();
        try {
            assertThatThrownBy(() -> service(tmp, false).generateQuotation("abc"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.ERROR)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("报价单参数校验失败: 订单号格式非法");
        } finally {
            detach(appender);
        }
    }

    @Test
    void generateQuotation_logsOneLinePerDetailItem(@TempDir Path tmp) {
        // 场景7 特征化：日志风暴 —— 单请求打 (lineItems + 1) 条 INFO（每行明细一条 + 一条"生成成功"）。
        // 要验证修复，应当断言它收敛到 1 条左右，而不是 11 条。
        ListAppender<ILoggingEvent> appender = attach();
        try {
            service(tmp, false).generateQuotation("ORD-1005");

            long perItem = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().startsWith("报价单明细渲染"))
                    .count();
            assertThat(perItem).isEqualTo(TEST_LINE_ITEMS);
        } finally {
            detach(appender);
        }
    }

    @Test
    void dateFormatter_isSharedStaticInstance() throws Exception {
        // 场景4 特征化：只钉住"SimpleDateFormat 被 static 共享"这个注入事实。
        // 真正的竞态（并发下算错日期/抛 NumberFormatException）**低并发不复现**，
        // 单测里不跑：要复现请用 POST /test/scenario/concurrent-burst，见 docs/POSTMAN_SCENARIOS.md。
        Field field = QuotationService.class.getDeclaredField("DATE_FMT");

        assertThat(Modifier.isStatic(field.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(field.getModifiers())).isTrue();
        assertThat(field.getType()).isEqualTo(SimpleDateFormat.class);
    }

    @Test
    void generateQuotation_deadlocksWithCleanupBecauseLockOrderInverts(@TempDir Path tmp) throws Exception {
        // 场景5 特征化：清理路径 fileLock→templateLock，生成路径 templateLock→fileLock，顺序相反。
        //
        // 这里不靠"碰运气并发"，而是**卡住时序**，让它成为确定性复现：
        //   ① 先起清理线程，等它进到 fileLock 里（此时它在 sleep，故状态是 TIMED_WAITING）；
        //   ② 再起生成线程 —— 它拿得到 templateLock，但请求 fileLock 时被清理线程挡住；
        //   ③ 清理线程醒来去请求 templateLock，被生成线程挡住 → 互等，永久 BLOCKED。
        // 两个线程都是 daemon：死锁后留在那儿不影响测试进程退出。
        QuotationService svc = service(tmp, false);

        Thread cleanup = daemon("cleanup", svc::cleanExpiredFiles);
        cleanup.start();
        long deadline = System.currentTimeMillis() + 2000;
        while (cleanup.getState() != Thread.State.TIMED_WAITING && System.currentTimeMillis() < deadline) {
            Thread.sleep(1);
        }
        assertThat(cleanup.getState())
                .as("清理线程应已持有 fileLock 并在锁内 sleep")
                .isEqualTo(Thread.State.TIMED_WAITING);

        Thread generate = daemon("generate", () -> svc.generateQuotation("ORD-9001"));
        generate.start();
        Thread.sleep(500);

        assertThat(cleanup.getState()).isEqualTo(Thread.State.BLOCKED);
        assertThat(generate.getState()).isEqualTo(Thread.State.BLOCKED);
        // 卡在第二把锁上，连临时文件都还没创建
        assertThat(countOf(tmp, "quotation_")).isZero();
    }

    @Test
    void cleanExpiredFiles_deletesOnlyExpiredQuotationFiles(@TempDir Path tmp) throws Exception {
        QuotationService svc = service(tmp, false);
        // 一个过了期的报价单临时文件 + 一个场景1 的填盘文件（不该被清理任务碰）
        Path expired = Files.createFile(tmp.resolve("quotation_ORD-9_old.pdf"));
        Files.setLastModifiedTime(expired, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 11 * 60 * 1000L));
        Path leak = Files.createFile(tmp.resolve("leak_keep.bin"));

        svc.cleanExpiredFiles();

        assertThat(expired).doesNotExist();
        assertThat(leak).as("leak_*.bin 是场景1 的填盘证据，清理任务不许动").exists();
    }
}
