package com.company.order.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.function.Supplier;

/**
 * 暴露 /data 数据盘用量（场景1 的「磁盘 100%」可观测性）。
 * minikube hostPath PVC 无法通过 cadvisor 采到磁盘用量，故用 Micrometer 自定义 gauge 补齐。
 */
@Component
public class DiskMetrics {

    private final File dataDir;

    public DiskMetrics(MeterRegistry registry,
                       @Value("${quotation.temp-dir:/data/tmp}") String tempDir) {
        File dir = new File(tempDir).getParentFile();
        this.dataDir = (dir != null && dir.exists()) ? dir : new File("/");

        Gauge.builder("data_disk_total_bytes", (Supplier<Number>) this::totalBytes)
                .description("数据盘总容量（bytes）")
                .register(registry);
        Gauge.builder("data_disk_free_bytes", (Supplier<Number>) this::freeBytes)
                .description("数据盘可用空间（bytes）")
                .register(registry);
    }

    private Number totalBytes() {
        return dataDir.getTotalSpace();
    }

    private Number freeBytes() {
        return dataDir.getUsableSpace();
    }
}
