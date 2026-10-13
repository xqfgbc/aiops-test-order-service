package com.company.order.service;

/**
 * 报价单模板缺失（场景3「未判空 NPE」的受控收敛）。
 *
 * <p>模板仓库/配置中心查不到订单对应模板时，原来 {@code loadTemplate} 直接返回 {@code null}，
 * 由 {@code quotationSummary} 对 {@code null} 调 {@code trim()} 抛 NPE；
 * 现在统一抛本异常：<b>自带 orderId 便于排障，且带明确业务错误码</b>，
 * 由 {@code GlobalExceptionHandler} 映射为可读的业务错误。
 */
public class QuotationTemplateMissingException extends QuotationException {

    /** 明确的业务错误码：模板缺失（区别于其它报价单业务错误）。 */
    public static final String ERROR_CODE = "QUOTATION_TEMPLATE_MISSING";

    /** 触发该异常的订单号 —— 排障时不依赖堆栈也能定位到具体单据。 */
    private final String orderId;

    public QuotationTemplateMissingException(String orderId) {
        super("报价单模板缺失: orderId=" + orderId, null);
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
