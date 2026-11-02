package com.example.obs.log;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;

/**
 * 读取当前 trace 上下文，用于把 traceId/spanId 回吐到 HTTP 响应里。
 *
 * <p>为什么响应里要带 traceId：用户/前端报障时只说"刚才下单失败"，
 * 有了 traceId 就能在 Jaeger 精确定位那一条链路、在 Kibana 捞出整条链路上的所有日志。
 * 这是"三支柱可关联"的最小成本实现。
 */
public final class Traces {

    private Traces() {
    }

    /**
     * 优先走 OTel API；agent 未挂载时回退到 MDC（agent 若挂载过，MDC 里也会有 trace_id）。
     * 两者都没有就返回空串，绝不让可观测性代码把业务打挂。
     */
    public static String traceId() {
        SpanContext ctx = Span.current().getSpanContext();
        if (ctx.isValid()) {
            return ctx.getTraceId();
        }
        String fromMdc = MDC.get("trace_id");
        return fromMdc == null ? "" : fromMdc;
    }

    public static String spanId() {
        SpanContext ctx = Span.current().getSpanContext();
        if (ctx.isValid()) {
            return ctx.getSpanId();
        }
        String fromMdc = MDC.get("span_id");
        return fromMdc == null ? "" : fromMdc;
    }
}
