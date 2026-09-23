package com.company.order.controller;

import com.company.order.service.QuotationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 报价单接口。**本流程上叠了下述故障场景**（注入点都在 {@link QuotationService}，本类只是入口）：
 *
 * <table border="1">
 *   <caption>场景清单</caption>
 *   <tr><th>场景</th><th>入口</th><th>症状</th></tr>
 *   <tr><td>1 磁盘写满</td><td>{@code GET /quotation}（{@code QUOTATION_TEMP_LEAK=true} 时泄漏临时文件）</td>
 *       <td>500 + {@code No space left on device}</td></tr>
 *   <tr><td>3 未判空 NPE</td><td>{@code GET /quotation/exception}</td>
 *       <td>500 + {@code quotationSummary} 的 NPE 堆栈</td></tr>
 *   <tr><td>4 SDF 竞态</td><td>{@code GET /quotation} <b>并发</b></td>
 *       <td>偶发 500（NumberFormatException）或返回体里日期错乱</td></tr>
 *   <tr><td>5 ABBA 死锁</td><td>{@code GET /quotation} <b>持续并发</b> + 清理任务</td>
 *       <td>请求全部无响应，<b>日志里没有 ERROR</b>，要重启进程</td></tr>
 *   <tr><td>6 日志误报</td><td>{@code GET /quotation?orderId=非ORD开头}</td>
 *       <td>500 + 常量 ERROR 签名（调用方问题被记成应用故障）</td></tr>
 *   <tr><td>7 日志风暴</td><td>{@code GET /quotation}</td>
 *       <td>200，但单请求数百条 INFO：日志量突增、吞吐下降</td></tr>
 * </table>
 *
 * <p>并发型（4/5）单发请求永不触发，用 {@code POST /test/scenario/concurrent-burst}。
 * 完整的触发/判读步骤见 {@code docs/POSTMAN_SCENARIOS.md}。
 */
@RestController
public class QuotationController {

    private final QuotationService quotationService;

    public QuotationController(QuotationService quotationService) {
        this.quotationService = quotationService;
    }

    @GetMapping("/quotation")
    public ResponseEntity<byte[]> quotation(@RequestParam String orderId) {
        byte[] content = quotationService.generateQuotation(orderId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=quotation_" + orderId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(content);
    }

    /**
     * 报价单摘要（场景3 被测）：service 内模板为 null，未判空 → NullPointerException。
     * 期望表现：HTTP 500，日志中可见 QuotationService.quotationSummary 的 NPE 堆栈。
     */
    @GetMapping("/quotation/exception")
    public Map<String, Object> exception(@RequestParam String orderId) {
        String summary = quotationService.quotationSummary(orderId);
        return Map.of("orderId", orderId, "summary", summary);
    }
    
}
