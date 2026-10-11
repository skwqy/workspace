package com.example.order.web;

import java.math.BigDecimal;

/**
 * 下单响应：把"技术演示证据"（线程名、是否虚拟线程、traceId）一并返回，
 * 不翻日志也能看到虚拟线程与调用链生效。
 */
public record OrderView(
        String orderId,
        String sku,
        int qty,
        int available,
        int reserved,
        BigDecimal unitPrice,
        BigDecimal total,
        String handlerThread,
        boolean handlerVirtual,
        String traceId,
        long costMs) {
}
