# Install and use the offline Android candidate

Use **`ascent-offline.apk`** for competition review, not the compact network APK.
Candidate source `25ae8eee` passed both Android CI jobs; it remains an internal
candidate with ARM/final acceptance open. Exact hashes, expiry and pending durable
publication are in [SUBMISSION.md](SUBMISSION.md#candidate-identity).

## 1. Obtain the identified APK

1. Download **[ascent-offline-25ae8eee.apk from Google Drive](https://drive.google.com/file/d/1lC7Li-dU0CfahqT3Pn8S-8aXor2poaVA/view?usp=sharing)**.
   The public preview exposes the expected filename; the remote file has not yet
   been independently downloaded/rehashed. Compare its raw hash below.
   Alternatively, open [offline-baseline-25ae8eee](https://github.com/pmixay/Ascent/actions/runs/42420616552/artifacts/13594571558)
   from the [successful CI run](https://github.com/pmixay/Ascent/actions/runs/42420616552).
   GitHub sign-in/read access may be required. The ZIP expires **14 November 2026,
   22:54:39 UTC**; this expiry applies to CI backup, not the Drive link. This is attempt **2**;
   attempt 1's removed artifact and differently signed APK are not substituted.
2. Extract `ascent-offline.apk`. The **raw APK** is **2,167,298,546 bytes**,
   SHA-256 `5fe9f857db4bbce6bb20ee36d2edb525a764e5cc38a0b34499bc5005846a0e4d`.
   This is not the ZIP hash. Ordinary `shasum -a 256 ascent-offline.apk` or an
   equivalent file hashing utility can compare bytes without building the app.
3. Use an **Android 12+ ARM64** device or ARM64 Android emulator. Package:
   `com.pmixay.ascent.offline`; version `0.1.156-25ae8eee-offline-baseline`, code 156.
   Do not install this ARM64 download on an x86-only emulator. x86_64 CI APKs are
   validation variants, not distributed phone files.

## 2. Install and prepare models locally

- Allow installation from the chosen source in Android's system settings, open the
  APK and complete installation. If ADB already exists, `adb install ascent-offline.apk`
  is an alternative; no local SDK/toolchain setup or app build is required.
- Use a fresh authorized test install/profile for cold-offline evidence; removing
  app data or uninstalling destroys encrypted history. Do not remove a valuable
  existing installation without the owner's permission. Existing debug-signed
  builds can have different certificates: an incompatible-update error is not
  fixed by silently deleting history.
- Disable Wi-Fi/mobile data before the first launch to demonstrate offline startup.
  Open Ascent and wait for `Встроенные модели готовы`. Extraction verifies all
  three bundled groups: acoustic, multilingual and text. There is no model download
  or Internet permission in this variant. A failed preparation must not be hidden
  by installing models through another app or enabling networking.

**Storage:** the APK is about 2.17 GB and extracted model payload is
**2,147,012,171 bytes**. Android installation copies, native extraction and reserve
need additional space. These two numbers are not a measured phone peak/free-space
recommendation. CI-only x86_64 observations are in [TEST_RESULTS.md](TEST_RESULTS.md).
If the app reports insufficient space, free space and reopen. Interrupted model
extraction is retried with validation; a partially extracted set is not ready.

## 3. Import and transcribe

1. Tap `Язык` and choose `Русский` for Russian, `Другие языки` for English, or
   `Авто` (remembered default). Auto is a heuristic based on early recognizer output,
   not a mixed-language guarantee. The mode shown after completion is the actual
   recognizer used, distinct from the next job's selection.
2. Tap **`Импорт аудио`** and select a local file using Android's document picker.
   Microphone permission is not required. Canceling the picker does not start a job.
3. Wait for actual recognition, speaker separation, local text processing and save.
   The app reads the selected URI in place; it does not modify or delete your file.
   One job runs at a time. `Отменить обработку` requests cancellation; native calls
   can finish their current work before cancellation takes effect.
4. Open the saved conversation. Read participant turns and source-relative times.
   Times are approximate; a label is not a confirmed real-world identity. Check the
   separate text-model report: failed/unavailable Qwen is **not** LLM acceptance.
5. Total/media duration and completed RTF appear for the last job; expand
   `Показать время по этапам` for exclusive timings. These values are volatile and
   include model loading, text, save and cleanup, not prior model extraction.

### Input limits

Source declares seekable WAV PCM 8/16/24/32-bit, float32, A-law/μ-law, 1–8 channels,
4–192 kHz; conversion supplies bounded mono 16 kHz windows. Other audio uses the
device's Android decoder, including MP3/AAC/M4A where supported. WAV limit: two
hours; compressed audio and microphone: 30 minutes. Unknown compressed duration,
RF64 WAV and unsupported/nonseekable providers may be rejected. These are source
limits, not measured phone capacity or a promise of every codec/provider. Save a
provider's file locally if it cannot supply seekable input.

## 4. Export the jury JSON

1. In the desired conversation select **`Экспорт JSON`**, choose the save destination and
   wait for confirmation. Export preserves the recognized words, not Qwen's
   presentation punctuation. Participant renaming does not change exported IDs.
2. The root is `transcript`; entries contain exactly `speaker_id` (string),
   `start_ms` / `end_ms` (integer milliseconds) and `text` (string). Times refer to
   the original file including silence. Silent input exports `{"transcript":[]}`.
3. TXT is optional presentation export, not a substitute for the required JSON.
   A provider/write error leaves saved text intact so export can be retried.

The independent checker on a host can inspect an exported file without building
Android or running model inference:

```sh
python3 tools/qa/validate_transcript.py exported.json --duration-ms 30000
```

Supply the actual source duration, not an assumed value from JSON. Schema checking
does not measure recognition, speakers or boundary accuracy. Protocol:
[PROTOCOL.md](PROTOCOL.md). No host processing substitutes for the submitted app.

## 5. Optional microphone and interruption behavior

`Начать разговор` requests microphone permission. Stop listening to process
automatically; there is no separate transcribe button. This prototype writes a
temporary microphone WAV, attempts cleanup after processing/failure, and cleans
abandoned audio on next launch. It does **not** establish production zero audio
persistence. File import does not use this temporary-WAV path.

Rotation retains the ViewModel; ordinary backgrounding does not deliberately stop
an import, but no transcription foreground service guarantees process survival.
Force-stop/process death loses the active job. Reopen reports interruption and
asks for file reselection while preserving committed history. No audio recovery
is promised. See [limitations](LIMITATIONS.md) for unverified conditions.

## Compact and watch variants are not required for this handoff

The compact package `com.pmixay.ascent` downloads acoustic models from the fixed
HTTPS catalog. It has no Qwen/Parakeet assets, refuses fixed multilingual mode and
cannot satisfy fresh-offline/local-LLM acceptance. Its [attempt-2 artifact](https://github.com/pmixay/Ascent/actions/runs/42420616552/artifacts/13594382036)
is available; it is optional for this handoff and not a substitute for the offline APK.

Wear OS is a separate preview, unnecessary for importing local files. Pairing,
capture, screen-off behavior and power measurements remain unverified. The
competition demonstration does not need a watch.
