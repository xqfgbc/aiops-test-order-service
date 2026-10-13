package com.company.order.service;

/**
 * 客户端参数错误：订单号格式非法（为空、非法前缀、超长）。
 *
 * <p>这是**调用方的错**，不是应用故障。它与历史实现里裸抛的 {@link IllegalArgumentException}
 * 的区别在于语义明确：全局异常处理器据此把「参数不合法」映射为 HTTP 400 + 结构化错误体，
 * 并以 WARN 记录日志 —— 不再被当成未处理异常记 ERROR / 返回 500，也就不会污染
 * 基于 ERROR 的日志监控与 5xx 错误率指标。
 */
public class InvalidOrderIdException extends RuntimeException {

    private final String orderId;

    public InvalidOrderIdException(String orderId, String reason) {
        super(reason);
        this.orderId = orderId;
    }

    /** 触发校验失败的原始订单号（可能为 null），便于排障与接口级断言。 */
    public String getOrderId() {
        return orderId;
    }
}
