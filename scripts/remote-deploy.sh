#!/usr/bin/env bash
# 在服务器上执行：用 CI 上传的构建上下文（src + pom.xml + frontend/dist）打包 → 重启 → 健康检查
# 不依赖服务器访问 GitHub（国内网络不稳定），也不再跨境传 88MB 的 jar。
set -euo pipefail

BUILD_DIR="${BUILD_DIR:-$HOME/build}"
APP_DIR="${APP_DIR:-/home/ubuntu/sspOffer-interview-assistant}"

# 允许传相对路径（相对于部署用户 home），脚本内部会 cd 到构建目录
resolve_path() {
  case "$1" in
    /*) echo "$1" ;;
    *) echo "$HOME/$1" ;;
  esac
}

CONTEXT_TAR=$(resolve_path "${1:-incoming/build-context.tar.gz}")
DEPLOY_SCRIPT=$(resolve_path "${DEPLOY_SCRIPT:-incoming/ssp-deploy.sh}")

if [ ! -f "$CONTEXT_TAR" ]; then
  echo "[remote] 缺少构建上下文: $CONTEXT_TAR" >&2
  exit 1
fi

mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

echo "[remote] 解压构建上下文"
rm -rf src pom.xml frontend/dist
tar -xzf "$CONTEXT_TAR"

echo "[remote] Maven 打包"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m -XX:MaxMetaspaceSize=256m}"
mvn -B -ntp -DskipTests clean package

JAR=$(ls -t target/*.jar | head -1)
echo "[remote] 构建产物: $JAR"
cp -f "$JAR" "$APP_DIR/app.jar.new"

bash "$DEPLOY_SCRIPT" "$APP_DIR/app.jar.new"
