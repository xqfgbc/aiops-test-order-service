package com.company.order.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

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
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public void handle(Exception e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Spring MVC 自带的错误（缺必填参数 400 / 方法不支持 405 / 路径不存在 404 …）都实现
        // ErrorResponse：**保持原状态码，且不记 ERROR** —— 那是调用方问题、不是应用故障。
        // 记成 ERROR 会把客户端错误混进基于 ERROR 级别的日志监控。
        if (e instanceof ErrorResponse errorResponse) {
            sendErrorIfPossible(response, errorResponse.getStatusCode().value());
            return;
        }
        // 真正的未处理异常。此刻 TraceFilter 的 finally 还没跑，MDC 里的 traceId 仍在，
        // 日志因此带上它 —— 这是本类存在的全部理由。
        log.error("unhandled exception: {} {}", request.getMethod(), request.getRequestURI(), e);
        sendErrorIfPossible(response, HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    /**
     * 仅在响应尚未提交时写出错误。
     *
     * <p>修复（观测断层）：原实现在此处无条件 {@code sendError}。当响应流已经被下游写过
     * （{@code response.isCommitted()==true}）时，容器会抛
     * {@code IllegalStateException: Cannot call sendError() after the response has been committed}，
     * 该二次异常覆盖掉原始业务异常，使日志里只剩下与真实故障无关的
     * {@code IllegalStateException} 噪音（rca 指出的「日志侧仅剩 actuator 抓取断连噪音」），
     * 原始异常的 cause / traceId / 应用栈帧全部丢失，故障无法用日志定位。
     *
     * <p>因此先判 {@code isCommitted()}：已提交则不再写响应，原始异常已在上面完整记入日志
     * （含 traceId），直接返回即可；未提交时才按原逻辑写出对应状态码。
     */
    private void sendErrorIfPossible(HttpServletResponse response, int status) throws IOException {
        if (response.isCommitted()) {
            // 响应已提交，无法再改状态码/写错误体：只保留原始异常记录，不再二次抛出。
            return;
        }
        response.sendError(status);
    }
}
