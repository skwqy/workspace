package com.example.obs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 可观测性三支柱样例工程。
 *
 * <p>三支柱各走各的通道，互不依赖：
 * <ul>
 *   <li>指标：Micrometer → Prometheus 文本端点（{@code /actuator/prometheus}），外界每 15s 主动 pull；</li>
 *   <li>链路：opentelemetry-javaagent 字节码注入 → OTLP gRPC → otel-collector → Jaeger（push）；</li>
 *   <li>日志：SLF4J + Logback → JSON 文件 → Filebeat → Kafka → Logstash → Elasticsearch → Kibana。</li>
 * </ul>
 *
 * @see <a href="https://opentelemetry.io/docs/zero-code/java/agent/">OpenTelemetry Java Agent</a>
 */
@SpringBootApplication
public class ObservabilityApplication {

    public static void main(String[] args) {
        SpringApplication.run(ObservabilityApplication.class, args);
    }
}
