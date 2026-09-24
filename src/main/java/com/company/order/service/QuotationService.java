package com.company.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 报价单生成与临时文件管理（场景1、4、5、6、7 的宿主）。
 *
 * <h3>本类里的注入点</h3>
 * <ul>
 *   <li><b>场景1</b> {@link #generateQuotation}：{@code quotation.temp-leak=true} 时 finally 不清理临时文件 → 磁盘写满。</li>
 *   <li><b>场景4</b> {@link #formatDeliveryDate}：{@code static} 共享的 {@link SimpleDateFormat}，并发下算错日期/抛异常。</li>
 *   <li><b>场景5</b> {@link #generateQuotation} 与 {@link #cleanExpiredFiles}：两把锁的获取顺序相反（ABBA）→ 死锁。</li>
 *   <li><b>场景6</b> {@link #generateQuotation}：参数校验失败（调用方问题）却记 ERROR → 误报洪水。</li>
 *   <li><b>场景7</b> {@link #renderItems}：循环里逐行打 INFO → 日志风暴。</li>
 * </ul>
 *
 * <p><b>并发型场景（4/5）单发请求永远不触发</b>，需要并发：见 {@code docs/POSTMAN_SCENARIOS.md}
 * 与 {@code POST /test/scenario/concurrent-burst}。场景5 一旦触发，服务整体卡死，恢复要重启进程。
 */
@Service
public class QuotationService {

    private static final Logger log = LoggerFactory.getLogger(QuotationService.class);

    /**
     * ⚠️ 场景4 bug 注入点：{@link SimpleDateFormat} 非线程安全（内部 Calendar/字段被 parse/format 改写），
     * 却被 {@code static} 共享给所有请求线程。并发下两种表现：
     * <ul>
     *   <li><b>抛异常</b>（最常见 {@code NumberFormatException}）→ 请求 500，堆栈顶层应用帧指向本类；</li>
     *   <li><b>静默算错</b>：不抛异常但日期错乱、接口照样 200 —— 只错数据不报错，比抛异常更难发现。</li>
     * </ul>
     * 正确写法是每次调用新建实例，或用 {@code DateTimeFormatter}（不可变、线程安全）。
     */
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * 明细行交付日期。**故意用常量**：不并发时 {@code parse→format} 的输出恒等于它，
     * 于是外部脚本（和 ES 里的 INFO 日志）一眼就能看出哪些响应被竞态算错了。
     */
    private static final String DELIVERY_DATE_RAW = "2026-01-01 00:00:00";

    /** 清理任务的间隔：**调大它会让场景5 更难触发**（窗口出现的频次变低）。 */
    private static final long CLEANUP_INTERVAL_MS = 2000;

    /**
     * 清理任务持有 fileLock 的时长。
     *
     * <p>⚠️ 这是场景5（死锁）的**窗口拉宽参数**：死锁成立的条件是「某个请求在清理任务持有
     * fileLock 期间去请求 fileLock」——清理任务持锁越久，撞上的概率越高。真实事故里这个窗口
     * 由目录规模/慢 I/O 提供（本方法也确实在锁内做真实的扫描 + 逐个 delete）；
     * 这里额外 sleep 一小段，是为了让窗口大到**持续并发能稳定复现**，
     * 参照场景1 用 {@code dd} + CPU 忙循环"强制"制造故障的既有做法。
     *
     * <p><b>为什么是 5ms 而不是几十毫秒</b>：单发只是"恰好撞上窗口"就会把整个服务打死，
     * 概率 ≈ (请求耗时 + 窗口) / 周期。取 50ms 时单发中招率约 4% —— 平台侧 §4 验证脚本里
     * 就有 {@code curl /quotation}，不该有这种概率。5ms 把它压到 ~1.5%，
     * 而**持续爆发（负载跨过一个完整周期）依然是必然触发**（窗口期内请求以亚毫秒间隔到达）。
     *
     * <p>调大到几十毫秒 → 更容易死锁，但无关场景也容易被误伤；
     * 调成 0 → 只剩真实扫描的窗口，死锁变得极难触发（适合只想看场景4 的时候）。
     *
     * <p>之所以做成可注入的字段而不是常量：单测要用 100ms 把时序钉死（见
     * {@code generateQuotation_deadlocksWithCleanupBecauseLockOrderInverts}），
     * 生产默认仍是 5ms。集群里也可以直接 {@code QUOTATION_CLEANUP_SCAN_HOLD_MS} 调。
     */
    @Value("${quotation.cleanup-scan-hold-ms:5}")
    private long cleanupScanHoldMs = 5;

    /** 只清理自己的文件：超过这个年龄的 {@code quotation_*} 才算过期。 */
    private static final long EXPIRED_AGE_MS = 10 * 60 * 1000L;

    /** 模板仓库（真实实现从配置中心/模板库加载；这里用常量代替）。 */
    private static final String ROW_TEMPLATE_DEFAULT = "  - {ITEM} | order={ORDER}";

    // ⚠️ 场景5 bug 注入点：两把锁，生成路径与清理路径的获取顺序相反 → ABBA 死锁。
    //   注意必须是**实例**字段（不是 static）：单测里每个 service 实例互不影响。
    /** 模板缓存锁（保护 {@link #rowTemplate}）。 */
    private final Object templateLock = new Object();
    /** 临时目录锁（保护 {@code /data/tmp} 下的写盘与清理）。 */
    private final Object fileLock = new Object();

    /** 报价单明细行模板（由 {@link #templateLock} 保护）。 */
    private volatile String rowTemplate = ROW_TEMPLATE_DEFAULT;

    @Value("${quotation.temp-dir:/data/tmp}")
    private String tempDir;

    // ⚠️ 场景1 bug 开关：QUOTATION_TEMP_LEAK=true 时泄漏临时文件（不清理）→ 磁盘写满
    @Value("${quotation.temp-leak:false}")
    private boolean tempLeak;

    /**
     * 场景7 的量级旋钮：每次请求渲染多少行明细 = 打多少条 INFO 日志。
     *
     * <p>500 行 = 单请求约 500 条 INFO（日志量突增）;同时也是场景4 竞态的**窗口来源**
     * （每行一次 parse+format，约几百微秒的在临界区时间）。调小它两个场景都会变弱。
     */
    @Value("${quotation.line-items:500}")
    private int lineItems;

    public byte[] generateQuotation(String orderId) {
        // ⚠️ 场景6 bug 注入点：「订单号格式非法」是**调用方的错**（本该 400、本不该记 ERROR），
        //   这里既记了 ERROR 又靠抛异常走了 500 —— 于是日志监控（signature_aggregate, min_count=5）
        //   会把调用方的脏参数当成应用故障，开出一堆本不该开的问题单。
        //   ⚠️ 消息必须是**常量**、不带任何插值：带 orderId 的话每条签名都不同，聚合不出 LogAnomaly，
        //   反而一条单也开不出来（同 LogGenerator#emitTransientBurst 的注释）。
        //   校验用 "ORD" 前缀而不是 "ORD-"：平台侧 load/验证脚本用的是 ORD001，收严会把正常流量全打成 500。
        if (orderId == null || !orderId.startsWith("ORD")) {
            log.error("报价单参数校验失败: 订单号格式非法");
            throw new IllegalArgumentException("非法订单号: " + orderId);
        }

        File dir = new File(tempDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // ⚠️ 场景4 + 场景7：**必须在锁外**。放进 templateLock 里会被串行化（并发度恒为 1），
        //   场景4 的竞态就永远不会出现、场景7 的日志也会变成串行输出。
        List<String> rows = renderItems(orderId);

        File tmp = null;
        try {
            byte[] content;
            synchronized (templateLock) {                        // 场景5-A：生成路径先拿**模板锁**
                content = composeQuotation(orderId, rows);
                synchronized (fileLock) {                        // 场景5-A：再拿**文件锁** ← ABBA 的一半
                    tmp = File.createTempFile("quotation_" + orderId + "_", ".pdf", dir);
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(content);
                    }
                }
            }
            log.info("报价单生成成功 orderId={} file={}", orderId, tmp.getName());
            return content;
        } catch (IOException e) {
            log.error("生成报价单失败: {}", e.toString(), e);
            throw new QuotationException("生成报价单失败", e);
        } finally {
            // ⚠️ 场景1 bug 注入点：tempLeak=true 时不删除临时文件，模拟「finally 未清理」
            if (tmp != null && tmp.exists() && !tempLeak) {
                tmp.delete();
            }
        }
    }

    /**
     * 逐行渲染报价单明细（⚠️ 场景4 与场景7 的注入点，调用方保证**不在锁内**）。
     *
     * @return 每行的 {@code sku|delivery} 文本，供 {@link #composeQuotation} 套模板
     */
    private List<String> renderItems(String orderId) {
        List<String> rows = new ArrayList<>(Math.max(0, lineItems));
        for (int i = 0; i < lineItems; i++) {
            String sku = skuOf(i);
            // ⚠️ 场景4：每行一次 parse→format，几百微秒的临界窗口 × 高并发 = 竞态
            String delivery = formatDeliveryDate(DELIVERY_DATE_RAW);
            // ⚠️ 场景7 bug 注入点：循环里逐行打 INFO —— 单请求数百条日志，
            //   日志量曲线陡增、ES 写入压力、同步 ConsoleAppender 还会直接拖慢请求。
            //   正确做法是汇总成一条（或降级到 DEBUG）
            log.info("报价单明细渲染 orderId={} no={} sku={} delivery={}", orderId, i, sku, delivery);
            rows.add(sku + "|" + delivery);
        }
        return rows;
    }

    /** ⚠️ 场景4 的竞态本体：共享 {@link #DATE_FMT} 上的 parse + format 往返。 */
    private String formatDeliveryDate(String raw) {
        try {
            return DATE_FMT.format(DATE_FMT.parse(raw));
        } catch (ParseException e) {
            // 常量输入不会走到这里；竞态真正抛的是 NumberFormatException（非受检，直接上抛 → 500）
            throw new QuotationException("明细交付日期解析失败", e);
        }
    }

    /**
     * 用模板渲染整张报价单（⚠️ 场景5-A 的前半段：本方法在 {@code templateLock} 内执行，
     * 返回后紧接着在**仍持有 templateLock** 的情况下请求 fileLock —— 死锁的一半在这里形成）。
     */
    private byte[] composeQuotation(String orderId, List<String> rows) {
        StringBuilder sb = new StringBuilder(256 + rows.size() * 64);
        sb.append("Quotation for order ").append(orderId)
                .append(" @ ").append(System.currentTimeMillis()).append('\n');
        for (String row : rows) {
            sb.append(rowTemplate.replace("{ITEM}", row).replace("{ORDER}", orderId)).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 定时清理过期报价单临时文件 + 刷新模板缓存（⚠️ 场景5-B：ABBA 的**另一半**）。
     *
     * <p>生成路径是 {@code templateLock → fileLock}，这里是 {@code fileLock → templateLock}，
     * 顺序正好相反 → 两条路径并发时互相等待，线程永久阻塞。
     *
     * <p>死锁成立的条件（也是触发菜谱的全部内容）：**某个请求在本次清理持有 fileLock 的窗口内
     * 去请求 fileLock** —— 该请求会阻塞并保持持有 templateLock，随后本任务请求 templateLock 即卡死。
     * 所以「并发爆发 + 清理周期」重叠就必然触发，不需要精确卡点。
     *
     * <p>恢复只能重启进程（锁无法从外部释放）。
     */
    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS)
    public void cleanExpiredFiles() {
        try {
            synchronized (fileLock) {                            // 场景5-B：清理路径先拿**文件锁**
                int deleted = scanAndDeleteExpired();
                // ⚠️ 注入用：把 fileLock 的持有时长拉到几毫秒，见 cleanupScanHoldMs 的说明
                Thread.sleep(Math.max(0, cleanupScanHoldMs));
                synchronized (templateLock) {                    // 场景5-B：再拿**模板锁** ← 顺序与生成路径相反
                    rowTemplate = loadRowTemplateFromRepo();
                }
                if (deleted > 0) {
                    log.info("清理过期报价单临时文件 {} 个", deleted);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // ⚠️ 本方法**绝不能外抛**：调度线程抛异常会被 Spring 记成 ERROR（Unexpected error occurred
            //   in scheduled task），凭空多出一个签名，污染基于 ERROR 的日志检测。
            log.warn("清理任务异常: {}", e.toString(), e);
        }
    }

    /**
     * 扫描并删除过期的 {@code quotation_*} 临时文件。
     *
     * <p>只匹配 {@code quotation_} 前缀：{@code leak_*.bin}（场景1 的填盘证据）不归它管，
     * 否则清理任务会把场景1 的证据删掉，两个场景互相拆台。
     */
    private int scanAndDeleteExpired() {
        File dir = new File(tempDir);
        if (!dir.exists()) {
            return 0;
        }
        File[] files = dir.listFiles((d, name) -> name.startsWith("quotation_"));
        if (files == null) {
            return 0;
        }
        long deadline = System.currentTimeMillis() - EXPIRED_AGE_MS;
        int deleted = 0;
        for (File f : files) {
            if (f.lastModified() < deadline && f.delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    /** 从模板仓库重新加载明细行模板（真实实现是一次远程/配置中心调用，这里用常量代替）。 */
    private String loadRowTemplateFromRepo() {
        return ROW_TEMPLATE_DEFAULT;
    }

    private String skuOf(int i) {
        return String.format("SKU-%05d", i);
    }

    /** 报价单摘要（场景3 被测）。 */
    public String quotationSummary(String orderId) {
        String template = loadTemplate(orderId);
        log.info("渲染报价单摘要 orderId={}", orderId);
        // 防御性判空：模板缺失是受控业务异常，绝不能把 null 继续传给调用方（历史缺陷：此处 NPE）
        if (template == null) {
            throw new QuotationException("报价单模板缺失 orderId=" + orderId, null);
        }
        return template.trim();
    }

    /**
     * 模拟模板仓库/配置中心查询。
     *
     * <p>修复：模板查不到时不再「打 WARN 后静默 return null」——异常被吞成 null 会让
     * 调用方在无感知的情况下 NPE（见 quotationSummary）。这里改为记录 ERROR（带模板标识
     * 上下文）并抛出受控业务异常，使失败在调用链上可见、语义明确。
     */
    private String loadTemplate(String orderId) {
        log.error("报价单模板加载失败 orderId={}", orderId);
        throw new QuotationException("报价单模板加载失败 orderId=" + orderId, null);
    }

    /** 供 /test/leak 快速填盘 */
    public int leakFiles(int count, int sizeMb) throws IOException {
        File dir = new File(tempDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        byte[] chunk = new byte[sizeMb * 1024 * 1024];
        int created = 0;
        for (int i = 0; i < count; i++) {
            File f = new File(dir, "leak_" + UUID.randomUUID() + ".bin");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(chunk);
            }
            created++;
        }
        log.warn("leakFiles 写入 {} 个文件（各 {}MB）", created, sizeMb);
        return created;
    }

}
