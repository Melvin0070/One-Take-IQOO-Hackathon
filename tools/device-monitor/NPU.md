# NPU monitoring on the iQOO 15

This workflow targets the tested iQOO 15 (`SM8850`, Android 16) with a local Qualcomm QAIRT SDK.
It does not claim universal NPU access or a device-wide utilization percentage.

## What each measurement means

| Evidence | Meaning |
| --- | --- |
| `npu_temperature_nsp0` through `nsp7` | Vendor-reported type 9 NPU temperature sensors, in Celsius |
| Successful `QnnHtp` model execution | The configured model engine executed through Qualcomm's HTP backend |
| Genie token statistics | Prompt processing rate, time to first token, generated tokens, and token generation rate |
| Genie trace events | Framework and QNN-wrapper function durations, including model loading and execution calls |
| `npu_utilization: unsupported` | No validated NPU occupancy/utilization counter is collected |

Framework call duration includes host-side work and waiting; it is not an NPU hardware utilization measurement.
CPU tokenization and sampling can coexist with an HTP model engine.
Genie profiling does not enable QNN detailed per-operation or optrace profiling by itself.
NPU frequency, power, and hardware occupancy remain unverified.

## Model and SDK

The first model is Qualcomm's precompiled **Qwen3-0.6B**, w4a16, for Snapdragon 8 Elite Gen 5.
The [official model card](https://huggingface.co/qualcomm/Qwen3-0.6B) links to the correct chipset bundle and its terms.
Do not use a CPU/GPU GGUF bundle as evidence of HTP execution.
The downloaded bundle is compiled with QAIRT 2.45.
The installed QAIRT 2.50.0.260828 runtime successfully loaded and executed it on this phone; this validates this combination only.
Prefer matching SDK versions when preparing a different model bundle.
The upstream card indicates Genie support will be deprecated; keep this capture adapter isolated so a future GenieX adapter can replace it.

The SDK and model files are local dependencies, not vendored source files.
Do not commit `.cache/`, `runs/`, downloaded weights, or SDK libraries.
The reusable runner accepts explicit paths and does not download models or install an APK.
It pushes a prepared bundle and a limited set of runtime libraries into a uniquely named device workspace, runs a bounded inference, pulls results, and removes that workspace.
It rejects non-SM8850 devices and engines not explicitly configured as `QnnHtp`.
It preserves the original bundle config and writes a separate run config with a response token limit.

## Run an experiment

From the repository root, use the installed SDK and downloaded bundle:

```sh
qairt_sdk=/Users/sabari/Downloads/qairt/2.50.0.260828
qwen_bundle=tools/device-monitor/.cache/models/qwen3-0.6b-sm8850/qwen3_0_6b-genie-w4a16-qualcomm_snapdragon_8_elite_gen5
python3 tools/device-monitor/genie_run.py \
  --sdk "$qairt_sdk" \
  --bundle "$qwen_bundle" \
  --prompt-file tools/device-monitor/runs/qwen-prompt.txt \
  --max-tokens 128 \
  --output tools/device-monitor/runs/qwen-new-experiment
```

Use a new output directory each time.
Provide a prompt file in the model's expected chat format; the example prompt from initial verification is retained under `runs/` locally.
The token cap can truncate a reasoning response before its final answer, so check `response.txt` rather than treating any generated text as a correctness pass.
The device-side inference timeout is 120 seconds.
Prompt and generated text remain on the development computer and connected phone.

For temperature and system context, start a sampled recording in a second terminal before inference:

```sh
python3 tools/device-monitor/device_monitor.py record \
  --duration 60 --interval 1 \
  --output tools/device-monitor/runs/qwen-system-new.ndjson
python3 tools/device-monitor/dashboard.py tools/device-monitor/runs/qwen-system-new.ndjson
```

The model upload precedes inference and can consume a significant part of a short sampling window.
Do not infer sustained thermal behavior from a subsecond query.
These sampled and Genie traces have separate time origins; they are not automatically synchronized or merged.

## Output files

- `run.json`: requested backend, SDK and chipset, source/executed config hashes, query metrics, completion/failure, and remote cleanup result.
- `run-config.json`: the actual configuration sent to the phone.
- `prompt.txt` and `response.txt`: the exact input and generated output.
- `profile.json`: the unmodified SDK profile, including token statistics and trace events.
- `stdout.txt` and `stderr.txt`: raw runtime diagnostics.
- `profile-config.json` and `run-script.txt`: the profiling recipe sent to `genie-app`.

Retain raw evidence when a run fails.
A successful runtime exit must also produce a text response, a query profiling event, and trace events before the runner records completion.
Check `remote_cleanup_succeeded` if the phone disconnects.
Only remove the unique workspace recorded for that run when cleaning up manually.

## Perfetto trace import

The SDK's raw profile may contain crossing synchronous slices on the same reported thread.
Perfetto flags these as overlapping import errors.
Use the derived trace exporter to retain all timestamps and durations while placing crossing slices on additional display lanes:

```sh
python3 tools/device-monitor/genie_trace.py \
  tools/device-monitor/runs/qwen-npu-verified/profile.json \
  tools/device-monitor/runs/qwen-npu-verified/perfetto.json
python3 tools/device-monitor/perfetto_local.py serve --port 10003 \
  --trace tools/device-monitor/runs/qwen-npu-verified/perfetto.json
```

Open the exact URL printed by the server.
Extra visual lanes do not represent additional OS threads or NPU cores.
Keep `profile.json` as the original evidence; the derived view must not change event times or discard overlaps.

## Verification

Run the existing monitoring tests and verify a real on-device query and Perfetto import after changing the integration.
See [VERIFICATION.md](VERIFICATION.md) for dated measurements and limitations.
For sustained performance comparisons, repeat a fixed workload after controlling thermal starting state and distinguish cold model loading from token generation.

## Standalone QNN hardware smoke test

To repeat the separate SDK convolution/ReLU test with detailed HTP profiling:

```sh
python3 tools/device-monitor/qnn_smoke.py \
  --sdk /Users/sabari/Downloads/qairt/2.50.0.260828 \
  --ndk /Users/sabari/Library/Android/sdk/ndk/30.0.16248370 \
  --serial SERIAL --compare-cpu \
  --output-dir tools/device-monitor/runs/qnn-new-experiment
```

Discover the current serial with `device_monitor.py devices` and replace `SERIAL`.
Inspect `manifest.json`, `htp-profile.csv`, and `output-comparison.json` separately.
Execution success does not mean the optional CPU comparison passed.
The verified hardware run produced detailed accelerator timings and cycles, but its CPU comparison exceeded the configured tolerance.
The Chrome reader also failed on that detailed-profile capture; use the CSV for this test.
These measurements apply to the SDK sample, not automatically to Qwen inference.
