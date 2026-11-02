# =====================================================================================
# hello-observability 运行镜像
#   要点：OTel javaagent 通过 JAVA_TOOL_OPTIONS 挂载 —— 不改一行启动命令，也不改打包产物。
#   构建前置：bash scripts/fetch-otel-agent.sh（把 agent jar 下到 ./otel-agent/）
#   注意：不要加 "# syntax=docker/dockerfile:1"，否则构建会强制联网拉 frontend 镜像。
# =====================================================================================

# ---- 阶段一：把 fat jar 解压成"依赖层 + 代码层"，利用镜像层缓存 ----
FROM eclipse-temurin:25-jre AS builder
WORKDIR /builder
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- 阶段二：运行镜像 ----
FROM eclipse-temurin:25-jre
RUN useradd --system --create-home app \
 && mkdir -p /var/log/app /opt/otel \
 && chown -R app:app /var/log/app /opt/otel

# agent jar：只取决于 agent 版本，单独一层，换代码不会让这层缓存失效
COPY --chown=app:app otel-agent/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar

USER app
WORKDIR /application
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./

# 链路身份与出口：service 名、资源属性、OTLP gRPC 端点
ENV TZ=Asia/Shanghai \
    LOG_PATH=/var/log/app \
    OTEL_SERVICE_NAME=hello-observability \
    OTEL_RESOURCE_ATTRIBUTES=deployment.environment=dev,service.version=0.0.1,service.namespace=hello \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317 \
    OTEL_EXPORTER_OTLP_PROTOCOL=grpc \
    OTEL_EXPORTER_OTLP_TIMEOUT=5000 \
    OTEL_TRACES_EXPORTER=otlp \
    OTEL_METRICS_EXPORTER=none \
    OTEL_LOGS_EXPORTER=none \
    OTEL_INSTRUMENTATION_LOGBACK_APPENDER_ENABLED=false \
    OTEL_INSTRUMENTATION_LOGBACK_MDC_ADD_BAGGAGE=true \
    OTEL_TRACES_SAMPLER=parentbased_traceidratio \
    OTEL_TRACES_SAMPLER_ARG=1.0 \
    OTEL_PROPAGATORS=tracecontext,baggage \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -javaagent:/opt/otel/opentelemetry-javaagent.jar"

# 架构决策（写在这里，防止后人"顺手"打开）：
#   OTEL_METRICS_EXPORTER=none —— 指标交给 Micrometer + Prometheus（pull），不走 OTLP
#   OTEL_LOGS_EXPORTER=none    —— 日志交给 Logback + Filebeat（旁路），不走 OTLP
#   两个都打开会让同一份数据出现两条通道、两套口径，是这类架构最常见的事故源。

ENTRYPOINT ["java", "-jar", "application.jar"]
