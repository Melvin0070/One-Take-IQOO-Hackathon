plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.onetake.app"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.onetake.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        // Halves the APK. You sideload every build to three loaners over Office
        // Kit; every megabyte is wall-clock at a gate. (Contract §1)
        ndk { abiFilters += listOf("arm64-v8a") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // R19: the debug keystore is committed on purpose. It signs DEBUG builds
        // only, so three laptops and CI can `adb install -r` over the loaners in
        // place without uninstalling — and uninstalling wipes models and sessions.
        // No release artifact is ever signed with it. Explained in README.md.
        getByName("debug") {
            storeFile = rootProject.file("config/debug.keystore")
            storePassword = "onetake"
            keyAlias = "onetake-debug"
            keyPassword = "onetake"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // LiteRT's Qualcomm runtime resolves its .so files from
            // applicationInfo.nativeLibraryDir, which needs uncompressed libs.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":asr"))
    implementation(project(":npu"))
    implementation(project(":capture"))
    implementation(project(":media"))
    implementation(project(":link"))
    debugImplementation(project(":engine-fixtures"))   // replay-driven UI (§6.2)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
