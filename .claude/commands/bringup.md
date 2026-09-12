---
description: Device bring-up harness — characterize the loaner before building features
---

Build or run the device bring-up harness described in
`docs/agents/workflow.md#device-bring-up`.

The first thirty minutes on a phone buy **device characterization, not features**. Every
item below is a Saturday-night surprise you would otherwise find at 23:00. One debug-panel
screen reporting all of it in one place:

- [ ] `SENSOR_INFO_TIMESTAMP_SOURCE`
- [ ] **Concurrent capture on THIS device** — landmines L9: all three assertions, both start
      orders, buffers checked directly for zeros
- [ ] **ASR is not silently empty** — landmines L1. Product-killer, no log line
- [ ] Which accelerator LiteRT actually chose, and per-inference µs — landmines L6's
      `Accelerator.NONE` route, which is undocumented and must be confirmed on-device
- [ ] Streaming zipformer RTF, measured. Nobody has published this number
- [ ] `CamcorderProfile.get(cameraId, QUALITY_1080P).videoBitRate` → real MB/min
- [ ] `getCurrentThermalStatus()` and battery
- [ ] Bluetooth remote: does it pair, **what keycode does it emit**, how many buttons. Log
      unhandled keycodes so an unknown remote is supported in thirty seconds. Return `true`
      from `onKeyDown` for volume keys or the system volume panel covers the viewfinder
- [ ] Free storage and estimated recordable minutes
- [ ] Whether a `mediaProcessing` foreground service survives
- [ ] Flag-vector checksum and config version (R25)

Check `adb devices` first. Report each item as measured / failed / not run — never infer one
from another, and never report a number you did not observe.
