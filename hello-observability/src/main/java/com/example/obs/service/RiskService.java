package com.example.obs.service;

import io.micrometer.core.annotation.Timed;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * 风控服务：演示 {@code @WithSpan} + {@code @Timed} 打在同一方法上。
 *
 * <p>两个注解由两套彼此独立的切面消费：
 * <ul>
 *   <li>{@code @WithSpan} —— 由 agent 的 annotations 插桩织入，产出 OTel Span（进 Jaeger）；</li>
 *   <li>{@code @Timed} —— 由 Micrometer 的 TimedAspect 织入，产出 Timer（进 Prometheus）。</li>
 * </ul>
 * 这就是"agent 方案 + Micrometer 指标"并存的真实形态：各写各的注解，互不感知。
 */
@Service
public class RiskService {

    @WithSpan("risk.check")
    @Timed(value = "risk.check", percentiles = {0.5, 0.95, 0.99}, histogram = true)
    public Decision check(@SpanAttribute("order.sku") String sku, @SpanAttribute("order.qty") int qty) {
        sleep(ThreadLocalRandom.current().nextInt(20, 60));
        // 单笔超过 50 件走人工审核：返回拒绝，业务层据此抛异常，链路里会看到 ERROR 状态
        if (qty > 50) {
            return new Decision(false, "qty over single-order limit");
        }
        return new Decision(true, "ok");
    }

    public record Decision(boolean approved, String remark) {
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
