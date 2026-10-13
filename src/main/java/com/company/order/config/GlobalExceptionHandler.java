package com.company.order.config;

import com.company.order.service.InvalidOrderIdException;
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
 * 未处理异常的日志落点 —— 让 ERROR 日志带上 traceId；同时把「调用方问题」与「应用故障」区分开。
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
 * <h3>客户端错误 vs 应用故障（场景6 修复）</h3>
 * 参数校验类失败（非法订单号、非法入参）是**调用方的错**，不再落入未捕获异常分支记 ERROR / 返回 500，
 * 而是映射为 HTTP 400 + 结构化错误体，并以 <b>WARN</b> 记录 —— 不污染基于 ERROR 的日志监控，
 * 也不计入服务端 5xx 指标与 5xx 告警。Spring MVC 自带的错误（缺必填参数 400 / 方法不支持 405 /
 * 路径不存在 404 / {@code MethodArgumentNotValidException} …）都实现 {@link ErrorResponse}，
 * 沿用原状态码且不记 ERROR。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 非法订单号（{@link InvalidOrderIdException}）→ 400 + 结构化错误体，WARN 日志。
     *
     * <p>这是「参数不合法」的显式语义：不再抛裸 {@code IllegalArgumentException} 走 500，
     * 日志也不再是 ERROR/unhandled。响应体带 {@code code}/{@code message}/{@code reason}/{@code field}/{@code orderId}
     * 便于调用方与接口级断言定位。
     */
    @ExceptionHandler(InvalidOrderIdException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidOrderId(InvalidOrderIdException e,
                                                                    HttpServletRequest request) {
        log.warn("参数校验失败: {} {} orderId={} reason={}",
                request.getMethod(), request.getRequestURI(), e.getOrderId(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INVALID_ORDER_ID");
        body.put("message", "订单号格式非法");
        body.put("reason", e.getMessage());
        body.put("field", "orderId");
        body.put("orderId", e.getOrderId());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 兜底：任何以 {@link IllegalArgumentException} 表达的参数错误都归为客户端错误 400，
     * 而不是落进下面的 Exception 兜底分支被当成未处理异常记 ERROR / 返回 500。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e,
                                                                     HttpServletRequest request) {
        log.warn("参数校验失败: {} {} message={}",
                request.getMethod(), request.getRequestURI(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INVALID_ARGUMENT");
        body.put("message", e.getMessage() == null ? "请求参数非法" : e.getMessage());
        return ResponseEntity.badRequest().body(body);
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
