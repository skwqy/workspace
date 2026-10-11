#!/usr/bin/env bash
# 备份学习栈数据 —— 用 Git Bash 执行：bash /d/code/workspace/docker-stacks/backup.sh
# 物理备份（打包数据卷）+ 逻辑备份（mysqldump），产物落到 D:\code\backup
set -euo pipefail

STAMP=$(date +%Y%m%d_%H%M%S)
DEST=/d/code/backup
mkdir -p "$DEST"

echo "== 1/2 物理备份：打包 mysql / redis / kafka 数据卷 =="
for vol in mysql-data redis-data kafka-data; do
  docker run --rm -v "study-stack_${vol}":/src:ro -v "$DEST":/bak alpine \
    tar czf "/bak/${vol}_${STAMP}.tgz" -C /src .
  echo "   -> ${vol}_${STAMP}.tgz"
done

echo "== 2/2 逻辑备份：mysqldump 全库导出 =="
docker exec mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --all-databases' \
  | gzip > "$DEST/mysql_dump_${STAMP}.sql.gz"
echo "   -> mysql_dump_${STAMP}.sql.gz"

echo "备份完成 -> $DEST"
ls -lh "$DEST" | tail -5
