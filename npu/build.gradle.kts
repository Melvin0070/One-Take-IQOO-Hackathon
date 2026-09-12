plugins {
    alias(libs.plugins.android.library)
    // NOTE: do NOT apply kotlin.android here. AGP 9 bundles Kotlin support and
    // registers the `kotlin` extension itself; applying the standalone plugin
    // fails with "Cannot add extension with name 'kotlin'".
}

val qairtSdk = providers.environmentVariable("QAIRT_SDK_ROOT")

android {
    namespace = "com.onetake.npu"
    compileSdk { version = release(37) }
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 31            // LiteRT NPU floor; see docs/agents/landmines.md
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += "-DQAIRT_SDK_ROOT=${qairtSdk.orNull.orEmpty()}"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

if (qairtSdk.isPresent) {
    listOf(
        "include/QNN/QnnInterface.h",
        "include/QNN/System/QnnSystemInterface.h",
        "lib/aarch64-android/libQnnHtp.so",
        "lib/aarch64-android/libQnnHtpPrepare.so",
        "lib/aarch64-android/libQnnHtpV81Stub.so",
        "lib/aarch64-android/libQnnSystem.so",
        "lib/hexagon-v81/unsigned/libQnnHtpV81Skel.so",
    ).forEach { relativePath ->
        require(file("${qairtSdk.get()}/$relativePath").isFile) {
            "QAIRT_SDK_ROOT is missing $relativePath"
        }
    }
}

val qairtLibraries = layout.buildDirectory.dir("generated/qairt/jniLibs")
val stageQairtRuntime = tasks.register<Sync>("stageQairtRuntime") {
    enabled = qairtSdk.isPresent
    into(qairtLibraries.map { it.dir("arm64-v8a") })
    from(qairtSdk.map { "$it/lib/aarch64-android" }) {
        include(
            "libQnnHtp.so",
            "libQnnHtpPrepare.so",
            "libQnnHtpV81Stub.so",
            "libQnnSystem.so",
        )
    }
    from(qairtSdk.map { "$it/lib/hexagon-v81/unsigned" }) {
        include("libQnnHtpV81Skel.so")
    }
}

tasks.named("preBuild").configure { dependsOn(stageQairtRuntime) }

androidComponents {
    onVariants { variant ->
        if (qairtSdk.isPresent) {
            variant.sources.jniLibs?.addStaticSourceDirectory(qairtLibraries.get().asFile.absolutePath)
        }
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // LiteRT 2.2.0 + the matching NPU runtime .so files. READ npu/AGENTS.md FIRST.
    // implementation("com.google.ai.edge.litert:litert:2.2.0")
    // implementation("com.google.ai.edge.litert:litert-api:2.2.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
}
