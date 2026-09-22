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

    // ⚠️ 场景1 bug 开关：QUOTATION_TEMP_LEAK=true 时泄漏临时文件（不清理）→ 磁盘写满
    @Value("${quotation.temp-leak:false}")
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
            // ⚠️ bug 注入点：tempLeak=true 时不删除临时文件，模拟「finally 未清理」
            if (tmp != null && tmp.exists() && !tempLeak) {
                tmp.delete();
            }
        }
    }

    /** 报价单摘要（场景3 被测）。 */
    public String quotationSummary(String orderId) {
        String template = loadTemplate(orderId);
        log.info("渲染报价单摘要 orderId={}", orderId);
        // 场景3 修复：loadTemplate 模板加载失败时返回 null，这里必须先判空，
        // 否则 template.trim() 会直接抛 NullPointerException。
        // template == null 时改抛受控业务异常，失败语义明确且不再刷 NPE 堆栈。
        if (template == null) {
            throw new QuotationException("报价单模板缺失，无法生成摘要 orderId=" + orderId, null);
        }
        return template.trim();
    }

    /**
     * 模拟模板仓库/配置中心查询：查不到模板时不再「仅打 WARN 后 return null」静默吞掉失败，
     * 改为抛受控业务异常，使模板缺失在调用链上可见、语义明确。
     */
    private String loadTemplate(String orderId) {
        log.warn("报价单模板加载失败 orderId={}", orderId);
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
