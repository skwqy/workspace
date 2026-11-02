package com.example.obs.web.dto;

/**
 * 下单响应：尾部三个字段是可观测性"抓手"，方便手工验证。
 *
 * @param traceId 当前链路的 traceId —— 拿去 Jaeger 搜，能直接看到这条请求的完整 span 树
 * @param spanId  当前 HTTP server span 的 id
 * @param costMs  服务端处理耗时（与 Prometheus 里 {@code http_server_requests_seconds} 的观测一致）
 */
public record OrderView(
        String orderId,
        String sku,
        int qty,
        double unitPrice,
        double amount,
        long costMs,
        String traceId,
        String spanId) {
}
