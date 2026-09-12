# One-Take

**A script supervisor in the viewfinder.** While you film, it knows which lines of your
script still lack a clean take, asks for exactly those between lines, tells you when it is
safe to wrap, and plays the finished, captioned cut the moment you stop. On the phone,
offline.

Post-hoc tools already cut, caption and clean up. None of them can fix a take that was
never recorded — you find out at the desk that line 3 has no clean read, after the light,
the setup and the energy are gone. One-Take moves that check to the moment of capture.

Built for the iQOO City Battles, Chennai, Sep 12–13 2026. Android, Kotlin, Compose.

---

## Building

There is no system JDK on the build machines — use Android Studio's bundled one:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

./gradlew :engine:test          # the brain, pure JVM, ~2 seconds
./gradlew verify                # tests + assembleDebug + guards. The gate.
./gradlew :app:assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Never `adb uninstall`.** It wipes imported models and recorded sessions from the device.
`-r` updates in place.

## Modules

```
:engine          PURE JVM. Interfaces, aligner, ledger, coverage fold, edit-list rules.
:engine-fixtures Fakes, corpus loader, golden ledgers.
:eval            corpus -> eval card, one command.
:asr             sherpa-onnx CPU: VAD, streaming transducer, keyword spotting.
:npu             LiteRT + Qualcomm accelerator. The only module that links QNN.
:capture         CameraX, AudioRecord, foreground service, the timebase anchor.
:media           Media3 playback from the edit list, Transformer export, captions.
:link            Multicam pairing and encrypted transfer. Tier 2.
:app             Compose UI, every screen, debug panel.
```

`:engine` has no Android dependency and cannot compile against one — it uses the Kotlin JVM
plugin, so `import android.` is a compile error. That is what makes its test suite two
seconds instead of two minutes on a device.

## The debug keystore is committed on purpose

`config/debug.keystore` is in the repository, with its password in
`app/build.gradle.kts`. This is deliberate and it is not a secret:

- It signs **debug builds only.**
- It exists so three laptops and CI can all `adb install -r` over the same installation on a
  test phone. Without a shared key, every machine's build is a different signature and the
  install fails unless you uninstall first — and uninstalling wipes the models and recorded
  sessions on that phone.
- **No release artifact is ever signed with it.** Nothing signed by this key is distributed.

Model files and their hashes stay out of git. They arrive on the device by file transfer and
are SHA-256 verified at import against a checked-in manifest.

## Build guards

Three checks run in `./gradlew guards` and they exist because a hackathon with many agents
adding dependencies is exactly how an "offline" app quietly acquires `INTERNET`:

| Task | What it enforces |
|---|---|
| `guardPermissions` | The **merged** manifest's permission list equals `config/allowed-permissions.txt`. A library that adds a permission fails the build |
| `guardDependencies` | Every resolved dependency is in `config/allowed-dependencies.txt`. No analytics, ads or crash-reporting SDKs, and no second ONNX or QNN runtime |
| `generateAttribution` | Regenerates `ATTRIBUTION.md` from the real resolved dependency graph |

## Privacy

The single-phone path makes no network calls, and the demo build has no `INTERNET`
permission — checkable in the merged manifest. Raw video, audio, transcripts and ledgers
stay in app-private storage until an explicit export. Delete project removes raw files, the
ledger, transcripts and app-made exports. No third-party analytics, ads or crash reporting.
Release builds have no debug panel.

Multicam would need `INTERNET` (Android requires it even for local sockets). If it ships, it
ships as a separate build flavour and the claim changes with it.

## Documentation

| For | Read |
|---|---|
| **AI agents and contributors** | [`AGENTS.md`](AGENTS.md), then [`docs/agents/`](docs/agents/README.md) |
| Why the product is shaped this way | [`One-Take-Design-v3.md`](One-Take-Design-v3.md) |
| Module boundaries, interfaces, verified library research | [`One-Take-Contract.md`](One-Take-Contract.md) |
| Ordering, gates, fallbacks | [`One-Take-Playbook.md`](One-Take-Playbook.md) |

## Also in this repository

- **[`tools/device-monitor/`](tools/device-monitor/README.md)** — a standalone ADB profiler
  that collects device measurements, exports NDJSON, and hosts the real Perfetto UI locally
  for native traces and sampled sessions. Runs on the development machine; needs no app
  changes.
- **`experiments/`** — the pre-event prototype (a CameraX recorder with on-device Whisper
  captions and pause cutting). **Not in the Gradle build.** Kept as a mining reference for
  working CameraX, VAD, JNI and Media3 patterns. See
  [`experiments/AGENTS.md`](experiments/AGENTS.md).

## Status

The scaffold exists; the product does not. `:engine` carries the frozen interfaces and
`TODO` at every behavioural boundary; the other modules are shells. Nothing has run on the
target device yet, and several pinned choices are researched but unproven on this hardware —
the bring-up harness exists to prove or kill each of them in the first thirty minutes on a
loaner.
