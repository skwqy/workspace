#!/usr/bin/env bash
# 一键启动：打开 Docker Desktop → 等引擎就绪 → 启动所有栈 → 显示状态
# 用法（Git Bash）：  bash /d/code/workspace/docker-stacks/startup-all.sh
STACKS_DIR=/d/code/workspace/docker-stacks

echo "== 1/3 启动 Docker Desktop =="
if docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
  echo "引擎已在运行"
else
  powershell.exe -NoProfile -Command 'Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"'
  printf "等待引擎就绪"
  up=0
  for i in $(seq 1 36); do
    sleep 5
    if docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
      up=1; echo " -> 就绪（约 $((i*5)) 秒）"
      break
    fi
    printf "."
  done
  if [ "$up" = 0 ]; then
    echo ""
    echo "[!!] 引擎 180 秒内未就绪——打开 Docker Desktop 窗口看有没有报错，处理后重跑本脚本"
    exit 1
  fi
fi

echo "== 2/3 启动所有 compose 栈 =="
for d in "$STACKS_DIR"/*/; do
  if [ -f "$d/compose.yaml" ]; then
    echo "-- $(basename "$d")"
    (cd "$d" && docker compose up -d 2>&1 | sed 's/^/   /')
  fi
done

echo "== 3/3 当前状态（MySQL/Kafka 要 ~30 秒才到 (healthy)，属正常） =="
sleep 3
for d in "$STACKS_DIR"/*/; do
  if [ -f "$d/compose.yaml" ]; then
    (cd "$d" && docker compose ps --format "  {{.Name}}: {{.Status}}")
  fi
done
echo "完成。"
