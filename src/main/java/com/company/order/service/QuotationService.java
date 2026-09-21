package com.company.order.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 报价单生成（场景1 被测）。
 * 纯字节流写临时文件到 /data/tmp。
 *
 * 修复说明：
 * 原实现受 {@code quotation.temp-leak}（QUOTATION_TEMP_LEAK）开关控制，
 * 开启时 finally 分支不删除临时文件，反复调用会把 /data/tmp 所在数据盘写满，
 * 继而导致 java.io.IOException: No space left on device。
 * 现在无论开关取值，临时文件都在 finally 中**无条件**清理；
 * 同时启动时清理历史残留的报价单临时文件，帮助已写满的盘自动回收空间。
 */
@Service
public class QuotationService {

    private static final Logger log = LoggerFactory.getLogger(QuotationService.class);

    /** 历史残留报价单文件的保留时长，超过即可被启动清理回收。 */
    private static final long STALE_FILE_TTL_MS = 60L * 60L * 1000L;

    @Value("${quotation.temp-dir:/data/tmp}")
    private String tempDir;

    /**
     * 兼容旧配置的开关：保留字段以免环境变量/配置解析失败，但已不再影响清理逻辑
     * （历史上为 true 时不清理临时文件，是磁盘写满的根因）。
     */
    @Value("${quotation.temp-leak:false}")
    private boolean tempLeak;

    /**
     * 启动时回收上次异常退出/旧版本残留的报价单临时文件，避免历史泄漏继续占用磁盘。
     */
    @PostConstruct
    public void purgeStaleQuotationFiles() {
        File dir = new File(tempDir);
        if (!dir.isDirectory()) {
            return;
        }
        File[] stale = dir.listFiles((d, name) -> name.startsWith("quotation_") && name.endsWith(".pdf"));
        if (stale == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - STALE_FILE_TTL_MS;
        int removed = 0;
        for (File f : stale) {
            if (f.lastModified() < cutoff && f.delete()) {
                removed++;
            }
        }
        if (removed > 0) {
            log.warn("启动清理残留报价单临时文件 {} 个，目录={}", removed, tempDir);
        }
    }

    public byte[] generateQuotation(String orderId) {
        File dir = new File(tempDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        if (tempLeak) {
            // 旧开关已被忽略：不再允许“不清理”的行为，否则会写满数据盘
            log.warn("quotation.temp-leak=true 已被忽略，临时文件始终会被清理 orderId={}", orderId);
        }
        File tmp = null;
        try {
            tmp = File.createTempFile("quotation_" + orderId + "_", ".pdf", dir);
            byte[] content = ("Quotation for order " + orderId + " @ " + System.currentTimeMillis() + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content);
            }
            log.info("报价单生成成功 orderId={} file={}", orderId, tmp.getName());
            return content;
        } catch (IOException e) {
            log.error("生成报价单失败: {}", e.toString(), e);
            throw new QuotationException("生成报价单失败", e);
        } finally {
            // 修复点：无条件清理临时文件，杜绝磁盘被写满
            if (tmp != null && tmp.exists() && !tmp.delete()) {
                log.warn("临时文件清理失败，存在残留风险 file={}", tmp.getAbsolutePath());
            }
        }
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
