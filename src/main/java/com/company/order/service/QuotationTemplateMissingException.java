package com.company.order.service;

/**
 * 报价单模板缺失（场景3 的受控业务异常）。
 *
 * <p>模板仓库/配置中心查不到模板时，{@code loadTemplate} 不再返回 {@code null}（那会让调用方
 * {@code template.trim()} 抛 {@link NullPointerException} → 500 裸栈），而是抛出本异常。
 * 异常携带 {@code orderId}，便于排障与接口层映射成可读的业务错误码。
 */
public class QuotationTemplateMissingException extends RuntimeException {

    private final String orderId;

    public QuotationTemplateMissingException(String orderId) {
        super("报价单模板缺失: orderId=" + orderId);
        this.orderId = orderId;
    }

    public QuotationTemplateMissingException(String orderId, Throwable cause) {
        super("报价单模板缺失: orderId=" + orderId, cause);
        this.orderId = orderId;
    }

    /** 触发模板缺失的订单号（可能为 null，取决于入参）。 */
    public String getOrderId() {
        return orderId;
    }
}
