@echo off
chcp 65001 > nul
echo ==============================================
echo       面试助手 (Interview Assistant) 一键启动
echo ==============================================

REM 先关闭可能残留的 Java 进程（防止 mvn clean 删 jar 失败）
taskkill /F /IM java.exe >nul 2>&1

echo [1/5] 检查 Docker 和 Qdrant 状态...
docker ps | findstr qdrant > nul
if %errorlevel% neq 0 (
    echo [警告] 未检测到运行中的 qdrant 容器！
    echo 正在尝试启动 Qdrant...
    docker run -d -p 6333:6333 -p 6334:6334 -v qdrant_storage:/qdrant/storage --name qdrant qdrant/qdrant
    if %errorlevel% neq 0 (
        echo [错误] 请确保 Docker Desktop 已打开！然后重新运行此脚本。
        pause
        exit /b
    )
) else (
    echo [OK] Qdrant 向量数据库正在运行.
)

echo.
echo [2/5] 构建前端资源 (React + Vite)...
cd frontend
call npm install
call npm run build
if %errorlevel% neq 0 (
    echo [错误] 前端构建失败！请确保已安装 Node.js。
    pause
    exit /b
)
cd ..
echo [OK] 前端构建完成.

echo.
echo [3/5] 构建后端项目 (Spring Boot)...
call mvn clean package -DskipTests
if %errorlevel% neq 0 (
    echo [错误] 后端构建失败！请检查代码或 Maven 环境。
    pause
    exit /b
)
echo [OK] 后端构建完成.

echo.
echo [4/5] 启动小红书爬虫服务 (FastAPI on port 8686)...
REM 先关闭可能残留的旧爬虫进程（占用 8686 端口）
for /f "tokens=5" %%p in ('netstat -ano ^| findstr :8686 ^| findstr LISTENING') do (
    taskkill /F /PID %%p >nul 2>&1
)
start "XHS-Spider" /B cmd /c "cd /d d:\project\Spider_XHS-master && python server.py > spider.log 2>&1"
REM 等待爬虫服务就绪（最多 15 秒）
echo 等待爬虫服务启动...
set SPIDER_OK=0
for /l %%i in (1,1,15) do (
    timeout /t 1 /nobreak > nul
    curl -s http://localhost:8686/api/health > nul 2>&1
    if not errorlevel 1 (
        if "!SPIDER_OK!"=="0" (
            set SPIDER_OK=1
        )
    )
)
curl -s http://localhost:8686/api/health > nul 2>&1
if not errorlevel 1 (
    echo [OK] 小红书爬虫服务已启动 (http://localhost:8686/docs^)
) else (
    echo [警告] 爬虫服务未就绪，小红书搜索功能可能不可用。日志见 Spider_XHS-master/spider.log
)

echo.
echo [5/5] 启动服务...
echo 请在浏览器中访问: http://localhost:8080
echo 小红书爬虫 API 文档: http://localhost:8686/docs
echo ----------------------------------------------
java -jar target/interview-assistant-1.0.0.jar --spring.profiles.active=local

