package com.example.obs.config;

import io.micrometer.core.aop.CountedAspect;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 指标埋点与链路埋点的"接线板"。
 *
 * <p>本工程刻意把两套埋点分开接：
 * <ul>
 *   <li>{@code @Observed} / {@code @Timed} / {@code @Counted} → Micrometer Observation → 指标（pull 模型）；</li>
 *   <li>{@code @WithSpan} / {@link Tracer} → OpenTelemetry API → 链路（由 javaagent 提供 SDK 与导出）。</li>
 * </ul>
 * 这是 javaagent 方案与"Micrometer Tracing + Observation 桥"方案最大的差别：
 * 指标和链路的埋点各写各的，不共享一个 Observation 上下文，二者靠 <b>exemplar</b> 和 <b>日志里的 trace_id</b> 对上号。
 */
@Configuration(proxyBeanMethods = false)
public class TelemetryConfig {

    /**
     * {@code @Observed} 的切面。<b>必须自己声明</b>：
     * Boot 只有在 {@code management.observations.annotations.enabled=true} 时才会自动装配它，
     * 而默认是关闭的 —— 只加注解不加这个 bean，注解会被静默忽略（最常见的一个坑）。
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * {@code @Timed} 的切面，<b>同样必须自己声明</b>。
     *
     * <p>这里踩过一个更隐蔽的坑：Boot 的 {@code MetricsAspectsAutoConfiguration}（负责装配
     * {@link TimedAspect} 与 {@link CountedAspect}）和 {@code ObservedAspect} 共用同一个开关
     * {@code management.observations.annotations.enabled}。本工程为避免 {@code ObservedAspect}
     * 重复注册把它设成了 {@code false}，结果是 {@code @Observed} 生效、{@code @Timed}/{@code @Counted}
     * <b>全部静默失效</b> —— 编译通过、启动无警告、接口正常，只是 {@code /actuator/prometheus}
     * 里永远查不到 {@code risk_check_seconds} 这类指标。定位方式是"指标名对不上"，不是"报错"。
     *
     * <p>两个切面都显式声明后，开关保持 {@code false} 也不会再影响它们。
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    @Bean
    public CountedAspect countedAspect(MeterRegistry registry) {
        return new CountedAspect(registry);
    }

    /**
     * 让业务代码能注入 {@link OpenTelemetry} 的"唯一正确姿势"。
     *
     * <p>agent 在 premain 阶段初始化 {@link GlobalOpenTelemetry} 并把它全局固定住，
     * 所以这里拿到的就是 agent 装配好的、带 OTLP exporter 的那个实例。
     * 如果没挂 agent，{@code GlobalOpenTelemetry.get()} 返回 no-op 实现，业务代码照样能跑（链路为空）——
     * 这正是"对代码零侵入、可随时摘掉"的好处。
     */
    @Bean
    public OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("com.example.obs", "0.0.1");
    }
}
