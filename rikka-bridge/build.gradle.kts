plugins {
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization")
}

// rikka-bridge 是纯 Kotlin/JVM 模块，不依赖 Android SDK
// 它桥接 CodeAssist 的 agent-api 和 RikkaHub 的 LLM 能力
//
// 注意: rikka-bridge 只依赖 agent-api（接口层），不依赖 agent-impl（实现层）
// 因为 agent-impl 依赖 rikka-bridge（Phase 1 接入），如果反过来依赖会形成循环。
// RikkaChatViewModel 通过 AgentLoopRunner 回调接口运行 AgentLoop，
// 实际的 AgentLoop 创建在 agent-impl 或 ide-core 中完成。

dependencies {
    // CodeAssist agent API（接口定义，不形成循环）
    implementation(project(":agent-api"))
    
    // 共享依赖
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
