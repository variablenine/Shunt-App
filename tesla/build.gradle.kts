plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    // Part B: the production TessieVehicleNavClient talks HTTP to api.tessie.com.
    // Still pure JVM (OkHttp/serialization are JVM libs) — no Android deps.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // The abstract VehicleNavClient contract suite ships as test fixtures so
    // the production client (built separately) can extend and satisfy it.
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.kotlinx.coroutines.test)
    testFixturesImplementation(kotlin("test"))

    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
