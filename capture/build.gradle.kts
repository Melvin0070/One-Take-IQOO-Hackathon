plugins {
    alias(libs.plugins.android.library)
    // NOTE: do NOT apply kotlin.android here. AGP 9 bundles Kotlin support and
    // registers the `kotlin` extension itself; applying the standalone plugin
    // fails with "Cannot add extension with name 'kotlin'".
}

android {
    namespace = "com.onetake.capture"
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
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
