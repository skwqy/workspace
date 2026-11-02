package com.example.obs.web;

import com.example.obs.log.Traces;
import com.example.obs.service.OrderService;
import com.example.obs.web.dto.OrderView;
import com.example.obs.web.dto.PlaceOrderRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 下单。HTTP server span 由 agent 自动创建（无需一行代码），
     * 方法内部的 {@code @WithSpan} 与手工 span 会自动挂成它的子 span。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView place(@RequestBody @Valid PlaceOrderRequest request) {
        return toView(orderService.place(request.sku(), request.qty()));
    }

    /** 查单；用 id 前缀 {@code MISS} 可稳定复现 404，用于验证"4xx 也要在链路上留痕" */
    @GetMapping("/{orderId}")
    public OrderView find(@PathVariable String orderId) {
        return toView(orderService.find(orderId));
    }

    private static OrderView toView(OrderService.OrderResult r) {
        return new OrderView(r.orderId(), r.sku(), r.qty(), r.unitPrice(), r.amount(), r.costMs(),
                Traces.traceId(), Traces.spanId());
    }
}
