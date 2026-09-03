plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.hwyz.iov.vehicle.ivi.ivai.service"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "OLLAMA_BASE_URL", "\"http://10.0.2.2:11434\"")
        buildConfigField("String", "OLLAMA_MODEL", "\"qwen3.5:4b\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // AgentService public API exposes types from these modules (submit -> AgentResult,
    // vehicleState -> MockVehicleState), so they must be api for consumers.
    api(project(":agent-core"))
    api(project(":tool-runtime"))
    api(project(":adapter-vehicle-mock"))
    api(project(":observability"))

    implementation(project(":model-client"))
    implementation(project(":tool-registry"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}
