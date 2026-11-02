#!/usr/bin/env bash
# =====================================================================================
# 本机启动（带 javaagent）
#   用法：bash scripts/run-local.sh                        # 默认 8080
#         bash scripts/run-local.sh --server.port=8081     # 覆盖 Spring 参数
#         OTEL_TRACES_EXPORTER=logging bash scripts/run-local.sh
#   链路后端没起时也能跑：trace 上报失败只会打 WARN，业务不受影响
# =====================================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

if [[ ! -f otel-agent/opentelemetry-javaagent.jar ]]; then
  bash scripts/fetch-otel-agent.sh
fi

echo "[build] mvn package"
mvn -B -q -DskipTests package

# ---- 链路：身份 + 出口（与 Dockerfile 里的 ENV 保持一致，改一处记得改另一处）----
export OTEL_SERVICE_NAME="${OTEL_SERVICE_NAME:-hello-observability}"
export OTEL_RESOURCE_ATTRIBUTES="${OTEL_RESOURCE_ATTRIBUTES:-deployment.environment=local,service.version=0.0.1,service.namespace=hello}"
export OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4317}"
export OTEL_EXPORTER_OTLP_PROTOCOL="${OTEL_EXPORTER_OTLP_PROTOCOL:-grpc}"
export OTEL_TRACES_EXPORTER="${OTEL_TRACES_EXPORTER:-otlp}"
# 指标/日志不走 OTLP：指标 → Micrometer/Prometheus，日志 → Logback/Filebeat
export OTEL_METRICS_EXPORTER="${OTEL_METRICS_EXPORTER:-none}"
export OTEL_LOGS_EXPORTER="${OTEL_LOGS_EXPORTER:-none}"
# 关掉 agent 自带的 logback appender 采集，避免日志进两条管道
export OTEL_INSTRUMENTATION_LOGBACK_APPENDER_ENABLED="${OTEL_INSTRUMENTATION_LOGBACK_APPENDER_ENABLED:-false}"
export OTEL_INSTRUMENTATION_LOGBACK_MDC_ADD_BAGGAGE="${OTEL_INSTRUMENTATION_LOGBACK_MDC_ADD_BAGGAGE:-true}"
export OTEL_TRACES_SAMPLER="${OTEL_TRACES_SAMPLER:-parentbased_traceidratio}"
export OTEL_TRACES_SAMPLER_ARG="${OTEL_TRACES_SAMPLER_ARG:-1.0}"
export OTEL_PROPAGATORS="${OTEL_PROPAGATORS:-tracecontext,baggage}"

export LOG_PATH="${LOG_PATH:-logs}"

echo "[run  ] service=${OTEL_SERVICE_NAME} otlp=${OTEL_EXPORTER_OTLP_ENDPOINT} logPath=${LOG_PATH}"
# "$@" 放在 -jar 之后：额外参数一律当作 Spring Boot 参数（--server.port=8081 等），
# 想加 JVM 参数用环境变量 JAVA_TOOL_OPTIONS 注入，避免"Unrecognized option"这种低级错
exec java \
  -javaagent:otel-agent/opentelemetry-javaagent.jar \
  -XX:+ExitOnOutOfMemoryError \
  -Dfile.encoding=UTF-8 \
  -jar target/hello-observability-0.0.1-SNAPSHOT.jar \
  "$@"
