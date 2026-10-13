package com.company.order.controller;

import com.company.order.config.GlobalExceptionHandler;
import com.company.order.service.QuotationService;
import com.company.order.service.QuotationTemplateMissingException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 场景3 的接口级回归锁（{@code GET /quotation/exception}）。
 *
 * <p>单测 {@code QuotationServiceTest} 已经钉住"模板缺失 → 受控异常"这一层；
 * 这里钉住**再往外一层**的契约：异常经 {@link GlobalExceptionHandler} 映射后，
 * 客户端拿到的是明确的业务错误（HTTP 404 + {@code QUOTATION_TEMPLATE_MISSING} +
 * orderId），而不是未捕获 NPE 造成的 500 / 错误页。
 *
 * <p>不起 Spring 上下文（standalone MockMvc + 真实的 {@link GlobalExceptionHandler}）：
 * 只测本控制器与异常映射的接线，毫秒级完成，且不受 Feign/调度任务等装配影响。
 */
class QuotationControllerTest {

    private static MockMvc mvc(QuotationService service) {
        return MockMvcBuilders.standaloneSetup(new QuotationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void exception_mapsTemplateMissingToBusinessErrorInsteadOfServerError() throws Exception {
        QuotationService service = mock(QuotationService.class);
        when(service.quotationSummary("ORD-001"))
                .thenThrow(new QuotationTemplateMissingException("ORD-001"));

        mvc(service).perform(get("/quotation/exception").param("orderId", "ORD-001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUOTATION_TEMPLATE_MISSING"))
                .andExpect(jsonPath("$.orderId").value("ORD-001"));
    }

    @Test
    void exception_returnsSummaryWhenTemplatePresent() throws Exception {
        QuotationService service = mock(QuotationService.class);
        when(service.quotationSummary("ORD-001")).thenReturn("- {ITEM}");

        mvc(service).perform(get("/quotation/exception").param("orderId", "ORD-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ORD-001"))
                .andExpect(jsonPath("$.summary").value("- {ITEM}"));
    }
}
