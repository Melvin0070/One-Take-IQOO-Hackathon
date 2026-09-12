import java.security.MessageDigest

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
        ndk { abiFilters += "arm64-v8a" }
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
    implementation(files("../libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
}

val verifySherpaRuntime = tasks.register("verifySherpaRuntime") {
    val artifact = rootProject.layout.projectDirectory.file("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar")
    inputs.file(artifact)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.asFile.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } ==
            "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471") {
            "The pinned sherpa-onnx runtime digest does not match"
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifySherpaRuntime) }
