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
        buildConfigField("String", "OLLAMA_BASE_URL", "\"http://192.168.2.170:11434\"")
        buildConfigField("String", "OLLAMA_MODEL", "\"qwen3.5:4b\"")
    }

    buildTypes {
        getByName("debug") {
            // 开发构建允许局域网 HTTP 地址（与 debug network_security_config 放开一致）。
            buildConfigField("boolean", "ALLOW_INSECURE_HTTP", "true")
        }
        getByName("release") {
            // 量产构建默认要求 HTTPS（IVI-IVAI-DSN-CR-003 地址校验约束）。
            buildConfigField("boolean", "ALLOW_INSECURE_HTTP", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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

    // AgentService exposes model-client config types through the binder
    // (configRepository), so app-demo needs them on its compile classpath.
    api(project(":model-client"))
    implementation(project(":tool-registry"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.vintage.engine)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
