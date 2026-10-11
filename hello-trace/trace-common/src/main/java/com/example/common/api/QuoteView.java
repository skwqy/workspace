package com.example.common.api;

import java.math.BigDecimal;

/**
 * 询价响应：stock-svc 序列化，order-svc 反序列化。
 */
public record QuoteView(String sku, BigDecimal unitPrice) {
}
