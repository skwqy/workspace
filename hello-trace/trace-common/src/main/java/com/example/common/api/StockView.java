package com.example.common.api;

import java.math.BigDecimal;

/**
 * 库存查询响应：stock-svc 序列化，order-svc 反序列化。
 */
public record StockView(String sku, int available, int reserved, BigDecimal price) {
}
