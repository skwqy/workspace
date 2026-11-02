package com.example.obs.web;

import com.example.obs.log.Traces;
import com.example.obs.service.QuoteService;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 压测/演示用端点：造慢、造错、造并发，用来产出可观测数据。
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final QuoteService quoteService;

    public DemoController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    /** 造慢：{@code /api/demo/slow?ms=800}，用于观察 JVM 线程、HTTP 耗时分位数 */
    @GetMapping("/slow")
    public DemoResult slow(@RequestParam(defaultValue = "500") long ms) {
        log.info("slow endpoint start ms={}", ms);
        try {
            Thread.sleep(Math.min(ms, 10_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("slow endpoint done ms={}", ms);
        return new DemoResult("slow", ms, Traces.traceId());
    }

    /** 造错：未捕获异常 → 500，agent 会把 server span 标成 ERROR 并记录 exception 事件 */
    @GetMapping("/fail")
    public DemoResult fail(@RequestParam(defaultValue = "boom") String reason) {
        throw new IllegalStateException("intentional failure: " + reason);
    }

    /**
     * 并发扇出：{@code /api/demo/fanout?n=8}。
     *
     * <p><b>关键点</b>：{@code Context.current().wrap(...)} 把当前 OTel Context 携带进虚拟线程，
     * 否则子任务处于 root context，会在 Jaeger 里变成 n 条互不相干的短链路 ——
     * 这是异步/虚拟线程埋点最常见的错。
     */
    @GetMapping("/fanout")
    public FanoutResult fanout(@RequestParam(defaultValue = "8") int n) {
        int size = Math.max(1, Math.min(n, 64));
        long startNs = System.nanoTime();
        List<Quote> quotes = new ArrayList<>(size);
        // JDK 21+ 的 ExecutorService 实现了 AutoCloseable：close() 会等待所有任务结束，语义就是"结构化并发"
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Quote>> futures = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                String sku = "A-" + (100 + i);
                futures.add(executor.submit(Context.current().wrap(() -> new Quote(sku, quoteService.quote(sku)))));
            }
            for (Future<Quote> f : futures) {
                try {
                    quotes.add(f.get());
                } catch (Exception e) {
                    throw new IllegalStateException("fanout failed", e);
                }
            }
        }
        long costMs = (System.nanoTime() - startNs) / 1_000_000;
        log.info("fanout done n={} costMs={}", size, costMs);
        return new FanoutResult(size, costMs, quotes, Traces.traceId());
    }

    public record DemoResult(String scene, long value, String traceId) {
    }

    public record Quote(String sku, double price) {
    }

    public record FanoutResult(int n, long costMs, List<Quote> quotes, String traceId) {
    }
}
