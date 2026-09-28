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
 *   <tr><td>3 模板缺失（<b>已修复</b>）</td><td>{@code GET /quotation/exception}</td>
 *       <td>500 + 受控 {@code QuotationException}（消息「报价单模板缺失」）的 ERROR 日志；<b>不再是 NPE 堆栈</b></td></tr>
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
     * 报价单摘要（场景3 被测，<b>已修复</b>）：{@code loadTemplate} 查不到模板时返回 null，
     * service 侧**判空**后抛受控 {@code QuotationException}（消息「报价单模板缺失」），
     * 由 {@code GlobalExceptionHandler} 记 ERROR（带 orderId/traceId）后返回 500。
     *
     * <p>⚠️ 状态码与修复前**完全一致**（都是 500），没有任何 HTTP 状态码能鉴别修复与否 ——
     * 判修复要看日志：应出现带 orderId 的「报价单模板缺失」ERROR，
     * <b>不再有</b> {@code QuotationService.quotationSummary} 的 {@code NullPointerException} 堆栈。
     */
    @GetMapping("/quotation/exception")
    public Map<String, Object> exception(@RequestParam String orderId) {
        String summary = quotationService.quotationSummary(orderId);
        return Map.of("orderId", orderId, "summary", summary);
    }
    
}
