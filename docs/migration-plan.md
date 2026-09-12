# Project migration plan

Migrate the existing recorder from `MyApplication` into `OneTake` in dependency order.
Preserve the source files in their original directory.
Keep `One-Take` as the project name and `com.example.one_take` as the app identity.

## Commit cadence

Create and push one commit per section after its checks pass.
Leave at least 10 minutes of real elapsed time between migration commits.
Do not rewrite commit dates.

## Sections

1. Build and editing engine: copy the pure Kotlin engine and tests, configure its module and the dependencies required by the incoming app, and keep the existing launcher working.
2. Native runtime and bundled models: copy the pinned Whisper, Silero, and WebRTC VAD sources and notices, JNI bridges, CMake build, and bundled face and VAD models.
3. Media and capture services: migrate storage, caption generation, audio analysis, reversible edits, event persistence, vision, and guidance, with their existing unit tests.
4. Recorder interface: migrate camera, review, library, feature marketplace, theme, resources, manifest, and instrumentation tests, and replace the boilerplate launcher.
5. Developer tools and documentation: migrate the standalone device monitor and its tests, usage documentation, and source project guidance.

## Verification

Run the engine tests and Android build after the foundation.
Build both configured native ABIs after the runtime section.
Run migrated unit tests as each service section arrives.
Run Android lint, debug APK and instrumentation APK builds after app integration.
Use the connected phone for native classifier and recorder flow tests with the new app identity.
Use direct instrumentation, avoiding the connected-test runner that uninstalls the app during cleanup.
Run the monitoring Python suite and a bounded device probe/capture.
Review each staged diff and verify the remote commit after each push.
Compare migrated files with the source at completion, accounting for package, JNI, branding, and documented configuration changes.

## Exclusions

Do not copy build output, Gradle/Kotlin/native caches, machine-local SDK paths, IDE session state, downloaded Perfetto UI, device traces, or run output.
Do not read or copy `.env` files.
Preserve upstream source and model bytes and their license notices.
Historical verification documents remain historical; record new evidence separately.
