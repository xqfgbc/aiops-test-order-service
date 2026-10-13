package com.company.order.service;

/**
 * 报价单模板缺失：当模板仓库/配置中心查不到对应报价单模板（或返回 null）时抛出。
 *
 * <p>场景3 修复的一部分：原实现在 {@code quotationSummary} 里对 {@code loadTemplate} 的
 * null 返回值直接调用 {@code template.trim()} → NullPointerException（500）。
 * 现在失败被收敛为这个**可识别的受控业务异常**，并携带 orderId，便于排障与统一映射。
 */
public class QuotationTemplateMissingException extends RuntimeException {

    private final String orderId;

    public QuotationTemplateMissingException(String orderId) {
        super("报价单模板缺失 orderId=" + orderId);
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }
}
