package com.company.order.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuotationService 单元测试（场景1 的被测服务）。
 *
 * 不起 Spring 上下文：{@code @Value} 字段直接注，测的是**类自己的行为**，
 * 毫秒级完成。要测装配/HTTP 层应另开 {@code @SpringBootTest}。
 */
class QuotationServiceTest {

    private static QuotationService service(Path tempDir) {
        QuotationService svc = new QuotationService();
        ReflectionTestUtils.setField(svc, "tempDir", tempDir.toString());
        return svc;
    }

    /** 模拟旧环境仍把 QUOTATION_TEMP_LEAK 置为 true 的情况。 */
    private static QuotationService legacyLeakyService(Path tempDir) {
        QuotationService svc = service(tempDir);
        ReflectionTestUtils.setField(svc, "tempLeak", true);
        return svc;
    }

    private static long countOf(Path dir, String prefix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        }
    }

    @Test
    void generateQuotation_returnsContentMentioningTheOrder(@TempDir Path tmp) {
        byte[] pdf = service(tmp).generateQuotation("ORD-1001");

        assertThat(new String(pdf, StandardCharsets.UTF_8)).contains("ORD-1001");
    }

    @Test
    void generateQuotation_cleansUpItsTempFileByDefault(@TempDir Path tmp) throws IOException {
        service(tmp).generateQuotation("ORD-1002");

        // 正常路径：finally 里删掉临时文件，目录不该留东西
        assertThat(countOf(tmp, "quotation_")).isZero();
    }

    @Test
    void generateQuotation_cleansUpItsTempFileEvenWhenLegacyLeakSwitchIsOn(@TempDir Path tmp) throws IOException {
        // 修复后：即使环境里仍残留 QUOTATION_TEMP_LEAK=true，也不允许不清理，
        // 否则反复调用会写满数据盘并导致 No space left on device。
        legacyLeakyService(tmp).generateQuotation("ORD-1003");

        assertThat(countOf(tmp, "quotation_")).isZero();
    }

    @Test
    void generateQuotation_cleansUpTempFileAfterRepeatedCalls(@TempDir Path tmp) throws IOException {
        QuotationService svc = legacyLeakyService(tmp);
        for (int i = 0; i < 50; i++) {
            svc.generateQuotation("ORD-" + i);
        }

        assertThat(countOf(tmp, "quotation_")).isZero();
    }

    @Test
    void generateQuotation_createsTempDirWhenMissing(@TempDir Path tmp) {
        Path nested = tmp.resolve("not/created/yet");
        assertThat(nested).doesNotExist();

        service(nested).generateQuotation("ORD-1004");

        assertThat(nested).exists();
    }

    @Test
    void leakFiles_createsRequestedNumberOfFiles(@TempDir Path tmp) throws IOException {
        int created = service(tmp).leakFiles(3, 1);

        assertThat(created).isEqualTo(3);
        assertThat(countOf(tmp, "leak_")).isEqualTo(3);
        // 与报价单是两套文件，别互相混淆
        assertThat(countOf(tmp, "quotation_")).isZero();
    }
}
