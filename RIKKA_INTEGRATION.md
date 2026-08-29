# CodeAssist + RikkaHub Integration (方案 A)

> 改造 CodeAssist，嵌入 RikkaHub 的成熟 AI 能力（多 Provider LLM 客户端、Memory、Skills 等）

## 改造内容

### 新增模块: `rikka-bridge`

桥接 CodeAssist 的 `agent-api` SPI 和 RikkaHub 的 LLM 能力。

| 文件 | 职责 |
|------|------|
| `RikkaProviderBridge.kt` | LLM Provider 适配器（Anthropic/OpenAI/Gemini/OpenRouter/Local） |
| `RikkaLlmClient.kt` | 基于 OkHttp 的流式 SSE 客户端（替代 agent-impl 的 Provider） |
| `RikkaToolRegistry.kt` | 工具注册表（复用 CodeAssist BuiltinTools） |
| `RikkaAgentIntegration.kt` | 整合入口：Provider + Tool + SystemPrompt |
| `RikkaSystemPrompt.kt` | 系统提示生成器（注入实时项目上下文） |

### 修改文件

- `settings.gradle.kts`: 添加 `:rikka-bridge` 模块
- `.github/workflows/build.yml`: CI 构建验证

## 架构

```
┌─ 改造后的 CodeAssist ──────────────────────────────┐
│                                                     │
│  ┌─ rikka-bridge (新增) ────────────────────────┐  │
│  │  RikkaProviderBridge (替代 agent-impl 的       │  │
│  │    Provider 层)                                │  │
│  │  ├── Anthropic (Claude)                        │  │
│  │  ├── OpenAI (GPT)                              │  │
│  │  ├── Google Gemini                             │  │
│  │  ├── OpenRouter (多模型路由) ⭐                 │  │
│  │  └── Local Model (Ollama/llama.cpp) ⭐        │  │
│  │                                                │  │
│  │  RikkaToolRegistry                             │  │
│  │  └── 复用 BuiltinTools (20+ 工具)             │  │
│  │                                                │  │
│  │  RikkaSystemPrompt                             │  │
│  │  └── 实时项目上下文注入                       │  │
│  └────────────────────────────────────────────────┘  │
│         ▲ 依赖                                       │
│  ┌──────┴────────────────────────────────────────┘  │
│  │  agent-api (保留: AgentTool/AgentWorkspace/LlmProvider) │
│  │  agent-mcp (保留: MCP Server)                  │  │
│  │  build-engine / lang-java / lang-kotlin (保留)  │  │
│  └─────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## 部署方法

```bash
# 1. 在有网络的机器上运行
./deploy.sh <github-username> <personal-access-token>

# 2. 等待 GitHub Actions 构建完成
# 3. 查看 https://github.com/<username>/CodeAssist/actions
```

## 后续阶段

- **Phase 1**: 替换 agent-impl Provider 层 → 用 rikka-bridge 的 RikkaLlmClient
- **Phase 2**: 替换 agent-ui ChatDrawer → 用 RikkaHub 聊天 UI
- **Phase 3**: 集成 RikkaHub Workspace (PRoot Linux)
- **Phase 4**: Skills 系统和 Memory 集成
