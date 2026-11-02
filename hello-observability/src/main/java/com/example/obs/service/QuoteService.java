package com.example.obs.service;

import io.micrometer.core.annotation.Timed;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * 询价服务：被虚拟线程并发调用，用来验证"跨线程上下文传播"。
 *
 * <p>{@code @WithSpan} 之所以能在虚拟线程里正确挂到父 span 下，是因为调用方用
 * {@code Context.current().wrap(...)} 把 OTel Context 带进了新线程；
 * 如果直接 {@code executor.submit(() -> ...)}，新线程的 Context 是 root，这里会变成一条孤立链路。
 */
@Service
public class QuoteService {

    @WithSpan("quote.calc")
    @Timed(value = "quote.calc", percentiles = {0.5, 0.95}, histogram = true)
    public double quote(String sku) {
        sleep(ThreadLocalRandom.current().nextInt(30, 90));
        // 用 sku 的 hash 造一个稳定的"商品价格"，保证同一商品多次询价结果一致，便于对账
        return 50 + Math.abs(sku.hashCode() % 500) / 10.0;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
