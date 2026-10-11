#!/usr/bin/env bash
# 归零脚本：停容器 → 退 Docker Desktop → 关 WSL 虚拟机 → 逐项验证
# 用法（Git Bash）：  bash /d/code/workspace/docker-stacks/shutdown-all.sh
# 数据全部保留在卷里；下次要用跑 startup-all.sh 一键回来。
STACKS_DIR=/d/code/workspace/docker-stacks

if docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
  echo "== 1/4 停止所有 compose 栈 =="
  found=0
  for d in "$STACKS_DIR"/*/; do
    if [ -f "$d/compose.yaml" ]; then
      found=1
      echo "-- $(basename "$d")"
      (cd "$d" && docker compose stop 2>&1 | sed 's/^/   /')
    fi
  done
  [ "$found" = 0 ] && echo "(栈目录里没有 compose.yaml，跳过)"

  echo "== 2/4 检查残留容器 =="
  left=$(docker ps -q 2>/dev/null)
  if [ -n "$left" ]; then
    echo "仍有容器在跑："
    docker ps --format "   {{.Names}} ({{.Status}})"
    echo "   如需强制停止：docker stop \$(docker ps -q)"
  else
    echo "(无运行中的容器)"
  fi
else
  echo "== 1/4、2/4 跳过：Docker 引擎本来就没在运行 =="
fi

echo "== 3/4 退出 Docker Desktop + 关闭 WSL 虚拟机 =="
if powershell.exe -NoProfile -Command 'if (Get-Process "Docker Desktop" -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }'; then
  powershell.exe -NoProfile -Command 'Get-Process "Docker Desktop","com.docker.backend","com.docker.build" -ErrorAction SilentlyContinue | Stop-Process -Force'
  echo "Docker Desktop 已退出"
else
  echo "Docker Desktop 本来就没在运行"
fi
if wsl.exe --shutdown 2>/dev/null; then
  echo "WSL 虚拟机已关闭"
else
  echo "WSL 关闭命令未生效（通常本就没在运行，可忽略）"
fi
sleep 2

echo "== 4/4 逐项验证 =="
if powershell.exe -NoProfile -Command 'if (Get-Process vmmemWSL -ErrorAction SilentlyContinue) { exit 1 } else { exit 0 }'; then
  echo "[OK] vmmemWSL 已消失（内存已释放）"
else
  echo "[!!] vmmemWSL 仍在运行（内存未释放）"
fi
if powershell.exe -NoProfile -Command 'if (Get-Process "Docker Desktop" -ErrorAction SilentlyContinue) { exit 1 } else { exit 0 }'; then
  echo "[OK] Docker Desktop 已退出"
else
  echo "[!!] Docker Desktop 仍在运行"
fi
wsllist=$(wsl.exe -l -v 2>/dev/null | iconv -f UTF-16LE -t UTF-8 2>/dev/null)
if echo "$wsllist" | grep -q "Running"; then
  echo "[!!] 仍有 WSL 发行版在运行："
  echo "$wsllist" | sed 's/^/     /'
else
  echo "[OK] 无运行中的 WSL 发行版"
fi
echo "归零完成。数据都在卷里，下次 startup-all.sh 一键回来。"
