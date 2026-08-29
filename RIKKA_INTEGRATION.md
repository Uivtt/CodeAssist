# CodeAssist + RikkaHub Integration (方案 A — 完成)

> 改造 CodeAssist，嵌入 RikkaHub 的成熟 AI 能力

## ✅ 完成状态

| Phase | 内容 | 状态 |
|-------|------|------|
| Phase 0 | rikka-bridge 模块创建 + LLM Provider 适配器 | ✅ 完成 |
| Phase 1 | Providers.kt 接入 + build.gradle.kts 依赖 | ✅ 完成 |
| Phase 2 | 扩展工具 (BuildProjectTool + GitTools) | ✅ 完成 |
| Phase 3 | 聊天 UI 桥接 (ChatState + ChatViewModel) | ✅ 完成 |
| Phase 4 | 记忆系统 + 技能系统 + 完整系统提示 | ✅ 完成 |

## 模块结构

```
rikka-bridge/
├── build.gradle.kts                              模块构建配置
└── src/main/kotlin/dev/ide/bridge/
    ├── RikkaAgentIntegration.kt                   整合入口（组装所有组件）
    ├── RikkaProviderBridge.kt                     LLM Provider 适配器
    ├── RikkaLlmClient.kt                          OkHttp SSE 流式客户端
    ├── RikkaSystemPrompt.kt                       系统提示生成器
    ├── RikkaToolRegistry.kt                      工具注册表桥接
    ├── RikkaExtendedTools.kt                      扩展工具工厂
    ├── tools/
    │   ├── BuildProjectTool.kt                    构建项目工具
    │   └── GitTools.kt                            Git 工具集
    ├── chat/
    │   ├── RikkaChatState.kt                      聊天 UI 状态模型
    │   └── RikkaChatViewModel.kt                 聊天 ViewModel
    ├── memory/
    │   └── RikkaMemoryStore.kt                   项目记忆系统
    └── skills/
        └── RikkaSkillManager.kt                  技能管理器
```

## 修改的文件

- `settings.gradle.kts` — 添加 `:rikka-bridge` 模块
- `agent-impl/build.gradle.kts` — 添加 `:rikka-bridge` 依赖
- `agent-impl/.../Providers.kt` — 使用 RikkaProviderBridge 替代原有 Provider
- `.github/workflows/build.yml` — CI 构建验证
- `.agents/skills/android-dev/skill.md` — Android 开发技能包

## 架构

```
┌─ 改造后的 CodeAssist ───────────────────────────────┐
│                                                       │
│  ┌─ rikka-bridge ──────────────────────────────────┐ │
│  │                                                  │ │
│  │  Phase 0: RikkaProviderBridge                    │ │
│  │    ├── Anthropic (Claude)                        │ │
│  │    ├── OpenAI (GPT-4o)                           │ │
│  │    ├── Google Gemini                             │ │
│  │    ├── OpenRouter (多模型路由) ⭐                  │ │
│  │    └── Local Model (Ollama) ⭐                   │ │
│  │                                                  │ │
│  │  Phase 2: RikkaExtendedTools                     │ │
│  │    ├── build_project (简化构建) ⭐                 │ │
│  │    ├── git_commit / git_push / git_status ⭐     │ │
│  │    └── + 原 20+ BuiltinTools                     │ │
│  │                                                  │ │
│  │  Phase 3: RikkaChatState + ViewModel            │ │
│  │    ├── 流式文本渲染                              │ │
│  │    ├── 工具调用可视化                            │ │
│  │    ├── 思考过程折叠显示                          │ │
│  │    └── 消息历史管理                              │ │
│  │                                                  │ │
│  │  Phase 4: RikkaMemoryStore + SkillManager       │ │
│  │    ├── 跨对话项目记忆 (分类存储) ⭐               │ │
│  │    ├── 技能系统 (可复用指令包) ⭐                  │ │
│  │    └── android-dev 技能 (预置) ⭐                 │ │
│  └──────────────────────────────────────────────────┘ │
│         ▲ 依赖                                       │
│  ┌──────┴───────────────────────────────────────────┐│
│  │  agent-api (保留)    agent-impl (修改)            ││
│  │  agent-mcp (保留)    build-engine (保留)          ││
│  │  lang-java (保留)    lang-kotlin (保留)           ││
│  └──────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────┘
```

## 使用方法

### 1. 下载 APK

从 GitHub Actions 下载构建好的 APK：
```
https://github.com/Uivtt/CodeAssist/actions
→ 选择最新的成功 run → Artifacts → debug-apk
```

### 2. 配置 Provider

在 Settings → Agent 中配置至少一个 API Key：
- Anthropic: console.anthropic.com
- OpenAI: platform.openai.com
- Gemini: ai.google.dev
- OpenRouter: openrouter.ai
- Local: Ollama (localhost:11434)

### 3. 使用 AI 开发

打开项目 → 右侧滑出聊天面板 → 对话写代码 → 构建 → 安装

---

> Last clean rebuild: 2026-08-29T21:25:44Z
