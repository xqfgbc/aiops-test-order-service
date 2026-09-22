package com.company.order.service;

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
 */
@Service
public class QuotationService {

    private static final Logger log = LoggerFactory.getLogger(QuotationService.class);

    @Value("${quotation.temp-dir:/data/tmp}")
    private String tempDir;

    // 保留该字段以兼容既有配置/装配（如 QUOTATION_TEMP_LEAK），
    // 但临时文件清理已改为无条件执行：写盘路径不再产生残留文件，
    // 任何取值都不可能再泄漏临时文件把 /data 卷写满。
    @Value("${quotation.temp-leak:false}")
    @Deprecated
    private boolean tempLeak;

    public byte[] generateQuotation(String orderId) {
        File dir = new File(tempDir);
        if (!dir.exists()) {
            dir.mkdirs();
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
            // 修复：写盘路径（无论开关取值/成功失败分支）都必须清理临时文件，
            // 避免残留文件把 memory-backed EmptyDir /data 写满后触发 ENOSPC。
            if (tmp != null && tmp.exists()) {
                if (!tmp.delete()) {
                    log.warn("临时报价单文件删除失败，可能存在残留 file={}", tmp.getAbsolutePath());
                }
            }
        }
    }

    /** 报价单摘要（场景3 被测）。 */
    public String quotationSummary(String orderId) {
        String template = loadTemplate(orderId);
        log.info("渲染报价单摘要 orderId={}", orderId);
        // ⚠️ 场景3 bug 注入点：模板加载失败返回 null，这里未判空直接调用 → NullPointerException
        return template.trim();
    }

    /** 模拟模板仓库/配置中心查询：查不到时返回 null（不做兜底） */
    private String loadTemplate(String orderId) {
        log.warn("报价单模板加载失败，返回 null orderId={}", orderId);
        return null;
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
