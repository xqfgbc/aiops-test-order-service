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
        // 修复点：模板加载失败不再以 null 表达失败，这里保留判空守卫做防御，
        // 一旦真出现 null 也抛受控业务异常，绝不把失败退化成 NullPointerException。
        if (template == null) {
            log.error("报价单模板缺失，无法渲染摘要 orderId={}", orderId);
            throw new QuotationTemplateNotFoundException("报价单模板缺失，无法渲染摘要 orderId=" + orderId);
        }
        log.info("渲染报价单摘要 orderId={}", orderId);
        return template.trim();
    }

    /**
     * 模拟模板仓库/配置中心查询。
     * 修复点：查不到时不再「只打 WARN 然后 return null」把异常吞掉，
     * 而是抛受控业务异常，让失败在调用链上可见且语义明确。
     */
    private String loadTemplate(String orderId) {
        String template = queryTemplateRepository(orderId);
        if (template == null || template.isBlank()) {
            log.error("报价单模板加载失败，未命中模板 orderId={}", orderId);
            throw new QuotationTemplateNotFoundException("报价单模板加载失败 orderId=" + orderId);
        }
        return template;
    }

    /** 真实实现：模板仓库/配置中心查询，未命中时返回 null。 */
    private String queryTemplateRepository(String orderId) {
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
