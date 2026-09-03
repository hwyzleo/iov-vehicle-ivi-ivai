plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Public API exposes OkHttpClient (OllamaModelProvider ctor) and JsonElement (ModelResponse).
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.okhttp)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
