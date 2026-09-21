package com.company.order.service;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tester 回归套件（fix-4/5/6）：
 * 复现 fix-implementer env_changes：quotation.min-free-bytes 通过反射注入（替代 @Value 默认值），
 * quotation.temp-leak 已废弃不再参与删除路径控制。
 */
public class QuotationServiceRegressionTest {

    private QuotationService newService(Path tempDir, long minFreeBytes) throws Exception {
        QuotationService svc = new QuotationService();
        setField(svc, "tempDir", tempDir.toString());
        setField(svc, "minFreeBytes", minFreeBytes);
        return svc;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = QuotationService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void generateQuotation_neverLeavesTempFile() throws Exception {
        Path dir = Files.createTempDirectory("tmp-quote-test");
        try {
            QuotationService svc = newService(dir, 0L);
            byte[] content = svc.generateQuotation("ORD-1");
            assertTrue(content.length > 0);
            assertEquals(0, dir.toFile().listFiles().length, "fix-4: finally 应无条件删除临时文件，目录不得残留");
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void generateQuotation_keepsDeleting_underLeakScenario() throws Exception {
        Path dir = Files.createTempDirectory("tmp-quote-leak");
        try {
            QuotationService svc = newService(dir, 0L);
            for (int i = 0; i < 5; i++) {
                svc.generateQuotation("ORD-" + i);
            }
            assertEquals(0, dir.toFile().listFiles().length, "fix-4: temp-leak 场景下仍应清理，/data/tmp 不增长");
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void generateQuotation_diskLow_throwsClearBusinessError() throws Exception {
        Path dir = Files.createTempDirectory("tmp-quote-full");
        try {
            // env_change: quotation.min-free-bytes 调大模拟磁盘不足（Long.MAX_VALUE 必然低于真实可用空间）
            QuotationService svc = newService(dir, Long.MAX_VALUE);
            QuotationException ex = assertThrows(QuotationException.class,
                    () -> svc.generateQuotation("ORD-1"));
            assertTrue(ex.getMessage().contains("磁盘空间不足"),
                    "fix-6: 磁盘满应抛明确业务错误，实际 message=" + ex.getMessage());
            assertEquals(0, dir.toFile().listFiles().length, "磁盘满降级时也不得残留临时文件");
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void generateQuotation_ioError_preservesRootCauseMessage() throws Exception {
        Path dir = Files.createTempDirectory("tmp-quote-cause");
        File file = null;
        try {
            // 只读目录（owner 去掉写权限）→ createTempFile 必然抛 IOException
            File roDir = Files.createTempDirectory("tmp-quote-ro").toFile();
            boolean ok = roDir.setWritable(false, true);
            org.junit.jupiter.api.Assumptions.assumeTrue(ok, "无法将目录设为只读，跳过");
            QuotationService svc = newService(roDir.toPath(), 0L);
            QuotationException ex = assertThrows(QuotationException.class,
                    () -> svc.generateQuotation("ORD-CAUSE"));
            assertTrue(ex.getMessage().contains("生成报价单失败"),
                    "fix-5: 异常应带前缀，实际 message=" + ex.getMessage());
            assertTrue(ex.getCause() instanceof java.io.IOException,
                    "fix-5: 应保留原始根因异常堆栈，实际 cause=" + ex.getCause());
        } finally {
            if (file != null) file.delete();
            Files.deleteIfExists(dir);
        }
    }
}
