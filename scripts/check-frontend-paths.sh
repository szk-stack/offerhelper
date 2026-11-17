#!/usr/bin/env bash
# 子路径部署守卫：禁止在源码里写死以 / 开头的接口或资源路径。
# 正确做法：
#   - 接口：import { api, API_BASE } from 'api/client'，用 `${API_BASE}/...`
#   - 图片：import { asset } from 'utils/asset'，用 asset('logo.png')
#   - 路由：react-router 的 <Link to="/xxx">（to 不需要前缀）
set -euo pipefail

cd "$(dirname "$0")/.."

patterns=$(cat <<'EOF'
fetch\([[:space:]]*["'`]/
EventSource\([[:space:]]*["'`]/
window\.open\([[:space:]]*["'`]/
location\.(href|assign|replace)[[:space:]]*=[[:space:]]*["'`]/
(href|src)=["'`]/[^/]
url\(["']?/
EOF
)

fail=0
while IFS= read -r pattern; do
  [ -z "$pattern" ] && continue
  hits=$(grep -rnE "$pattern" frontend/src \
    --include='*.js' --include='*.jsx' --include='*.css' 2>/dev/null || true)
  if [ -n "$hits" ]; then
    echo "❌ 发现硬编码根路径（部署在 /interview/ 下会 404/405）："
    echo "$hits"
    echo
    fail=1
  fi
done <<< "$patterns"

if [ "$fail" -ne 0 ]; then
  echo "请改用 API_BASE / asset() / react-router Link。"
  exit 1
fi

echo "✅ 前端路径检查通过"
