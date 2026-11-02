package com.example.obs.config;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.trace.Span;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 指标 → 链路 的唯一自动化桥梁：<b>Exemplar</b>。
 *
 * <p>原理：Prometheus 采集样本时可以附带一个"示例"（exemplar），内容是
 * {@code {trace_id="..."} 观测值 @时间戳}。这样在 Prometheus/Grafana 里看到一个慢的直方图桶，
 * 点开 exemplar 就能直接跳到 Jaeger 里的那条 trace —— 从"指标异常"一步跨到"具体请求"。
 *
 * <p>落地点很小：Spring Boot 4 的 {@code PrometheusMetricsExportAutoConfiguration} 会
 * {@code ObjectProvider<io.prometheus.metrics.tracer.common.SpanContext>} 取这个 bean，
 * 有就塞进 {@code PrometheusMeterRegistry} 的构造器。所以<b>只需要声明一个 SpanContext，
 * 千万不要自己 new PrometheusMeterRegistry</b> —— 那样会绕开 Boot 的 PrometheusRegistry bean，
 * 结果就是 {@code /actuator/prometheus} 返回 200 但内容为空（本项目实测踩过）。
 *
 * <p>本工程没有 Micrometer Tracing（链路是 javaagent 给的），因此用 OTel API 自己实现这个回调。
 *
 * <p><b>exemplar 只在 OpenMetrics 格式里输出</b>，验证方式见 README §4。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(PrometheusMeterRegistry.class)
public class PrometheusExemplarConfig {

    @Bean
    public io.prometheus.metrics.tracer.common.SpanContext exemplarSpanContext() {
        return new OtelSpanContext();
    }

    /** 把 Prometheus 客户端的"取当前上下文"回调，接到 OTel 的 ThreadLocal 上下文上 */
    static final class OtelSpanContext implements io.prometheus.metrics.tracer.common.SpanContext {

        @Override
        public String getCurrentTraceId() {
            io.opentelemetry.api.trace.SpanContext ctx = Span.current().getSpanContext();
            return ctx.isValid() ? ctx.getTraceId() : null;
        }

        @Override
        public String getCurrentSpanId() {
            io.opentelemetry.api.trace.SpanContext ctx = Span.current().getSpanContext();
            return ctx.isValid() ? ctx.getSpanId() : null;
        }

        @Override
        public boolean isCurrentSpanSampled() {
            io.opentelemetry.api.trace.SpanContext ctx = Span.current().getSpanContext();
            return ctx.isValid() && ctx.isSampled();
        }

        @Override
        public void markCurrentSpanAsExemplar() {
            // 去重钩子：默认采样器用它避免同一 span 反复写 exemplar。
            // 这里留空 —— 同一 trace 内的重复观测都可能成为 exemplar，量很小，可接受。
        }
    }
}
