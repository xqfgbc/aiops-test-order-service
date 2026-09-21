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

    // 磁盘可用空间下限（bytes）：低于则拒绝生成，避免 createTempFile 抛 IOException
    @Value("${quotation.min-free-bytes:10485760}")
    private long minFreeBytes;

    public byte[] generateQuotation(String orderId) {
        File dir = new File(tempDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new QuotationException("临时目录不可用: " + tempDir);
        }
        // fix-6：创建临时文件前检查可用空间，磁盘满时优雅降级返回明确业务错误
        if (dir.getUsableSpace() < minFreeBytes) {
            throw new QuotationException("磁盘空间不足（可用 " + dir.getUsableSpace()
                    + " bytes，低于阈值 " + minFreeBytes + " bytes），无法生成报价单");
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
            // fix-5：保留原始根因消息与堆栈，便于后续排查
            throw new QuotationException("生成报价单失败: " + e.getMessage(), e);
        } finally {
            // fix-4：无条件删除临时文件（不受 temp-leak 开关影响），杜绝 /data/tmp 泄漏
            if (tmp != null && tmp.exists()) {
                tmp.delete();
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
