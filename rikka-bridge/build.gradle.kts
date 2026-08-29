plugins {
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization")
}

// rikka-bridge 是纯 Kotlin/JVM 模块，不依赖 Android SDK
// 它桥接 CodeAssist 的 agent-api 和 RikkaHub 的 LLM 能力
//
// Phase 0: LLM Provider 适配器 (RikkaProviderBridge + RikkaLlmClient)
// Phase 1: 接入 agent-impl (通过 Providers.kt)
// Phase 2: 扩展工具 (BuildProjectTool + GitTools)
// Phase 3: 聊天 UI 桥接 (RikkaChatState + RikkaChatViewModel)
// Phase 4: 记忆系统 + 技能系统

dependencies {
    // CodeAssist agent API（接口定义）
    implementation(project(":agent-api"))
    
    // CodeAssist agent-impl（用于 AgentLoop、BuiltinTools、LlmTransport）
    implementation(project(":agent-impl"))
    
    // 共享依赖
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
