pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Vendored AARs (sherpa-onnx static-link). See libs/README.md.
        flatDir { dirs("libs") }
    }
}

rootProject.name = "One-Take"

// The module graph IS the parallelization plan (docs/agents/architecture.md).
// Pure JVM — no android.* anywhere below this line.
include(":engine")
include(":engine-fixtures")
include(":eval")
// Android libraries — one lane each, no shared files.
include(":asr")
include(":npu")
include(":capture")
include(":media")
include(":link")
// The app.
include(":app")

// experiments/ is deliberately NOT included. It is the pre-event prototype, kept
// on disk as a mining reference only. See experiments/AGENTS.md.
