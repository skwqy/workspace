package com.example.stock.web;

import com.example.common.api.QuoteView;
import com.example.common.api.StockView;
import com.example.common.log.Markers;
import com.example.common.log.Traces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 库存服务接口（被调方）。
 *
 * <p>所有日志消息保持 ASCII——Git Bash 里 curl 传中文参数会被 Tomcat 按 GBK 解码出 400（工作区坑 6）。
 */
@RestController
public class StockController {

    private static final Logger log = LoggerFactory.getLogger(StockController.class);

    /**
     * 库存查询：模拟一次慢 IO。
     * 虚拟线程在 Thread.sleep 阻塞时会卸载载体线程， millions 并发也不占等量平台线程——
     * 这正是 spring.threads.virtual.enabled=true 之后 Tomcat 用虚拟线程处理请求的价值。
     */
    @GetMapping("/stock/{sku}")
    public StockView stock(@PathVariable String sku,
                           @RequestParam(defaultValue = "80") long delayMs,
                           @RequestParam(defaultValue = "false") boolean fail) throws InterruptedException {
        log.info("stock query: sku={} thread={} virtual={}",
                sku, Thread.currentThread().getName(), Thread.currentThread().isVirtual());
        log.debug("warehouse detail dump for sku={} (visible only with ?debug=true)", sku);

        Thread.sleep(delayMs);

        if (fail) {
            // ERROR 级别：RateLimitTurboFilter 对 WARN+ 永不拦截，这条一定看得到
            throw new IllegalStateException("simulated warehouse failure for sku=" + sku);
        }

        int hash = Math.abs(sku.hashCode());
        StockView view = new StockView(sku, 100 - hash % 90, hash % 10,
                BigDecimal.valueOf(99 + hash % 100).add(BigDecimal.valueOf(hash % 99, 2)));
        log.info(Markers.BUSINESS, "stock resolved: sku={} available={}", sku, view.available());
        return view;
    }

    @GetMapping("/stock/{sku}/quote")
    public QuoteView quote(@PathVariable String sku,
                           @RequestParam(defaultValue = "50") long delayMs) throws InterruptedException {
        log.info("quote request: sku={} thread={} virtual={}",
                sku, Thread.currentThread().getName(), Thread.currentThread().isVirtual());
        Thread.sleep(delayMs);

        int hash = Math.abs(sku.hashCode());
        QuoteView quote = new QuoteView(sku,
                BigDecimal.valueOf(49 + hash % 50).add(BigDecimal.valueOf(hash % 97, 2)));
        log.info(Markers.BUSINESS, "quote computed: sku={} unitPrice={}", sku, quote.unitPrice());
        return quote;
    }

    /**
     * Marker 演示接口：一次调用打出三种命运不同的日志。
     * <ul>
     *   <li>SECURITY —— 正常输出，且被 logback 路由进 security-audit.log 独立文件</li>
     *   <li>CONFIDENTIAL —— 被 TurboFilter（MarkerFilter）全局 DENY，任何输出里都不出现</li>
     *   <li>DEBUG —— 默认被 DynamicThresholdFilter 挡住；带 ?debug=true 时本次请求放开</li>
     * </ul>
     */
    @GetMapping("/stock/audit")
    public Map<String, Object> audit(@RequestParam String sku,
                                     @RequestParam(defaultValue = "13800001111") String phone,
                                     @RequestParam(defaultValue = "false") boolean debug) {
        if (debug) {
            // DynamicThresholdFilter 读这个 MDC key：只对"本次请求所在的线程"放开 DEBUG
            MDC.put("debugOn", "true");
        }
        try {
            log.info(Markers.SECURITY, "audit started: sku={} phone(masked)={}", sku, mask(phone));
            log.info(Markers.CONFIDENTIAL,
                    "audit raw phone = {} (CONFIDENTIAL marker -> globally DENIED, must never appear)", phone);
            log.debug("audit verbose detail: sku={} phone={} thread={}",
                    sku, phone, Thread.currentThread().getName());

            return Map.of(
                    "sku", sku,
                    "phoneMasked", mask(phone),
                    "note", "SECURITY line -> logs/stock-svc/security-audit.log; CONFIDENTIAL line dropped by TurboFilter",
                    "thread", Thread.currentThread().getName(),
                    "virtual", Thread.currentThread().isVirtual(),
                    "traceId", String.valueOf(Traces.traceId()));
        } finally {
            MDC.remove("debugOn");
        }
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 8) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
