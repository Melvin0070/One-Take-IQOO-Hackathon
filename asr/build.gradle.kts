plugins {
    alias(libs.plugins.android.library)
    // NOTE: do NOT apply kotlin.android here. AGP 9 bundles Kotlin support and
    // registers the `kotlin` extension itself; applying the standalone plugin
    // fails with "Cannot add extension with name 'kotlin'".
}

android {
    namespace = "com.onetake.asr"
    compileSdk { version = release(37) }

    defaultConfig {
        minSdk = 31            // LiteRT NPU floor; see docs/agents/landmines.md
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // sherpa-onnx v1.13.8+ static-link AAR goes here. READ asr/AGENTS.md FIRST.
    // implementation(files("../libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
