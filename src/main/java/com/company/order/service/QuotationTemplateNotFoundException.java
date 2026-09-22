package com.company.order.service;

/**
 * 报价单模板缺失/加载失败（受控业务异常）。
 *
 * <p>继承既有业务异常基类 {@link QuotationException}：模板加载失败是「业务上拿不到模板」，
 * 而不是代码缺陷。抛出它替代原先的 {@code NullPointerException}，
 * 让上层与日志系统能以此识别受控失败，而不是把它当成未处理异常。
 */
public class QuotationTemplateNotFoundException extends QuotationException {

    public QuotationTemplateNotFoundException(String message) {
        super(message, null);
    }

    public QuotationTemplateNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
