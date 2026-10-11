package com.example.order.web;

public record BurstView(String sku, int requested, long ok, long costMs, String traceId) {
}
