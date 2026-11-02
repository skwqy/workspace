#!/usr/bin/env bash
# =====================================================================================
# 造一批有代表性的流量，用来把三个面板同时"喂出数据"
#   bash scripts/demo-traffic.sh [BASE_URL]
# =====================================================================================
set -uo pipefail

BASE="${1:-http://localhost:8080}"
echo "target: ${BASE}"

post_order() {
  curl -s -o /dev/null -w "  POST /api/orders qty=$1 -> %{http_code} %{time_total}s\n" \
    -X POST "${BASE}/api/orders" -H 'Content-Type: application/json' -d "{\"sku\":\"A-100\",\"qty\":$1}"
}

echo "[1/5] 正常下单 x8（低延迟、成功）"
for _ in $(seq 1 8); do post_order 2; done

echo "[2/5] 风控拒绝 x2（422，业务异常，失败率指标 +1）"
post_order 60
post_order 80

echo "[3/5] 库存不足 x2（409，手工 span 上的 ERROR + exception 事件）"
post_order 30
post_order 40

echo "[4/5] 慢请求（喂 HTTP 耗时分位数与 JVM 指标）"
curl -s -o /dev/null -w "  GET /api/demo/slow?ms=700 -> %{http_code} %{time_total}s\n" "${BASE}/api/demo/slow?ms=700"

echo "[5/5] 并发扇出 + 未捕获异常"
curl -s -o /dev/null -w "  GET /api/demo/fanout?n=6 -> %{http_code} %{time_total}s\n" "${BASE}/api/demo/fanout?n=6"
curl -s -o /dev/null -w "  GET /api/demo/fail -> %{http_code}\n" "${BASE}/api/demo/fail"

echo
echo "接下来看三个地方："
echo "  Prometheus : http://localhost:19090/graph?g0.expr=rate(http_server_requests_seconds_count%5B5m%5D)"
echo "  Jaeger     : http://localhost:16686  （service 选 hello-observability）"
echo "  Kibana     : http://localhost:15601  （索引模式 app-logs-*，按 trace.id 过滤）"
