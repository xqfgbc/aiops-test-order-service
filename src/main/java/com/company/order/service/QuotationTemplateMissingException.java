package com.company.order.service;

/**
 * 报价单模板缺失（加载失败）时抛出的**受控业务异常**。
 *
 * <h3>它取代了什么</h3>
 * 原实现里 {@code loadTemplate(orderId)} 查不到模板时返回 {@code null}，
 * 调用方 {@link QuotationService#quotationSummary(String)} 未判空直接 {@code template.trim()}，
 * 于是抛 {@code NullPointerException} —— 未受控、堆栈顶层指向业务代码、
 * 客户端只能拿到一个没有语义的 500，排障时还得自己从堆栈里认订单。
 *
 * <h3>现在的契约</h3>
 * null 不再外泄：模板缺失一律以本异常上抛，**异常自带 orderId 与业务错误码**，
 * 日志（{@code code=... orderId=...}）与响应错误码都能直接定位是哪张单出了什么问题。
 */
public class QuotationTemplateMissingException extends QuotationException {

    /** 业务错误码：报价单模板缺失。客户端/日志据此区分"业务失败"与"应用崩溃"。 */
    public static final String CODE = "QUOTATION_TEMPLATE_MISSING";

    /** 触发本次失败的订单号，便于排障时直接定位。 */
    private final String orderId;

    public QuotationTemplateMissingException(String orderId, Throwable cause) {
        super("报价单模板缺失 code=" + CODE + " orderId=" + orderId, cause);
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getCode() {
        return CODE;
    }
}
