#!/usr/bin/env bash
set -euo pipefail

# ════════════════════════════════════════════════════════════════════
#  CodeAssist + RikkaHub 部署脚本 (API 版)
#  
#  通过 GitHub REST API 直接创建文件，无需克隆仓库。
#  只需小量 API 请求，适合慢速网络。
#
#  用法:
#    ./deploy_api.sh <github-token>
#
#  例如:
#    ./deploy_api.sh ghp_your_new_token_here
# ════════════════════════════════════════════════════════════════════

TOKEN="${1:?用法: $0 <github-token>}"
API="https://api.github.com"
UPSTREAM="tyron12233/CodeAssist"
BRANCH="feature/rikka-integration"
SRC_DIR="/workspace/CodeAssist-Rikka"

auth_header="Authorization: token $TOKEN"
content_type="Content-Type: application/json"

info()  { echo -e "\033[0;32m[INFO]\033[0m $1"; }
warn()  { echo -e "\033[1;33m[WARN]\033[0m $1"; }
error() { echo -e "\033[0;31m[ERROR]\033[0m $1"; exit 1; }

# ── 获取当前用户名 ──
info "Step 1: 获取 GitHub 用户信息..."
USER_JSON=$(curl -s -H "$auth_header" "$API/user")
GH_USER=$(echo "$USER_JSON" | grep '"login"' | head -1 | sed 's/.*"login": *"\([^"]*\)".*/\1/')
if [[ -z "$GH_USER" ]]; then
    error "无法获取用户信息，请检查 Token 有效性"
fi
info "  用户: $GH_USER"

# ── Step 2: Fork 仓库（如果尚未 Fork）──
info "Step 2: 检查/Fork 仓库..."
FORK_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -H "$auth_header" "$API/repos/$GH_USER/CodeAssist")

if [[ "$FORK_STATUS" == "200" ]]; then
    info "  Fork 已存在: $GH_USER/CodeAssist"
elif [[ "$FORK_STATUS" == "404" ]]; then
    info "  正在创建 Fork..."
    curl -s -X POST -H "$auth_header" -H "$content_type" \
        "$API/repos/$UPSTREAM/forks" > /dev/null
    info "  Fork 创建中，等待 15 秒..."
    sleep 15
else
    error "检查 Fork 失败 (HTTP $FORK_STATUS)"
fi

# ── Step 3: 获取 main 分支 SHA ──
info "Step 3: 获取 main 分支 SHA..."
MAIN_SHA=$(curl -s -H "$auth_header" \
    "$API/repos/$GH_USER/CodeAssist/git/refs/heads/main" \
    | grep '"sha"' | head -1 | sed 's/.*"sha": *"\([^"]*\)".*/\1/')

if [[ -z "$MAIN_SHA" ]]; then
    # 可能 fork 还在处理中，再等一下
    info "  等待 Fork 完成..."
    sleep 15
    MAIN_SHA=$(curl -s -H "$auth_header" \
        "$API/repos/$GH_USER/CodeAssist/git/refs/heads/main" \
        | grep '"sha"' | head -1 | sed 's/.*"sha": *"\([^"]*\)".*/\1/')
fi

[[ -z "$MAIN_SHA" ]] && error "无法获取 main 分支 SHA"
info "  main SHA: ${MAIN_SHA:0:12}..."

# ── Step 4: 创建新分支 ──
info "Step 4: 创建分支 $BRANCH..."

# 检查分支是否已存在
BRANCH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -H "$auth_header" \
    "$API/repos/$GH_USER/CodeAssist/git/refs/heads/$BRANCH")

if [[ "$BRANCH_STATUS" == "200" ]]; then
    info "  分支已存在，将更新文件..."
    # 获取分支当前 SHA
    BRANCH_SHA=$(curl -s -H "$auth_header" \
        "$API/repos/$GH_USER/CodeAssist/git/refs/heads/$BRANCH" \
        | grep '"sha"' | head -1 | sed 's/.*"sha": *"\([^"]*\)".*/\1/')
    HEAD_SHA="$BRANCH_SHA"
else
    # 创建新分支
    curl -s -X POST -H "$auth_header" -H "$content_type" \
        -d "{\"ref\":\"refs/heads/$BRANCH\",\"sha\":\"$MAIN_SHA\"}" \
        "$API/repos/$GH_USER/CodeAssist/git/refs" > /dev/null
    info "  分支已创建"
    HEAD_SHA="$MAIN_SHA"
fi

# ── Step 5: 上传文件（通过 Contents API）──
info "Step 5: 上传改造文件..."

upload_file() {
    local local_path="$1"
    local remote_path="$2"
    
    local content=$(base64 -w 0 "$local_path" 2>/dev/null)
    if [[ -z "$content" ]]; then
        warn "  跳过空文件: $remote_path"
        return
    fi
    
    local file_size=$(wc -c < "$local_path")
    info "  上传: $remote_path ($file_size bytes)"
    
    # 检查文件是否已存在
    local check_status=$(curl -s -o /dev/null -w "%{http_code}" -H "$auth_header" \
        "$API/repos/$GH_USER/CodeAssist/contents/$remote_path?ref=$BRANCH")
    
    local method="PUT"
    local payload="{\"message\":\"Add $remote_path\",\"content\":\"$content\",\"branch\":\"$BRANCH\""
    
    if [[ "$check_status" == "200" ]]; then
        # 文件已存在，获取 SHA 并更新
        local existing_sha=$(curl -s -H "$auth_header" \
            "$API/repos/$GH_USER/CodeAssist/contents/$remote_path?ref=$BRANCH" \
            | grep '"sha"' | head -1 | sed 's/.*"sha": *"\([^"]*\)".*/\1/')
        payload="$payload,\"sha\":\"$existing_sha\""
    fi
    payload="$payload}"
    
    local result=$(curl -s -X $method -H "$auth_header" -H "$content_type" \
        -d "$payload" \
        "$API/repos/$GH_USER/CodeAssist/contents/$remote_path")
    
    # 检查结果
    if echo "$result" | grep -q '"sha"'; then
        info "    ✓ 上传成功"
    else
        warn "    可能失败: $(echo "$result" | head -1)"
    fi
}

# 上传 rikka-bridge 模块文件
for f in $(find "$SRC_DIR/rikka-bridge" -type f -name "*.kt" -o -name "*.kts" | sort); do
    remote_path="rikka-bridge/${f#$SRC_DIR/rikka-bridge/}"
    upload_file "$f" "$remote_path"
done

# 上传 GitHub Actions workflow
upload_file "$SRC_DIR/.github/workflows/build.yml" ".github/workflows/build.yml"

# 上传文档
upload_file "$SRC_DIR/RIKKA_INTEGRATION.md" "RIKKA_INTEGRATION.md"

# 上传部署脚本
upload_file "$SRC_DIR/deploy_api.sh" "deploy_api.sh"

# ── Step 6: 修改 settings.gradle.kts ──
info "Step 6: 修改 settings.gradle.kts..."

SETTINGS_CONTENT=$(cat "$SRC_DIR/settings.gradle.kts")
# 在 agent-mcp 行后插入 rikka-bridge
MODIFIED_SETTINGS=$(echo "$SETTINGS_CONTENT" | sed 's/(":agent-mcp",/"(":agent-mcp",\n    ":rikka-bridge",   \/\/ RikkaHub bridge: provider adapter + tool registry + system prompt"/')

# Base64 编码并上传
ENCODED=$(echo "$MODIFIED_SETTINGS" | base64 -w 0)

# 获取现有文件的 SHA
SETTINGS_SHA=$(curl -s -H "$auth_header" \
    "$API/repos/$GH_USER/CodeAssist/contents/settings.gradle.kts?ref=$BRANCH" \
    | grep '"sha"' | head -1 | sed 's/.*"sha": *"\([^"]*\)".*/\1/')

curl -s -X PUT -H "$auth_header" -H "$content_type" \
    -d "{\"message\":\"Add rikka-bridge module to settings.gradle.kts\",\"content\":\"$ENCODED\",\"sha\":\"$SETTINGS_SHA\",\"branch\":\"$BRANCH\"}" \
    "$API/repos/$GH_USER/CodeAssist/contents/settings.gradle.kts" > /dev/null

info "  ✓ settings.gradle.kts 已修改"

# ── 完成 ──
echo ""
info "═══════════════════════════════════════════════════"
info "  部署完成！"
info "═══════════════════════════════════════════════════"
echo ""
echo "  仓库: https://github.com/$GH_USER/CodeAssist/tree/$BRANCH"
echo "  Actions: https://github.com/$GH_USER/CodeAssist/actions"
echo ""
echo "  请打开上述链接查看 GitHub Actions 构建状态。"
echo ""
warn "  完成后请删除此 Token（如果是一次性使用的）"
