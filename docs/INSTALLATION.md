# Android verification build: installation and use

The jury-ready offline verification APK is **not yet available** in this repository. The current compact `ascent.apk` is a prototype whose model pack is downloaded after installation. Use the steps below only after a final CI run, model manifest, APK hash and durable release link have been recorded in [TEST_RESULTS.md](TEST_RESULTS.md). An Actions artifact alone expires after 14 days and is not a durable submission link.

## Target device and package

The Android module currently declares Android 12+ (`minSdk 31`) and builds ARM64 (`arm64-v8a`) for download. Its x86_64 variant is for CI instrumentation. Current source accepts seekable PCM WAV with 8- or 16-bit samples, one or two channels and 8–96 kHz input rates, plus other audio tracks decodable by the device's Android media stack. The latter format support is device dependent and unverified; unsupported sources must show an error. Both paths limit known media duration to about 30 minutes. The final verification build must include all runtime weights, tokenizer and local text-model assets needed for the default pipeline; the actual APK size, installed storage requirement, model version/hash, signing identity and ARM performance remain to be measured. Leave enough free storage for model extraction and results after measuring the exact final package. Do not infer an installed size from the compressed ZIP.

## Install the exact inspected build

1. Open the durable release link listed in the final [TEST_RESULTS.md](TEST_RESULTS.md). Confirm the linked commit, all required CI jobs and SHA-256 match the release record. Download `ascent.apk` from that release.
2. On the test device, remove the earlier Ascent installation and app data. Turn off Wi-Fi and mobile data and enable airplane mode **before** first launch. For a fresh offline test, do not preinstall or predownload models.
3. Install the APK using Android's package installer, or from a development host with `adb install ascent.apk`. Confirm that Android reports a successful install. Keep the tested APK and hash fixed for the entire three-file rehearsal.
4. Open Ascent and wait for model readiness. If the first launch requires a network request, record a failed offline readiness case; do not silently turn networking back on to call the trial a pass.
5. Select **Import audio** (Russian UI: `Импорт аудио`), choose a user-owned file in Android's document picker and wait for processing. Import must work without microphone permission. Inspect the result's participant turns and times.
6. Choose the JSON export action, select a destination through Android's save picker, and copy the saved file to a host for the independent validator. Repeat for two unrelated files on the same installation. See [QA_JUDGE_SCRIPT.md](QA_JUDGE_SCRIPT.md) for negative and lifecycle cases.

For a known 30-second input, the checker invocation is:

```sh
python3 tools/qa/validate_transcript.py exported.json --duration-ms 30000
```

The duration must come from the original file, not from the exported JSON. If the UI wording changes before release, revise these instructions against the actual installed APK and record the final screenshots/video. A successful JSON shape check does not establish word or speaker accuracy.

## Companion watch preview

The new watch module targets Wear OS 6.0 on the Galaxy Watch7 SM-L305F. The phone baseline remains Android 12+; the named first phone test target is Samsung Android 17. A CI-built watch APK and paired-device trial have not yet been recorded. When both matching APKs exist, install the phone and watch packages on their respective devices, pair them through Bluetooth, make the phone's watch receiver visible/active, and open Ascent. on the watch. Grant `RECORD_AUDIO` and `BLUETOOTH_CONNECT` on the watch; notification permission is requested separately. Choose the bonded phone, start listening, then try pause, resume and stop. The watch should show real connection state, elapsed time, dropped frames and the latest phone-produced text. A phone without a ready local model rejects the watch handshake. See [QA_JUDGE_SCRIPT.md](QA_JUDGE_SCRIPT.md) for the unrun watch matrix.

