plugins { id("com.android.library") }

val qairtSdk = providers.environmentVariable("QAIRT_SDK_ROOT")
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

android {
    namespace = "com.onetake.engine.android"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    defaultConfig {
        minSdk = 24
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += "-DQAIRT_SDK_ROOT=${qairtSdk.orNull.orEmpty()}"
            }
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
tasks.named("preBuild").configure { dependsOn(stageQairtRuntime) }
dependencies {
    api(project(":engine"))
    testImplementation(libs.junit)
}

androidComponents {
    onVariants { variant ->
        if (qairtSdk.isPresent) {
            variant.sources.jniLibs?.addStaticSourceDirectory(qairtLibraries.get().asFile.absolutePath)
        }
    }
}
