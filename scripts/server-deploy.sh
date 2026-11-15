#!/usr/bin/env bash
# 在服务器上执行：替换 jar → 重启 → 健康检查 → 失败自动回滚
# 用法：bash server-deploy.sh /path/to/app.jar.new
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/sspOffer-interview-assistant}"
SERVICE="${SERVICE:-ssp-offer}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/api/auth/me}"
NEW_JAR="${1:-}"

if [ -z "$NEW_JAR" ] || [ ! -f "$NEW_JAR" ]; then
  echo "用法: $0 <新 jar 路径>" >&2
  exit 1
fi

cd "$APP_DIR"

if [ -f app.jar ]; then
  cp -f app.jar app.jar.prev
  echo "[deploy] 已备份旧版本到 app.jar.prev"
fi

mv -f "$NEW_JAR" app.jar
echo "[deploy] 已替换 app.jar，重启 $SERVICE"
sudo -n /usr/bin/systemctl restart "$SERVICE"

echo "[deploy] 等待健康检查..."
for _ in $(seq 1 40); do
  code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$HEALTH_URL" || true)
  if [ "$code" = "401" ] || [ "$code" = "200" ]; then
    echo "[deploy] 健康检查通过（HTTP $code），部署完成"
    exit 0
  fi
  sleep 2
done

echo "[deploy] 健康检查失败，回滚到上一个版本" >&2
if [ -f app.jar.prev ]; then
  mv -f app.jar.prev app.jar
  sudo -n /usr/bin/systemctl restart "$SERVICE"
  sleep 5
fi
exit 1
