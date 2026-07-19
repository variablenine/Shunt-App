import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Secrets come from local.properties or the environment only — never
 * committed (see .gitignore). Absent in CI/fresh checkouts they are blank: the
 * app still builds and surfaces the gap at runtime rather than failing here.
 * HERE powers routing/search; the Tessie token + VIN (Part B) let the app talk
 * to the user's own vehicle — without them it runs against the fake client.
 */
fun localSecret(name: String): String {
    val fromProps = rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.let { Properties().apply { load(it.inputStream()) }.getProperty(name) }
    return (fromProps ?: System.getenv(name)).orEmpty()
}

val hereApiKey = localSecret("HERE_API_KEY")
val tessieToken = localSecret("TESSIE_TOKEN")
val tessieVin = localSecret("TESSIE_VIN")

android {
    namespace = "app.shunt"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "app.shunt"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "HERE_API_KEY", "\"$hereApiKey\"")
        buildConfigField("String", "TESSIE_TOKEN", "\"$tessieToken\"")
        buildConfigField("String", "TESSIE_VIN", "\"$tessieVin\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":solver"))
    implementation(project(":tesla"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.maplibre)

    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":tesla")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
