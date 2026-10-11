package com.example.stock.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 自定义 TurboFilter：按 logger 限流（1 秒固定窗口）。
 *
 * <p>TurboFilter 是 logback 里"最早"的过滤器——挂在 LoggerContext 上，
 * 在常规级别判断之前就能对每次日志调用直接做出 ACCEPT / DENY / NEUTRAL 决定，
 * 被拒的日志连参数格式化都不会发生，比靠级别过滤便宜得多。
 *
 * <p>本实现的语义：
 * <ul>
 *   <li>每个 logger 在 1 秒窗口内最多放行 {@code maxPerSecond} 条低级别日志，超出直接 DENY</li>
 *   <li>WARN 及以上永不拦截——错误日志一条都不能丢</li>
 * </ul>
 *
 * <p>logback-spring.xml 里 {@code <maxPerSecond>10</maxPerSecond>} 会调用同名 setter。
 * 高压日志（burst 演示）会在这里被静默吞掉，root 级别与 appender 根本看不到这些事件。
 */
public final class RateLimitTurboFilter extends TurboFilter {

    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();
    private int maxPerSecond = 10;

    public void setMaxPerSecond(int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (logger == null || level == null) {
            return FilterReply.NEUTRAL;
        }
        if (level.isGreaterOrEqual(Level.WARN)) {
            return FilterReply.NEUTRAL;
        }
        Window window = windows.computeIfAbsent(logger.getName(), k -> new Window(maxPerSecond));
        return window.tryAcquire() ? FilterReply.NEUTRAL : FilterReply.DENY;
    }

    /**
     * 1 秒固定窗口计数器（演示用实现；生产建议换令牌桶，避免窗口边界的突刺）。
     */
    private static final class Window {

        private final int max;
        private long windowStart;
        private int count;

        Window(int max) {
            this.max = max;
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            if (now - windowStart >= 1_000_000_000L) {
                windowStart = now;
                count = 0;
            }
            if (count < max) {
                count++;
                return true;
            }
            return false;
        }
    }
}
