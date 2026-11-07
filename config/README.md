# Competition configuration

The existing source layout is retained instead of duplicating the Android tree.
The pinned candidate is `25ae8eee`; [submission identity](../docs/SUBMISSION.md)
keeps its source, APK, ZIP and model-bundle digests distinct.

| Configuration | Source of truth |
| --- | --- |
| Three-group schema-2 embedded model sources, revisions, sizes, hashes and licences | [embedded-models.json](../prototypes/phone-transcriber/config/embedded-models.json) |
| Installed model validation, atomic extraction and leases | `EmbeddedModels.kt`, `EmbeddedModelInstaller.kt`, `ModelDownloads.kt` in the [app source](../prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/) |
| Android 12+ / ARM64, offline manifest, R8 and no-compression rules | [app/build.gradle.kts](../prototypes/phone-transcriber/android/app/build.gradle.kts), [offline manifest](../prototypes/phone-transcriber/android/app/src/offline/AndroidManifest.xml) |
| Library versions | [libs.versions.toml](../prototypes/phone-transcriber/android/gradle/libs.versions.toml) |
| Recognition selection | `RecognitionSettings.kt`: remembered `AUTO` by default; Russian uses GigaAM, other languages use bundled Parakeet |
| Text task and bounds | [text-punctuation.md](../docs/interfaces/text-punctuation.md): Qwen2.5 0.5B int8, 64 words/block, 24 tokens/call, post-load soft budget `max(30 s, mediaDuration / 2)` |
| Jury output | [PROTOCOL.md](../docs/PROTOCOL.md); raw recognized words, anonymous IDs, original-media milliseconds |
| Existing acoustic/server schema 1 | [model_pack.py](../prototypes/phone-transcriber/tools/model_pack.py); unchanged by the separate embedded envelope |
| CI and release identity | [android.yml](../.github/workflows/android.yml), [RELEASE.md](../docs/RELEASE.md) |

All three embedded groups are implemented. Text/runtime ARM qualification is
still provisional, as recorded in the manifest; implemented assets are not device
acceptance. Exact source-pin SHA-256:
`13bc4a70941a0349a9fcd5c12b7d7b3b071682cb18de44f98f1b47e6d09cd9f1`.
The candidate's generated bundle SHA-256 is
`8b2ff5619d632f78d5c3473615026d2b032de156c381f3b99f3d77d164d0c792`.
Generated metadata can differ even when model graph hashes remain fixed.

The offline APK has no Internet permission or remote inference path. The compact
prototype's fixed model catalog is distribution only. No API keys, signing keys,
editable backend setting or user media belong in this directory.
