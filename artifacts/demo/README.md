# Demonstration video plan and release record

No demonstration video or final verification APK is linked yet. Record the video only after the final APK and model configuration are frozen, and fill the record below from the same device run. A shortened wait or edited cut must be labeled onscreen; do not imply it was uninterrupted real-time processing.

| Item | Verified release value |
| --- | --- |
| Durable video URL | pending |
| Durable APK URL | pending |
| APK commit and SHA-256 | pending |
| Model manifest and SHA-256 | pending |
| Device, Android version and ABI | pending |
| CI run and all required job results | pending |
| Recording date and source case IDs | pending |

## Shot list for the exact APK

1. Show the phone's Android version, app installation and airplane mode with Wi-Fi/mobile data off before first launch. Display the app's model-readiness state and the package identifier; record the exact APK/model hashes in the release table above.
2. Show the `Язык` selector and choose `Русский`. Select an unfamiliar, authorized call-like file from the system document picker. Show filename/format/duration when available, then the actual processing stages, actual-used mode and total/media/RTF summary. Expand the exclusive stage timings; keep extraction time separate. Keep one continuous shot through completion or label any cut.
3. Read several participant turns, including a short reply and a pause, with source-relative timestamps. Avoid showing private participant names or raw source paths.
4. Export the exact JSON through Android's save picker, open the file and show `transcript`, `speaker_id`, integer `start_ms`/`end_ms` and `text`. Show the independent validator's result on that exported file.
5. Repeat import/export with a meeting-like file and an English file using `Другие языки` (mixed-language behavior is a separate unqualified check) on the same installation. If time is shortened, disclose each cut and leave the full timings in `docs/TEST_RESULTS.md`.
6. Show a genuine-silence file producing an empty transcript and `{"transcript":[]}` export. Close with the final release/hash and direct links to architecture, limitations and measured results.
7. If the companion is included in the delivered release, show the paired Galaxy Watch7 start, pause/resume, stop, connection/gap indicator and the latest phone-produced text on the exact watch and phone APKs. Give both APK hashes and label any missing device result as unverified.

Use only media with documented permission. Publish a video that remains reachable for the jury, not only a short-lived CI artifact. The demonstration establishes behavior of the shown build; the independent test matrix establishes its wider limits.
