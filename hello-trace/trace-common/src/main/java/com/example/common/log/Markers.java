package com.example.common.log;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * 全项目统一的日志 Marker 常量。
 *
 * <p>Marker 是打在单条日志上的"语义标签"，由 logback-spring.xml 消费：
 * <ul>
 *   <li>CONFIDENTIAL —— 敏感数据标记，被全局 TurboFilter（MarkerFilter）无条件 DENY：
 *       任何代码就算误用这个 marker 打日志，输出里也永远看不到</li>
 *   <li>SECURITY —— 安全审计标记，额外路由到 security-audit.log 独立文件</li>
 *   <li>BUSINESS / PERF —— 业务与性能语义标签，便于日志检索</li>
 * </ul>
 */
public final class Markers {

    public static final Marker BUSINESS = MarkerFactory.getMarker("BUSINESS");
    public static final Marker SECURITY = MarkerFactory.getMarker("SECURITY");
    public static final Marker CONFIDENTIAL = MarkerFactory.getMarker("CONFIDENTIAL");
    public static final Marker PERF = MarkerFactory.getMarker("PERF");

    private Markers() {
    }
}
