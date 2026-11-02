package com.example.obs.web.dto;

import java.time.OffsetDateTime;

/**
 * 统一错误响应。带上 traceId 是硬性要求：一线排障时它比任何堆栈都值钱。
 */
public record ErrorView(String code, String message, String traceId, OffsetDateTime timestamp) {
}
