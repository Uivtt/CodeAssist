plugins {
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization")
}

// rikka-bridge 是纯 Kotlin/JVM 模块，不依赖 Android SDK
// 它桥接 CodeAssist 的 agent-api 和 RikkaHub 的 LLM 能力

dependencies {
    // CodeAssist agent API（接口定义）
    implementation(project(":agent-api"))
    
    // RikkaHub AI 模块（LLM Provider 抽象）
    // implementation(project(":rikka-ai"))
    // RikkaHub Common
    // implementation(project(":rikka-common"))
    
    // 共享依赖
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
