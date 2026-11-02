package com.example.obs.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 业务指标（手写 MeterRegistry 那一层）。
 *
 * <p>和 {@code @Observed}/{@code @Timed} 的分工：
 * <ul>
 *   <li>注解埋点负责"框架级"指标：HTTP 请求耗时、方法耗时（命名由注解决定）；</li>
 *   <li>本类负责"业务语义"指标：下单数、失败原因分布、金额分布、在途请求数。</li>
 * </ul>
 *
 * <p>命名一律 {@code 领域.对象.单位}（Prometheus 侧会自动变成 {@code order_placed_total}），
 * 这是 Micrometer 官方推荐的可读命名法，也避免了和框架指标 {@code http_server_requests_seconds} 撞名。
 *
 * <p>注意：这里所有 Meter 都在构造期注册。Meter 注册是懒绑定到 Prometheus Registry 的，
 * <b>不要</b>在请求路径里反复 {@code Counter.builder(...).register(...)} 造新对象（虽然 registry 会缓存，
 * 但动态 tag 值无上限时会撑爆拉取响应体积，属于经典的基数爆炸）。
 */
@Component
public class BusinessMetrics {

    /** 下单成功数，按商品维度打 tag */
    private final Counter ordersPlaced;
    /** 下单失败总数，按失败原因打 tag（reason 取值是一个有限枚举，不会爆炸） */
    private final Counter ordersFailed;
    /** 订单金额分布，带 SLO 桶，方便在 Prometheus 里算 P95/P99 */
    private final DistributionSummary orderAmount;
    /** 当前正在处理的下单请求数（Gauge 由 Micrometer 在 scrape 时回调取值） */
    private final AtomicInteger inflight = new AtomicInteger();
    /** 保留 registry 引用：失败原因这一类"运行时才知道 tag"的指标按需取 */
    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ordersPlaced = Counter.builder("order.placed")
                .description("成功下单数")
                .tag("channel", "http")
                .register(registry);

        this.ordersFailed = Counter.builder("order.failed")
                .description("下单失败数")
                .tag("channel", "http")
                .register(registry);

        this.orderAmount = DistributionSummary.builder("order.amount")
                .description("订单金额分布")
                .baseUnit("CNY")
                // 金额只关心这几个业务边界，不开 publishPercentileHistogram：
                // 否则 Micrometer 会生成一路线性桶（1,2,3…），时序数量翻几十倍还没人用
                .serviceLevelObjectives(50, 100, 500, 1000, 5000)
                .register(registry);

        // Gauge 持有的是 weak reference 到 AtomicInteger；本类是单例 bean，不会跑掉
        Gauge.builder("order.inflight", inflight, AtomicInteger::doubleValue)
                .description("在途下单请求数")
                .register(registry);
    }

    public void orderPlaced(String sku, double amount) {
        ordersPlaced.increment();
        orderAmount.record(amount);
    }

    public void orderFailed(String reason) {
        ordersFailed.increment();
        // 失败原因分布单独记一条：reason 是有限枚举，基数安全
        registry.counter("order.failed.by.reason", "reason", reason).increment();
    }

    public void enter() {
        inflight.incrementAndGet();
    }

    public void exit() {
        inflight.decrementAndGet();
    }
}
