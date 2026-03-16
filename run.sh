#!/bin/bash

# 面试助手 (Interview Assistant) 一键启动脚本 (Mac/Linux)

echo "=============================================="
echo "      面试助手 (Interview Assistant) 一键启动"
echo "=============================================="

echo "[1/4] 检查 Docker 和 Qdrant 状态..."
if ! docker info >/dev/null 2>&1; then
    echo "[错误] 无法连接到 Docker daemon，请确保 Docker 已安装并运行中。"
    exit 1
fi

if ! docker ps | grep -q qdrant; then
    echo "[警告] 未检测到运行中的 qdrant 容器！"
    echo "正在尝试启动 Qdrant..."
    if ! docker run -d -p 6333:6333 -p 6334:6334 -v qdrant_storage:/qdrant/storage --name qdrant qdrant/qdrant; then
        echo "[错误] Qdrant 启动失败，请检查 Docker 配置。"
        exit 1
    fi
else
    echo "[OK] Qdrant 向量数据库正在运行."
fi

echo ""
echo "[2/4] 构建前端资源 (React + Vite)..."
cd frontend || exit 1
npm install
npm run build
if [ $? -ne 0 ]; then
    echo "[错误] 前端构建失败！请确保已安装 Node.js。"
    exit 1
fi
cd ..
echo "[OK] 前端构建完成."

echo ""
echo "[3/4] 构建后端项目 (Spring Boot)..."
if ! mvn clean package -DskipTests; then
    echo "[错误] 后端构建失败！请检查代码或 Maven 环境。"
    exit 1
fi
echo "[OK] 后端构建完成."

echo ""
echo "[4/4] 启动服务..."
echo "请在浏览器中访问: http://localhost:8080"
echo "----------------------------------------------"
java -jar target/interview-assistant-1.0.0.jar --spring.profiles.active=local
