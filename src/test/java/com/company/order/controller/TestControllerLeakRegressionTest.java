package com.company.order.controller;

import com.company.order.LogGenerator;
import com.company.order.service.QuotationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tester 回归（fix-3）：/test/leak 默认下线；需 test.leak-enabled=true + test.leak-token + X-Internal-Token 匹配。
 * env_change 复现：test.leak-enabled（默认 false）、test.leak-token（默认空）。
 */
public class TestControllerLeakRegressionTest {

    private TestController newController(boolean leakEnabled, String leakToken,
                                        QuotationService quoteSvc) throws Exception {
        TestController c = new TestController(new LogGenerator(), quoteSvc);
        Field f = TestController.class.getDeclaredField("leakEnabled");
        f.setAccessible(true);
        f.set(c, leakEnabled);
        Field t = TestController.class.getDeclaredField("leakToken");
        t.setAccessible(true);
        t.set(c, leakToken);
        return c;
    }

    @Test
    void leak_defaultDisabled_rejectsEveryRequest() throws Exception {
        // env_change: test.leak-enabled 默认 false → 未启用即拒绝（无 NPE）
        TestController c = newController(false, "", null);
        ResponseEntity<Map<String, Object>> resp = c.leak(1, 1, null);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(), "默认未启用应 403");
        assertEquals("forbidden", resp.getBody().get("status"));
    }

    @Test
    void leak_enabledButWrongOrMissingToken_rejects() throws Exception {
        TestController c = newController(true, "s3cret", null);
        assertEquals(HttpStatus.FORBIDDEN, c.leak(1, 1, null).getStatusCode(), "未带 token 应 403");
        assertEquals(HttpStatus.FORBIDDEN, c.leak(1, 1, "wrong").getStatusCode(), "token 不符应 403");
    }

    @Test
    void leak_enabledAndTokenMatches_allowsFill() throws Exception {
        // 覆写 leakFiles 避免测试真正写盘到 /data/tmp（fix-3 只管鉴权分支，放行即达标）
        QuotationService quoteSvc = new QuotationService() {
            @Override
            public int leakFiles(int count, int sizeMb) {
                return count;
            }
        };
        TestController c = newController(true, "s3cret", quoteSvc);
        ResponseEntity<Map<String, Object>> resp = c.leak(1, 1, "s3cret");
        assertTrue(resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "token 匹配时不应再被 403 拦截（实际 " + resp.getStatusCode() + "）");
        assertEquals("leaked", resp.getBody().get("status"));
    }
}
