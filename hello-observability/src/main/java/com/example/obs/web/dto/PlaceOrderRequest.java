package com.example.obs.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlaceOrderRequest(
        @NotBlank(message = "sku 不能为空")
        @Size(max = 32, message = "sku 长度不能超过 32")
        String sku,

        @Min(value = 1, message = "qty 至少为 1")
        @Max(value = 999, message = "qty 最多为 999")
        int qty) {
}
