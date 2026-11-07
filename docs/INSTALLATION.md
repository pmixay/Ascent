# Android verification build: installation and use

The jury-ready offline verification APK is **not yet available** in this repository. The current compact `ascent.apk` is a prototype whose acoustic model pack is downloaded after installation. Its download server has been [repaired and verified](roles/PETYA_SERVER_STATUS.md). Actions artifacts expire at the date reported by GitHub and are not durable submission links.

## Current compact prototype: phone trial

The user will perform these phone trials later. Use the identified CI candidate
from [TEST_RESULTS.md](TEST_RESULTS.md), not an APK from Pasha's earlier branch.

1. Download the `debug-<commit>` artifact from its successful Android CI run,
   extract `ascent.apk` and check its SHA-256 against the candidate record.
2. Install on an Android 12+ ARM64 phone. Keep networking enabled for the first
   acoustic-model download and wait until the separate model panel says ready.
   Record the APK hash, model manifest digest, device/Android/ABI and free storage.
3. Disable networking. Select `Импорт аудио` and import three unrelated files
   consecutively. Microphone permission is unnecessary for import. Inspect the
   filename, source-relative times and participant turns; export each result as
   JSON and run the independent validator with the original media duration.
4. Include silence, a cancelled picker, bad input, cancellation followed by a new
   job and force-stop/reopen. The interrupted-job notice must appear once; saved
   history and the original source must remain intact. Follow the complete
   [judge script](QA_JUDGE_SCRIPT.md) and record outcomes without filling unrun cases.

The compact artifact includes no text or Parakeet assets. Auto stays on the Russian
recognizer; selecting `Другие языки` shows an unavailable-model message and refuses
processing until another mode is chosen. Its text-stage status
must report unavailable and preserve recognized words. This trial verifies the
prototype after download; it does not satisfy fresh offline/local-LLM acceptance.

## Embedded baseline: fresh offline phone trial

After both jobs pass on the combined branch, the same Android CI run provides
`offline-baseline-<commit>` containing `ascent-offline.apk`. This separate package
uses `com.pmixay.ascent.offline`, so it can coexist with the compact app. Verify
its APK/bundle identities in the candidate record before installing.

1. Use a fresh offline-package install/data state. Disable Wi-Fi/mobile data before
   first launch; no model predownload is needed. Record available storage, including
   space for the APK, Android's install copies and **2,147,011,731 bytes** of expanded
   model payloads, plus attribution/metadata. Schema 2 includes acoustic, text and
   Parakeet groups. This payload figure is not a measured installed or peak storage need.
2. Wait for local verification/extraction of all three model groups. The offline app
   has no Internet permission and shows disabled automatic updates.
3. Set `Язык` before import or phone capture. `Авто` is the remembered default;
   choose `Русский` for Russian and `Другие языки` for English comparison trials.
   Auto uses an early-window probe and is not a promise of mixed-language support.
   The result status identifies the recognizer actually used.
4. Run the three-file judge procedure, including import without microphone
   permission, microphone capture, cancellation and reopen. Record actual text-stage
   processed/fallback counts and time. Missing/failed text processing is an open
   acceptance failure, even when an acoustic transcript is preserved.
5. Record total/media time and completed RTF; expand `Показать время по этапам`
   for exclusive stage durations. These values are only in memory. Record cold/warm
   conditions, peak memory and the extraction/lifecycle negative cases separately.
   CI's bounded x86_64 extraction/text check does not perform ARM model inference.

## Target device and package

The Android module currently declares Android 12+ (`minSdk 31`) and builds ARM64 (`arm64-v8a`) for download. Its x86_64 variant is for CI instrumentation. Current source accepts seekable WAV PCM 8/16/24/32, float32, A-law/μ-law, one to eight channels and 4–192 kHz input rates, plus other audio tracks decodable by the device's Android media stack. The latter format support is device dependent and unverified; unsupported sources must show an error. The WAV adapter declares a two-hour limit; the compressed decoder declares 30 minutes. These are source limits, not measured phone capacity. The final verification build must include all runtime weights, tokenizer and local text-model assets needed for the default pipeline; the actual APK size, installed storage requirement, model version/hash, signing identity and ARM performance remain to be measured. Leave enough free storage for model extraction and results after measuring the exact final package. Do not infer an installed size from the compressed ZIP.

## Install the exact inspected build

These are the **future embedded-release** steps. Use them only after a final
offline APK, successful CI, model/APK hashes and durable release link are recorded.

1. Open the durable release link listed in the final [TEST_RESULTS.md](TEST_RESULTS.md). Confirm the linked commit, all required CI jobs and SHA-256 match the release record. Download the identified offline APK from that release (the CI baseline is named `ascent-offline.apk`).
2. On the test device, remove the earlier Ascent installation and app data. Turn off Wi-Fi and mobile data and enable airplane mode **before** first launch. For a fresh offline test, do not preinstall or predownload models.
3. Install the APK using Android's package installer, or from a development host with `adb install ascent-offline.apk`. Confirm that Android reports a successful install. Keep the tested APK and hash fixed for the entire three-file rehearsal.
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

