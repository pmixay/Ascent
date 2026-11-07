# Fresh speech holdout custody

Petya owns the new H11 evaluation set. Six English AMI meeting excerpts and six Russian DataHive conversations were selected and hash-pinned on 7 November 2026 **before application inference**. AMI has human annotations that still need review; DataHive supplies no transcripts, so its audio is a source-only holdout until independent references are made. No APK has processed these files. A Russian meeting-like set remains unselected. The old Dialogs `masha_dima_part3` / `masha_sveta_part3` recordings already informed the ASR study, and the earlier reserved recordings informed diarization fixes; neither is a fresh holdout. Do not select cases by inspecting this candidate's predictions.

## Frozen English meeting slice

The source of truth is [ami_holdout_manifest.json](../tools/qa/fixtures/ami_holdout_manifest.json): official AMI headset-mix WAV URLs, six exact meeting IDs, source sizes/SHA-256, manual annotation archive version/hash, and first-600-second crop SHA-256 values. All six are original continuous meeting excerpts, each 10 minutes long, for 60 minutes total; they are **not** assembled dialogues. The source WAVs are mono PCM16 at 16 kHz, and each first-ten-minute annotation set has words from four labeled speaker tracks. The [official AMI download page](https://groups.inf.ed.ac.uk/ami/download/) states the signals and manual annotations are CC BY 4.0.

The source audio and annotation ZIP are cached privately under `/tmp/ascent-ami-holdout` in this development environment and are excluded from Git. The checked preparation script [prepare_ami_holdout.py](../tools/qa/prepare_ami_holdout.py) verifies every source/annotation hash and recreates byte-identical 10-minute WAVs in a chosen output directory. Obtain the seven files at the exact URLs in the manifest, then run:

```sh
python3 tools/qa/prepare_ami_holdout.py \
  --source-dir /private/path/to/ami-sources \
  --output-dir /private/path/to/ami-clips
```

The six hash checks and crop reproduction passed locally. This is **data provenance only**, not an application test, transcript score, annotation audit or speaker-disjoint claim. Word boundaries crossing 600 seconds must be excluded or clipped consistently when extracting references. Record manual review, silence/overlap policy and reference word counts before using these clips for quality claims.

## Frozen Russian call source slice

The [DataHive Russian Conversations sample](https://huggingface.co/datasets/datahiveai/russian-conversations-sample) publishes unscripted, two-person Russian calls with caller/callee on separate stereo channels under CC BY 4.0. Its pinned repository revision, metadata hash, deterministic selection rule and six original MP3 source hashes are in [datahive_ru_source_manifest.json](../tools/qa/fixtures/datahive_ru_source_manifest.json). The pinned metadata reports 65 minutes 41.328 seconds across those six original calls; their decoded durations have not been checked independently. No clips were reassembled. All six downloaded files matched the published byte counts and SHA-256 hashes locally and are cached privately under `/tmp/ascent-datahive-ru-holdout`. Obtain each `preview/` file at the manifest's pinned revision, verify its byte count and SHA-256, and keep it outside Git. The source card says **transcripts are not included**, so these files cannot yet support WER, cpWER or word-timing results. Independently transcribe and align them, review each speaker channel and overlap, and record a reference hash before model inference. AMI and DataHive together amount to 125 minutes 41.328 seconds by source metadata, but only AMI currently has supplied annotations. This does not meet a two-hour *labeled* fresh-holdout claim.

## Source candidates to inspect before selection

| Candidate | Useful condition | Source and annotation caveat | Selection status |
| --- | --- | --- | --- |
| [Basis Conversations 1500](https://huggingface.co/datasets/basis-ai/basis-conversations-1500) | Russian and English live multi-party conversations with aligned speaker tracks, including overlap | Its Russian metadata/audio are access-gated: an unauthenticated request for `ru/segments.jsonl` returned HTTP 401 `GatedRepo` on 7 November 2026. The card says supplied transcripts are automatically generated and need manual checking. Exact Russian recording IDs/hashes cannot be frozen until dataset access is granted and the license accepted. | access required |
| [DataHive Russian Conversations sample](https://huggingface.co/datasets/datahiveai/russian-conversations-sample) | Russian unscripted two-person remote calls, two speaker channels | CC BY 4.0 source-only files at pinned revision; no transcripts, so manual word/turn reference creation is mandatory. Six files selected by duration and hash-pinned before inference. | source selection frozen; reference pending |
| [AMI Meeting Corpus](https://groups.inf.ed.ac.uk/ami/download/) | English continuous meetings, manual orthographic annotation, several microphones | Official signals/manual annotations are CC BY 4.0. Six 10-minute headset-mix clips are hash-pinned; human word/boundary review remains. | source selection frozen |
| [VoxConverse v0.3](https://github.com/joonson/voxconverse/blob/master/README.md) | English multi-speaker diarization, interruptions and overlap | Official repository supplies RTTM speaker turns; it does not provide an ordinary word transcript. Manual word reference is required for WER. Use corrected v0.3 RTTMs and note the original video copyright. | candidate only |

The frozen AMI slice fills the initial six-clip, 60-minute English meeting condition. The DataHive files add Russian call-like source media, with independent references still required. Before a combined final quality claim, select Russian meeting-like continuous recordings, preferably with new speakers, plus short replies, similar voices, telephone bandwidth and noise. Freeze separate development and evaluation manifests **before** the candidate runs. A constructed sequence must be labeled as constructed and cannot replace continuous media for timing/overlap claims. If no licensed Russian continuous source with sufficient annotation is obtainable, report that gap rather than use the old diagnostic set as fresh validation.

For each selected file, privately record source URL/revision, exact file path and SHA-256, license, unique media duration, language, speaker count, condition tags, channel/rate/codec, annotation file/hash, time origin, reference word count and annotation review status. Do not put audio, private transcripts or embeddings in public Git. Keep model predictions and candidate scores from the untouched set sealed until the model/config and internal thresholds are frozen. Double-check at least 100 turn boundaries and 50 replies under about 1.5 seconds across conditions where possible.

After scoring, publish denominators and aggregate/per-condition results in [TEST_RESULTS.md](TEST_RESULTS.md), with the actual model/APK/device identifiers. If a holdout failure drives a fix, relabel that set diagnostic and reserve new source-disjoint media for the next final validation.
