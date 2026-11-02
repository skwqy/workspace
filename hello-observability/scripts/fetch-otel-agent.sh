#!/usr/bin/env bash
# =====================================================================================
# 下载 OpenTelemetry Java Agent 到 ./otel-agent/opentelemetry-javaagent.jar
#
# 为什么不让 Dockerfile 直接 curl GitHub：
#   1) GitHub Release 的二进制域名（objects.githubusercontent.com）国内经常断流；
#   2) agent jar 是"与代码无关"的依赖，单独下载一次、Dockerfile 只 COPY，构建可离线、可复现。
#
# 优先级：阿里云 Maven 镜像 → Maven Central → GitHub Release
# =====================================================================================
set -euo pipefail

OTEL_AGENT_VERSION="${OTEL_AGENT_VERSION:-2.33.1}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="${ROOT_DIR}/otel-agent"
JAR="${DEST_DIR}/opentelemetry-javaagent.jar"

mkdir -p "${DEST_DIR}"

if [[ -f "${JAR}" ]]; then
  SIZE=$(wc -c <"${JAR}" | tr -d ' ')
  if [[ "${SIZE}" -gt 20000000 ]]; then
    echo "[skip] agent 已存在：${JAR} ($((SIZE / 1024 / 1024)) MB)"
    exit 0
  fi
  echo "[warn] 已有文件疑似不完整（${SIZE} bytes），重新下载"
  rm -f "${JAR}"
fi

URLS=(
  "https://maven.aliyun.com/repository/public/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar"
  "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar"
  "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"
)

for url in "${URLS[@]}"; do
  echo "[get ] ${url}"
  if curl -fL --connect-timeout 10 --max-time 300 -o "${JAR}.part" "${url}"; then
    mv "${JAR}.part" "${JAR}"
    echo "[ok  ] $(wc -c <"${JAR}" | tr -d ' ') bytes → ${JAR}"
    exit 0
  fi
  rm -f "${JAR}.part"
  echo "[fail] 该源不可用，换下一个"
done

echo "所有下载源都失败：请手动下载 opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar 放到 ${JAR}" >&2
exit 1
