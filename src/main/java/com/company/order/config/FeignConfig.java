package com.company.order.config;

import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把当前 traceId 加到出站 Feign header，跨服务保持同一 traceId。
 */
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor traceIdInterceptor() {
        return template -> {
            String traceId = MDC.get(TraceFilter.TRACE_ID_MDC);
            if (traceId != null) {
                template.header(TraceFilter.TRACE_ID_HEADER, traceId);
            }
        };
    }
}
