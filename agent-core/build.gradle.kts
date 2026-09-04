plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":model-client"))
    implementation(project(":tool-registry"))
    implementation(project(":tool-runtime"))
    implementation(project(":observability"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(project(":adapter-vehicle-mock"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
