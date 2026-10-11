package com.example.common.log;

import org.slf4j.MDC;

/**
 * 读取 Micrometer Tracing 写入 MDC 的 traceId / spanId，
 * 方便在业务代码和 HTTP 响应体里直接展示"当前调用链"。
 */
public final class Traces {

    public static String traceId() {
        return MDC.get("traceId");
    }

    public static String spanId() {
        return MDC.get("spanId");
    }

    public static String currentTrace() {
        String traceId = traceId();
        String spanId = spanId();
        return (traceId == null ? "-" : traceId) + "/" + (spanId == null ? "-" : spanId);
    }

    private Traces() {
    }
}
