# Demonstration video plan and release record

## Real offline ARM64 demonstration — 8 November 2026

**[Watch/download the offline demo](ascent-offline-arm64-25ae8eee.mp4)**
([raw MP4](https://raw.githubusercontent.com/pmixay/Ascent/main/artifacts/demo/ascent-offline-arm64-25ae8eee.mp4)).

The exact CI-built attempt-2 APK was installed on the newly created
`Ascent_ARM64_Demo` emulator, Android 16/API 36, native ARM64 on M1 Pro with
Hypervisor.Framework, 6 GiB guest RAM and four cores. APK SHA-256:
`5fe9f857db4bbce6bb20ee36d2edb525a764e5cc38a0b34499bc5005846a0e4d`.
Version/package/ABI were checked through Android; the preparation log identifies
the exact source/bundle and ARM64 runtime. No local app build or automated Android
test task was run. Runtime/management tools were installed by explicit user request.

Observed with airplane mode enabled and Wi-Fi/data disabled before first launch:
- Fresh local model extraction: **10,320 ms**, all 2,147,012,171 payload bytes.
- Real system-picker WAV import without requesting microphone permission.
- Russian ASR and two displayed participants, followed by successful Qwen:
  **6/6 phrases, zero fallback, 20.8 s displayed text time**.
- Full displayed processing **46.965 s** for **35.670 s** audio, **RTF 1.317**.
- Actual JSON save through the system picker; pulled export has six turns and
  independently passes schema, order and time bounds for 35,670 ms. The video
  shows saving, not a JSON content viewer. Export remains private evaluation output,
  not a published transcript artifact. Source hash was unchanged afterward.

Input is four known public regression clips from two LibriVox readers, with
inserted 500 ms pauses: **a constructed demonstration, not a natural dialogue or
untouched quality benchmark**. Credits: Russian LibriSpeech/OpenSLR96, LibriVox,
Foreign Girl, albus865 and the istupakov mirror. Audio is identified as public
domain in the USA; mirror metadata CC-BY-4.0. Exact construction/source identifiers
and content-free evidence: [arm64-evidence.json](arm64-evidence.json).

The 235.135575-second silent video joins two sequential screen recordings with a
gap at about 02:58, explicitly labeled onscreen. No speed changes; factual title
bars added. SHA-256 `90222b101432248abdd76710de872a06d98272d83123215e980773108388e794`,
2,875,853 bytes. This closes the missing exact-APK Russian/local-LLM demo, **not**
English/Parakeet inference, three-file QA, physical phone quality, natural WER/DER,
all lifecycle cases, stable signing or final release approval.

The older prototype and proposed extended recording script below are historical
where they say no offline video or ARM execution exists. Their unperformed extra
cases remain open. The supplied prototype is not relabeled as this offline run.

## Supplied prototype recording

[Watch/download the supplied MP4](ascent-prototype-2026-11-08.mp4)
([raw download](https://raw.githubusercontent.com/pmixay/Ascent/main/artifacts/demo/ascent-prototype-2026-11-08.mp4)).
The user supplied and authorized repository publication of this recording.

- File: `ascent-prototype-2026-11-08.mp4`, 3,149,676 bytes, H.264,
  590 × 1280, duration 26.223722 seconds; **no audio track**.
- SHA-256: `cb1d184a2dae0d504da3ae88f8f14ed908192d15b87fdb0ebfb20b2d788bda58`.
- Observed: microphone permission, phone capture, processing, a saved one-speaker
  phrase and the JSON-export button. Visible counters show 2.440 s input,
  5.497 s processing and RTF 2.253; these are one screen observation, not a benchmark.
- The screen explicitly says **the text model is not installed** and Auto uses
  Russian only because other-language assets are absent. This demonstrates the
  compact/prototype path, **not** the complete embedded/local-LLM candidate.
- Actual package/version, raw APK hash, device model, Android version and ABI are
  not established by the video. The source video filename gives a date, not build
  provenance. Do not assign the offline APK's hash to it.
- Cold offline model preparation, imported-file inference, successful Qwen,
  multiple speakers, English, actual JSON save/content and three-file completion
  are not demonstrated. No broad device acceptance is inferred.

**A real video of the identified offline pipeline remains pending.** The rest of
this page supplies its short script and candidate identity; it is not evidence
that those additional steps already passed.

Latest user assignment: **Petya records the real video** and supplies its file/URL,
actual APK hash and device information. The user uploads the verified local APK
to Google Drive; its public link is pending. Do not substitute a candidate hash
for the actual filmed build if Petya uses a different APK.

Use the final approved identified APK. The current candidate still has open ARM,
signing and release gates; filming a candidate does not close them automatically.
Any later APK substitution needs its own identity and affected retests. Label a
shortened wait or edited cut onscreen; do not imply uninterrupted real-time speed.

| Item | Verified release value |
| --- | --- |
| Available prototype video | [Repository MP4](ascent-prototype-2026-11-08.mp4); not the offline candidate; exact filmed APK unknown |
| Offline candidate demonstration URL | pending |
| APK URL | [Google Drive](https://drive.google.com/file/d/1lC7Li-dU0CfahqT3Pn8S-8aXor2poaVA/view?usp=sharing); remote full-file hash verification pending |
| Planned candidate source / raw APK SHA-256 (not yet filmed) | `25ae8eee`, CI attempt **2** / `5fe9f857db4bbce6bb20ee36d2edb525a764e5cc38a0b34499bc5005846a0e4d` |
| Candidate embedded bundle SHA-256 | `8b2ff5619d632f78d5c3473615026d2b032de156c381f3b99f3d77d164d0c792` |
| Device, Android version and ABI | pending |
| Candidate CI | [42420616552](https://github.com/pmixay/Ascent/actions/runs/42420616552), both jobs passed; 25 compact/four embedded required cases confirmed from actual logs |
| Recording date and source case IDs | pending |

## Minimal actual recording (suggested 2–4 minutes excluding disclosed waits)

The brief requires a video, not a particular duration or three-file video script.
Use an available ARM Android phone **or ARM Android emulator** for the supplied APK;
a physical-phone-only recording is not a separate written jury prerequisite.
Use unfamiliar authorized local media, never a precomputed transcript substituted
for model output. Do not publish private source speech or participant information
without permission.

1. **Identify the build.** Show version `0.1.156-25ae8eee-offline-baseline`, package
   `com.pmixay.ascent.offline`, Android/device or emulator, and Wi-Fi/mobile data off.
   For first-use evidence start from an authorized fresh install and show local
   model preparation. Do not erase valuable history merely to film it.
2. **Import and compute.** Choose `Русский`, tap `Импорт аудио`, select a real local
   call-like or meeting-like file and show actual processing. Explain briefly:
   recognition -> acoustic speaker clustering -> local Qwen punctuation -> encrypted
   saved text. Show the actual text-stage result; failed/unavailable Qwen is not pass.
3. **Read the computed result.** Show at least two participant turns where the input
   has two speakers and their source-relative times. Explain that times are
   approximate and anonymous participant IDs are not verified identities. Show
   measured total/media/RTF if available, without calling it a universal speed claim.
4. **Export.** Tap `Экспорт JSON`, save through Android's picker and show the resulting file
   with `transcript`, `speaker_id`, `start_ms`, `end_ms`, `text`. Do not present the
   documentation example as the generated result. Raw recognized words are exported;
   punctuation/capitals in the UI may differ.
5. **Close.** Show repository/APK/video links and real limitations. Record any error
   honestly instead of editing it into a pass. State which conditions were not tried.

Suggested narration (adapt only to observed behavior):

> Ascent processes imported conversations locally on Android. GigaAM handles
> Russian, Parakeet handles English and other supported languages, ERes2NetV2
> supplies voice embeddings, and Qwen restores punctuation without rewriting words.
> The offline package contains the models and has no Internet permission. Here is
> the actual computed transcript and its exported JSON. This recording demonstrates
> this build only; our report separates host benchmarks, emulator CI and device results.

After recording, fill the actual video URL/build/device/date above and the
`demo` fields in [submission.json](../submission.json). Keep `status` incomplete
until the other [submission gates](../../docs/SUBMISSION.md) really close.

## Extended shot list (optional; not the minimum video requirement)

1. Show the phone's Android version, app installation and airplane mode with Wi-Fi/mobile data off before first launch. Display the app's model-readiness state and the package identifier; record the exact APK/model hashes in the release table above.
2. Show the `Язык` selector and choose `Русский`. Select an unfamiliar, authorized call-like file from the system document picker. Show filename/format/duration when available, then the actual processing stages, actual-used mode and total/media/RTF summary. Expand the exclusive stage timings; keep extraction time separate. Keep one continuous shot through completion or label any cut.
3. Read several participant turns, including a short reply and a pause, with source-relative timestamps. Avoid showing private participant names or raw source paths.
4. Export the exact JSON through Android's save picker, open the file and show `transcript`, `speaker_id`, integer `start_ms`/`end_ms` and `text`. Show the independent validator's result on that exported file.
5. Repeat import/export with a meeting-like file and an English file using `Другие языки` (mixed-language behavior is a separate unqualified check) on the same installation. If time is shortened, disclose each cut and leave the full timings in `docs/TEST_RESULTS.md`.
6. Show a genuine-silence file producing an empty transcript and `{"transcript":[]}` export. Close with the final release/hash and direct links to architecture, limitations and measured results.
7. If the companion is included in the delivered release, show the paired Galaxy Watch7 start, pause/resume, stop, connection/gap indicator and the latest phone-produced text on the exact watch and phone APKs. Give both APK hashes and label any missing device result as unverified.

Use only media with documented permission. Publish a video that remains reachable for the jury, not only a short-lived CI artifact. The demonstration establishes behavior of the shown build; the independent test matrix establishes its wider limits.
