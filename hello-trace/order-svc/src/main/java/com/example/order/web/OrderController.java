package com.example.order.web;

import com.example.order.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    public OrderView place(@RequestBody PlaceOrderRequest request) {
        return orderService.place(request.sku(), request.qty());
    }

    @GetMapping("/orders/burst")
    public BurstView burst(@RequestParam(defaultValue = "A-100") String sku,
                           @RequestParam(defaultValue = "20") int n,
                           @RequestParam(defaultValue = "100") long delayMs) {
        return orderService.burst(sku, n, delayMs);
    }
}
