plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(project(":brouter"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
    // The benchmark is memory-bound in a way the unit suite is not: BRouter
    // builds a tile cache per concurrent routing pass, and whether two of them
    // fit is the whole question behind `maxConcurrentPasses`. A phone's heap
    // ceiling is what that has to be judged against, not a container's, so the
    // benchmark can be run under one:
    //
    //     ./gradlew :solver:test -PshuntTestHeap=256m --tests '*Benchmark*'
    //
    (project.findProperty("shuntTestHeap") as String?)?.let { maxHeapSize = it }
}
