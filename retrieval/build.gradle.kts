plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // ToolRetriever reads tool metadata (ToolDefinition) to build retrieval docs.
    implementation(project(":tool-registry"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // HTTP_COMPATIBLE Embedding Provider 使用 OkHttp（JVM/Android 双跑）。
    // 禁止引入 java.net.http（Android 无此 API，真机加载会 NoClassDefFoundError）。
    implementation(libs.okhttp)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
