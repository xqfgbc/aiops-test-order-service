package com.company.order.config;

import com.company.order.service.QuotationTemplateMissingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 未处理异常的日志落点 —— 让 ERROR 日志带上 traceId。
 *
 * <h3>为什么需要这个类</h3>
 * 异常如果不在这里接住，会穿过整个 filter chain 抛到 Tomcat，由 {@code StandardWrapperValve}
 * 打出 {@code Servlet.service() for servlet [dispatcherServlet] ... threw exception}。
 * 而 {@link TraceFilter} 在 {@code finally} 里就 {@code MDC.remove(traceId)} 了 —— 那条日志
 * 落盘时 MDC 已经空了，所以**框架兜底打的 ERROR 天生没有 traceId**。
 *
 * <p>执行顺序（本类生效的位置正好夹在 MDC 的进出之间）：
 * <pre>
 * Tomcat
 *  └─ FilterChain
 *      ├─ TraceFilter: MDC.put(traceId)
 *      │   └─ DispatcherServlet
 *      │       └─ 本类 @ExceptionHandler  ← 在这里记日志，traceId 还在
 *      │   └─ finally: MDC.remove(traceId)
 *      └─ (未接住时) StandardWrapperValve 打 ERROR ← traceId 已空
 * </pre>
 *
 * <p>同时它避免了一条重复日志：异常被接住后不再上抛，Tomcat 那条没有 traceId 的
 * {@code Servlet.service()} ERROR 就不会产生。否则同一次故障会有两条签名不同的 ERROR
 * （一条带 traceId 一条不带），在按签名分组的日志检测里会裂成两个问题单。
 *
 * <p>响应体不在这里拼：统一走 {@code sendError} 交回容器的 {@code /error}
 * （{@code BasicErrorController}），保证与改动前**完全一致**。
 *
 * <p>例外：可识别的受控业务异常（如 {@link QuotationTemplateMissingException}）走下面的
 * 专用分支，返回明确的错误码 + 提示（4xx），不再落成 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 报价单模板缺失：受控业务错误（调用方拿不到摘要，属可预期的业务状态），
     * 映射为 404 + 明确错误码/提示，避免落入 500（以及原先的裸 NPE 堆栈）。
     *
     * <p>注意：这里**不**记 ERROR 级别 —— 具体失败点已在 {@code QuotationService} 记带
     * orderId 的 ERROR，此处再记会多出一个签名、污染基于 ERROR 的日志检测。
     */
    @ExceptionHandler(QuotationTemplateMissingException.class)
    public ResponseEntity<Map<String, Object>> handleQuotationTemplateMissing(
            QuotationTemplateMissingException e, HttpServletRequest request) {
        log.warn("报价单模板缺失 {} {} orderId={}", request.getMethod(), request.getRequestURI(), e.getOrderId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "QUOTATION_TEMPLATE_MISSING");
        body.put("message", "报价单模板缺失，无法生成报价单摘要");
        body.put("orderId", e.getOrderId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(Exception.class)
    public void handle(Exception e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Spring MVC 自带的错误（缺必填参数 400 / 方法不支持 405 / 路径不存在 404 …）都实现
        // ErrorResponse：**保持原状态码，且不记 ERROR** —— 那是调用方问题、不是应用故障。
        // 记成 ERROR 会把客户端错误混进基于 ERROR 级别的日志监控。
        if (e instanceof ErrorResponse errorResponse) {
            response.sendError(errorResponse.getStatusCode().value());
            return;
        }
        // 真正的未处理异常。此刻 TraceFilter 的 finally 还没跑，MDC 里的 traceId 仍在，
        // 日志因此带上它 —— 这是本类存在的全部理由。
        log.error("unhandled exception: {} {}", request.getMethod(), request.getRequestURI(), e);
        response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }
}
