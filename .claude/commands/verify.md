---
description: Run the full verification gate and report honestly
---

Run the gate and report what actually happened.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew verify
```

That is `:engine:test` + `:engine-fixtures:test` + `:eval:test` + `:app:assembleDebug` +
`guards` (R17 permissions on the merged manifest, and the P4 dependency allowlist).

If anything fails:

- Show the failing output. Do not summarise it away.
- Fix it if the cause is clear and inside the current work.
- If a guard failed, the fix is almost never to widen the allowlist. A new permission or a
  new dependency arriving unannounced usually means a library brought it — find which, and
  ask whether that library belongs here at all.

Then report in two lines: what passed, what failed, and anything that was **not** covered —
device-only behaviour, timing, audio quality. A green suite that does not exercise the thing
you changed is not evidence, and saying so is more useful than a clean summary.
