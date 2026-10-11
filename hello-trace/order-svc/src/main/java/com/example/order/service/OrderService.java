package com.example.order.service;

import com.example.common.api.QuoteView;
import com.example.common.api.StockView;
import com.example.common.log.Markers;
import com.example.common.log.Traces;
import com.example.common.util.VirtualThreadExecutors;
import com.example.order.client.StockClient;
import com.example.order.web.BurstView;
import com.example.order.web.OrderView;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 订单服务：下单时用虚拟线程并行扇出"查库存 + 询价"两个下游 HTTP 调用。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final long CHILD_CALL_TIMEOUT_SECONDS = 10;

    private final StockClient stockClient;
    private final ObservationRegistry observationRegistry;

    public OrderService(StockClient stockClient, ObservationRegistry observationRegistry) {
        this.stockClient = stockClient;
        this.observationRegistry = observationRegistry;
    }

    public OrderView place(String sku, int qty) {
        log.info(Markers.BUSINESS, "place order: sku={} qty={} handlerThread={} virtual={}",
                sku, qty, Thread.currentThread().getName(), Thread.currentThread().isVirtual());

        Instant start = Instant.now();
        // 虚拟线程 + 追踪的经典坑：Observation 上下文是 ThreadLocal，
        // 新开的虚拟线程不会自动继承。提交任务前抓取当前请求的 Observation，
        // 在子线程里重新 openScope()，RestClient 才会把 HTTP 调用记成同一个
        // trace 下的 child span（traceparent 才会带过去）。见 withParentScope。
        Observation parent = observationRegistry.getCurrentObservation();

        try (ExecutorService vc = VirtualThreadExecutors.namedVirtualPerTask("order-vc-")) {
            Future<StockView> stockFuture = vc.submit(withParentScope(parent, () -> {
                log.info("child[stock] start: thread={} virtual={}",
                        Thread.currentThread().getName(), Thread.currentThread().isVirtual());
                StockView stock = stockClient.getStock(sku, 120);
                log.info("child[stock] done: available={} trace={}", stock.available(), Traces.currentTrace());
                return stock;
            }));
            Future<QuoteView> quoteFuture = vc.submit(withParentScope(parent, () -> {
                log.info("child[quote] start: thread={} virtual={}",
                        Thread.currentThread().getName(), Thread.currentThread().isVirtual());
                QuoteView quote = stockClient.getQuote(sku, 80);
                log.info("child[quote] done: unitPrice={} trace={}", quote.unitPrice(), Traces.currentTrace());
                return quote;
            }));

            StockView stock = stockFuture.get(CHILD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            QuoteView quote = quoteFuture.get(CHILD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            BigDecimal total = quote.unitPrice().multiply(BigDecimal.valueOf(qty));
            OrderView order = new OrderView(
                    "O-" + UUID.randomUUID().toString().substring(0, 8),
                    sku, qty,
                    stock.available(), stock.reserved(),
                    quote.unitPrice(), total,
                    Thread.currentThread().getName(),
                    Thread.currentThread().isVirtual(),
                    Traces.traceId(),
                    Duration.between(start, Instant.now()).toMillis());
            log.info(Markers.BUSINESS, "order placed: id={} total={} costMs={}",
                    order.orderId(), order.total(), order.costMs());
            return order;
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("downstream call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("order interrupted", e);
        }
    }

    /**
     * 并发扇出演示：n 个虚拟线程各打一次 stock-svc。
     * order-svc 侧的同格式日志会被 DuplicateMessageFilter（TurboFilter）压制，
     * stock-svc 侧则被自定义 RateLimitTurboFilter 限流——两个进程各演示一种 TurboFilter。
     */
    public BurstView burst(String sku, int n, long delayMs) {
        log.info(Markers.PERF, "burst start: n={} delayMs={} thread={}",
                n, delayMs, Thread.currentThread().getName());
        Instant start = Instant.now();
        Observation parent = observationRegistry.getCurrentObservation();

        try (ExecutorService vc = VirtualThreadExecutors.namedVirtualPerTask("order-burst-")) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                futures.add(vc.submit(withParentScope(parent, () -> {
                    stockClient.getStock(sku, delayMs);
                    // 每次都是同一条消息格式：DuplicateMessageFilter 的典型目标
                    log.info("burst child call ok (identical message format every time)");
                    return Boolean.TRUE;
                })));
            }

            long ok = 0;
            for (Future<Boolean> f : futures) {
                try {
                    if (Boolean.TRUE.equals(f.get(CHILD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS))) {
                        ok++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("burst interrupted", e);
                } catch (ExecutionException | TimeoutException e) {
                    log.warn("burst child failed: {}", String.valueOf(e.getMessage()));
                }
            }

            long costMs = Duration.between(start, Instant.now()).toMillis();
            log.info(Markers.PERF, "burst done: n={} ok={} costMs={}", n, ok, costMs);
            return new BurstView(sku, n, ok, costMs, Traces.traceId());
        }
    }

    /**
     * 把父 Observation 携带到子线程：openScope() 会在当前（子）线程上恢复上下文，
     * RestClient 的 client span 由此挂到同一个 trace 下。
     */
    private static <T> Callable<T> withParentScope(Observation parent, Callable<T> task) {
        return () -> {
            if (parent == null) {
                return task.call();
            }
            Observation.Scope scope = parent.openScope();
            try {
                return task.call();
            } finally {
                scope.close();
            }
        };
    }
}
