#!/usr/bin/env bash
# 在服务器上执行：拉代码 → 用 CI 构建好的前端 dist → Maven 打包 → 调 server-deploy.sh
# 这样避免从 GitHub Runner 跨境上传 88MB 的 jar（实测只有十几 KB/s）。
set -euo pipefail

BUILD_DIR="${BUILD_DIR:-/home/deploy/build}"
APP_DIR="${APP_DIR:-/home/ubuntu/sspOffer-interview-assistant}"
REPO="${REPO:-https://github.com/szk-stack/offerhelper.git}"
BRANCH="${BRANCH:-main}"
DIST_TAR="${1:-$HOME/incoming/frontend-dist.tar.gz}"
DEPLOY_SCRIPT="${DEPLOY_SCRIPT:-$HOME/incoming/ssp-deploy.sh}"

echo "[remote] 同步代码 $BRANCH"
if [ ! -d "$BUILD_DIR/.git" ]; then
  git clone --depth 1 --branch "$BRANCH" "$REPO" "$BUILD_DIR"
fi

cd "$BUILD_DIR"
git fetch --depth 1 origin "$BRANCH"
git reset --hard "origin/$BRANCH"

echo "[remote] 解压前端产物"
rm -rf frontend/dist
mkdir -p frontend/dist
tar -xzf "$DIST_TAR" -C frontend/dist

echo "[remote] Maven 打包"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m -XX:MaxMetaspaceSize=256m}"
mvn -B -ntp -DskipTests package

JAR=$(ls -t target/*.jar | head -1)
echo "[remote] 构建产物: $JAR"
cp -f "$JAR" "$APP_DIR/app.jar.new"

bash "$DEPLOY_SCRIPT" "$APP_DIR/app.jar.new"
