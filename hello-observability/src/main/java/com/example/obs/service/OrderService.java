package com.example.obs.service;

import com.example.obs.metrics.BusinessMetrics;
import io.micrometer.core.annotation.Timed;
import io.micrometer.observation.annotation.Observed;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 下单核心服务 —— 三支柱的"埋点样板"集中在这里。
 *
 * <p>一个方法上同时出现三类埋点，正好用来说明它们的边界：
 * <table border="1">
 *   <caption>埋点方式对照</caption>
 *   <tr><th>写法</th><th>产出</th><th>去哪</th></tr>
 *   <tr><td>{@code @Observed}</td><td>Observation → Timer/长任务指标</td><td>Prometheus</td></tr>
 *   <tr><td>{@code @WithSpan}</td><td>Span（方法级）</td><td>Jaeger</td></tr>
 *   <tr><td>{@code tracer.spanBuilder(...)}</td><td>Span + 业务属性/事件/异常</td><td>Jaeger</td></tr>
 *   <tr><td>{@code log.info(...)}</td><td>结构化日志（自动带 trace_id/span_id）</td><td>ES/Kibana</td></tr>
 * </table>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final AttributeKey<String> SKU = AttributeKey.stringKey("inventory.sku");
    private static final AttributeKey<Long> QTY = AttributeKey.longKey("inventory.qty");
    private static final AttributeKey<String> WAREHOUSE = AttributeKey.stringKey("inventory.warehouse");

    private final RiskService riskService;
    private final QuoteService quoteService;
    private final BusinessMetrics metrics;
    private final Tracer tracer;

    public OrderService(RiskService riskService, QuoteService quoteService, BusinessMetrics metrics, Tracer tracer) {
        this.riskService = riskService;
        this.quoteService = quoteService;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    /**
     * 下单。
     *
     * <p>注解顺序说明：{@code @Observed} 与 {@code @WithSpan} 各自独立生效，
     * 谁先谁后取决于切面 order，互不依赖；即便摘掉其中一个，另一个照常工作。
     */
    @Observed(name = "order.place", contextualName = "place-order")
    @WithSpan("order.place")
    public OrderResult place(@SpanAttribute("order.sku") String sku, @SpanAttribute("order.qty") int qty) {
        long startNs = System.nanoTime();
        metrics.enter();
        try {
            RiskService.Decision decision = riskService.check(sku, qty);
            if (!decision.approved()) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "RISK_REJECTED", decision.remark());
            }

            double unitPrice = reserveInventory(sku, qty);
            double amount = Math.round(unitPrice * qty * 100) / 100.0;
            String orderId = "O-" + Long.toHexString(ThreadLocalRandom.current().nextLong(0x100000L, 0xFFFFFFL));
            long costMs = (System.nanoTime() - startNs) / 1_000_000;

            metrics.orderPlaced(sku, amount);
            // 业务日志：不含技术细节，字段化输出，便于 ES 里做聚合
            log.info("order placed orderId={} sku={} qty={} amount={} costMs={}", orderId, sku, qty, amount, costMs);
            return new OrderResult(orderId, sku, qty, unitPrice, amount, costMs);
        } catch (BusinessException e) {
            metrics.orderFailed(e.getCode().toLowerCase());
            log.warn("order rejected orderId=? sku={} qty={} code={} message={}", sku, qty, e.getCode(), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            metrics.orderFailed("internal_error");
            log.error("order failed sku={} qty={}", sku, qty, e);
            throw e;
        } finally {
            metrics.exit();
        }
    }

    /** 查单：演示 {@code @Timed} 的百分位配置（P50/P95/P99 + 直方图） */
    @Timed(value = "order.query", percentiles = {0.5, 0.95, 0.99}, histogram = true)
    public OrderResult find(String orderId) {
        sleep(5);
        // 演示 404：用一个不可能命中缓存的 id 触发，异常链路上会带 ERROR 事件
        if (orderId.startsWith("MISS")) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "order not found: " + orderId);
        }
        return new OrderResult(orderId, "A-100", 1, 88.5, 88.5, 5);
    }

    /**
     * 手工 span 的"教学样本"：属性、事件、异常、状态码全都写了一遍。
     *
     * <p>为什么这里不用 {@code @WithSpan}？因为要在 span 上挂<b>运行时才知道</b>的属性（sku/qty/仓库），
     * 还要在失败分支里显式置 ERROR 并记录异常事件 —— 注解做不到这么细。
     */
    private double reserveInventory(String sku, int qty) {
        Span span = tracer.spanBuilder("inventory.reserve")
                .setAttribute(SKU, sku)
                .setAttribute(QTY, qty)
                .startSpan();
        // makeCurrent 把 span 放进当前 Context：这段代码里打出的日志、发起的下游调用、
        // 甚至新起的 @WithSpan 方法，都会挂到这个 span 下面
        try (Scope ignored = span.makeCurrent()) {
            sleep(ThreadLocalRandom.current().nextInt(30, 80));
            if (qty > 20) {
                IllegalStateException cause = new IllegalStateException("insufficient stock for sku=" + sku);
                span.recordException(cause);
                span.setStatus(StatusCode.ERROR, "OUT_OF_STOCK");
                throw new BusinessException(HttpStatus.CONFLICT, "OUT_OF_STOCK", cause.getMessage());
            }
            span.setAttribute(WAREHOUSE, "SH-01");
            span.addEvent("inventory.reserved", Attributes.of(SKU, sku, QTY, (long) qty));
            return quoteService.quote(sku);
        } catch (RuntimeException e) {
            // 兜底：任何没被上面分支显式记录的异常，都在这里落成 span 的 exception 事件
            if (span.isRecording() && span.getSpanContext().isValid()) {
                span.recordException(e);
            }
            throw e;
        } finally {
            span.end();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 领域结果，不含 trace 信息（trace 信息由 web 层附加，避免 service 依赖传输格式） */
    public record OrderResult(String orderId, String sku, int qty, double unitPrice, double amount, long costMs) {
    }
}
