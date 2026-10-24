# Downloadable offline models

The selected recognizer is **GigaAM Multilingual 220M CTC**, official model name
`multilingual_ctc`. The upstream README dates this family to June 2026; its source
integration was committed in July. This is newer than the Russian-only GigaAM v3
line released in November 2025. Choosing the 220M member of the latest family
keeps the initial mobile memory cost below that of the 600M member. This is a
size tradeoff, not a claim that it is the most accurate Russian recognizer.

Source is pinned to
[`7447938d791c4f3e643386ee22c33777004293a5`](https://github.com/salute-developers/GigaAM/commit/7447938d791c4f3e643386ee22c33777004293a5).
The checkpoint's upstream MD5 is `5379d887c53ccd9cb95981e2a1832720`.
The builder verifies that value and records its SHA-256, the source commit, exact
installed library versions, and each packaged file's SHA-256. MD5 is the upstream
revision check; it is not advertised as a cryptographic signature. The pack's
hashes detect corruption and do not establish who published a pack.

## Download in the app

The APK contains no model weights. The app downloads its catalog from the fixed
Ascent host at `https://ascent.arbuz.lol/models/catalog.json` when the user chooses
to install the models. No external archive or server selection is required.
Recognition and speaker processing run locally after installation; conversation
audio is not part of model download requests.

The catalog is a small JSON object:

```json
{
  "schema_version": 1,
  "model_id": "gigaam-multilingual-ctc-220m-2026-06",
  "manifest_sha256": "<64 lowercase hexadecimal characters>",
  "manifest_bytes": 12345
}
```

The digest and size above are illustrative. The publisher computes them from the
validated manifest. The app derives the immutable manifest URL as
`/models/<model_id>/<manifest_sha256>/manifest.json` on that same host and
checks its exact byte length and SHA-256 before parsing it. The six payload files
are siblings of the manifest. No URL supplied by an arbitrary model file can
redirect the app to another model server.

The app shows download progress and supports cancellation and retry. It validates
every payload before replacing the selected model directory; an incomplete or
corrupt download leaves the previously installed models selected. HTTPS
protects the connection to the configured publisher. Hashes detect corruption;
they are not an independent signature of publisher identity.

## Build and publish in GitHub Actions

The repository's [Ascent Model Export](../../../.github/workflows/model-pack.yml)
workflow, whose job is **Export and validate offline models**, runs only on
pushes affecting model export tools, export dependencies, model publishing
server files, or the workflow itself on `main` and the current prototype branch.
There is no pull-request or manual-dispatch trigger. Ordinary Android-only pushes
do not download or export weights.

The workflow uses Python 3.12, installs the pinned CPU environment, downloads and
verifies the speaker model and public speech fixture, and exports the model.
Only after the numerical, decoding, speaker-inference and file-integrity checks
pass does it stream the verified deployment ZIP over SSH to
`ascent-models@ascent.arbuz.lol`. The server's forced `publish-models` command
revalidates the archive, installs an immutable model directory, and atomically
replaces `catalog.json` after all files are ready. Previously published immutable
paths remain available to downloads that already fetched an older catalog.

The repository secrets `ASCENT_MODEL_DEPLOY_KEY` and `ASCENT_MODEL_HOST_KEY`
provide the dedicated deployment key and pinned SSH known-hosts entry. The
workflow uses strict host-key checking, writes the private key only to a
restricted temporary directory, and removes it at step exit. No application
release tag is created. The deployment archive is internal transport: it is not
uploaded as a GitHub artifact and users do not import it into the app. Android
CI publishes only its compact APK ZIP.

Model export and executable checks run only in CI under the repository's
[engineering contract](../../../AGENTS.md). Do not install local export or
Android toolchains or run the exporter locally. The workflow file is the source
of truth for its commands. Its working directory is
`prototypes/phone-transcriber/`. Application audio is never uploaded to that
workflow or an API; the public fixture is separate validation input. The
workflow reuses pip downloads and the upstream checkpoint cache. Checkpoint
verification and all export checks still run; no audio is cached. No measured
export speedup is claimed.

Dependencies are pinned to stable releases verified on October 24, 2026:

| Export dependency | Selected version |
| --- | --- |
| [PyTorch](https://pypi.org/project/torch/2.14.1/) | 2.14.1 CPU |
| [TorchAudio](https://pypi.org/project/torchaudio/2.11.0/) | 2.11.0 CPU |
| [NumPy](https://pypi.org/project/numpy/2.5.4/) | 2.5.4 |
| [ONNX](https://pypi.org/project/onnx/1.23.2/) | 1.23.2 |
| [ONNX Runtime](https://pypi.org/project/onnxruntime/1.31.0/) | 1.31.0, matching Android ASR |
| [sherpa-onnx](https://pypi.org/project/sherpa-onnx/2.0.0/) | 2.0.0, matching Android speaker bindings |
| [Hydra](https://pypi.org/project/hydra-core/1.3.7/) | 1.3.7 |
| [OmegaConf](https://pypi.org/project/omegaconf/2.3.1/) | 2.3.1, latest compatible with Hydra |
| [SoundFile](https://pypi.org/project/soundfile/0.14.0/) | 0.14.0 |
| [SentencePiece](https://pypi.org/project/sentencepiece/0.2.2/) | 0.2.2 |
| [tqdm](https://pypi.org/project/tqdm/4.70.1/) | 4.70.1 |

[TorchAudio's supported compatibility matrix](https://docs.pytorch.org/audio/stable/installation.html)
explicitly allows 2.11 with PyTorch 2.11 and later releases. OmegaConf 2.4.0 is
newer, but [Hydra 1.3.7's package metadata](https://pypi.org/pypi/hydra-core/1.3.7/json)
requires `<2.4`; prerelease Hydra is not used.

The separate GigaAM installation is deliberate: the pinned official
[dependency metadata](https://github.com/salute-developers/GigaAM/blob/7447938d791c4f3e643386ee22c33777004293a5/pyproject.toml)
requires ONNX 1.19 and ONNX Runtime 1.23. This exporter overrides those two bounds
to use the current stable libraries while retaining the exact upstream model
source and checkpoint. A normal combined pip installation would fail dependency
resolution. `pip check` will report these two known metadata mismatches; they are
not hidden by rewriting the installed package. Compatibility of the newer export
stack remains conditional on the required numerical and real-speech smoke checks
in CI. The exporter records actual installed versions in every pack.

The waveform wrapper still uses the upstream encoder's export context and
TorchScript ONNX path (`dynamo=False`), which
[PyTorch 2.14.1 retains](https://github.com/pytorch/pytorch/blob/v2.14.1/torch/onnx/__init__.py).
Switching its dynamic attention and positional-buffer behavior to `torch.export`
requires a successful model parity run; using the latest library does not make
that migration implicitly verified.

The workflow's `ASCENT_SPEAKER_SHA256` must be the independently checked SHA-256 of the chosen
ERes2NetV2 2024 ONNX artifact. The builder requires it and verifies the actual
file; it never silently substitutes an older speaker model. Supply the upstream
license text and retain the asset source URL. The expected source is the
[sherpa-onnx speaker recognition release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models).

Pinned value: `bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239`
(71,441,526 bytes). This is independently visible in the export maintainer's
[immutable October 14, 2024 commit](https://huggingface.co/csukuangfj/speaker-embedding-models/commit/8be2a75c9ed7a590538b268e46fbb65e1aa9d208).
The corresponding [immutable ONNX download](https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/8be2a75c9ed7a590538b268e46fbb65e1aa9d208/3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx)
can be used instead of the GitHub release mirror.

At least one `--smoke-wav path/to/short.wav` is required to publish a pack
(16 kHz mono, 0.5–25 seconds). This option may be repeated. The local decoder
must agree with the upstream decoder, and speech must produce nonempty text.
Do not use private
conversation audio in a public CI job. The report includes numerical metrics,
not the speech's transcript or local filename.

CI uses the public GigaAM example mirrored in the sherpa-onnx maintainer's
[immutable model repository](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16/resolve/f376a99ee8be93b61f9e969d2ac827c4d228dac3/test_wavs/example.wav),
verified as SHA-256
`d8aaaa18a5098d7c6de0595ae7ac1e64cacd0d4022af3595213bdaf23be77e69`.
This short speech sample is input data, independent of which GigaAM weights are
being exported.

The float32 export is the initial correctness baseline. INT8 is not enabled
without evaluating its effect on real Russian conversations. Actual model sizes
are recorded by the build; no model binaries belong in Git.

## Android contract

Each installed or published immutable model directory contains exactly these seven files.
The internal SSH deployment ZIP uses the same flat file set:

| File | Purpose |
| --- | --- |
| `manifest.json` | Contract, provenance, hashes and sizes |
| `asr.onnx` | Waveform → log-mel frontend → encoder → CTC log probabilities |
| `tokens.json` | Ordered UTF-8 JSON string array, excluding the blank token |
| `speaker.onnx` | ERes2NetV2 speaker embedding model for sherpa-onnx |
| `LICENSE-asr.txt` | Upstream GigaAM MIT license |
| `LICENSE-speaker.txt` | Speaker model license and attribution |
| `smoke-report.json` | Export parity check results |

Manifest fields consumed by Android:

```json
{
  "schema_version": 1,
  "model_id": "gigaam-multilingual-ctc-220m-2026-06",
  "sample_rate": 16000,
  "channels": 1,
  "min_samples": 4000,
  "max_samples": 400000,
  "asr_file": "asr.onnx",
  "tokens_file": "tokens.json",
  "speaker_file": "speaker.onnx",
  "speaker_model_id": "3dspeaker-eres2netv2-2024",
  "speaker_runtime": "sherpa-onnx",
  "vocabulary_size": 123,
  "blank_id": 123,
  "files": {}
}
```

The vocabulary count above is illustrative, not the model's actual count.
The builder reads it from the checkpoint. The real `files` object has one entry
for each of the six payload files, with `sha256` (64 lowercase hex digits) and
`bytes` (positive integer). A downloader must check every entry before activating
the models, reject extra paths and oversized files, and keep the previous models
until the new files have been verified. The server additionally rejects duplicate
ZIP entries. `manifest.json` is not self-hashed: the fixed-host catalog supplies
its exact SHA-256 and byte length. The internal ZIP checksum is deployment
diagnostics and is not required from the app user.

ASR ONNX inputs:

- `audio`: `float32[1,N]`, mono 16 kHz PCM normalized from signed int16 by
  division by 32768.0. No loudness normalization or pre-emphasis is added.
- `audio_lengths`: `int64[1]` with the value `N`. Batch size is fixed to one.
  Feed 4,000–400,000 samples per call; split longer recordings and retain the
  global start offset. Pad only recordings shorter than 0.25 s and clamp displayed
  timestamps to their real duration.

Outputs:

- `log_probs`: `float32[1,T,V+1]`.
- `encoded_lengths`: `int64[1]`, the valid frame count, which can be smaller than
  `T`.

Take the argmax at each valid frame; collapse consecutive repeated IDs **before**
removing blank (`blank_id = V`). Join the vocabulary strings as-is. This preserves
repeated letters separated by blank frames. This CTC model does not promise
punctuation; do not synthesize it by changing recognized words.

The frontend is embedded to avoid different mel implementations on desktop and
Android. Its real/imaginary Conv1d DFT uses the checkpoint's torchaudio analysis
window and mel matrix, reflect padding, power spectrum and natural logarithm
clamped to `[1e-9,1e9]`. The export must numerically match the upstream frontend
and full model at multiple lengths, including the 25-second limit.

Word times follow the upstream approximation:
`seconds_per_frame = N / 16000 / encoded_lengths[0]`. A word starts at its first
emitted character frame and ends one frame after its last emitted character.
Add the recording chunk's offset. These are model-derived approximate times,
not forced alignment ground truth. Speaker embeddings and clustering are a
separate step; ASR does not itself identify speakers or semantic roles.

## Verification status

The exporter fails before publication if source/checkpoint identity, frontend
parity, ONNX validity, model output parity, CTC text parity or file/archive integrity
checks fail. Synthetic smoke cases establish numerical equivalence and dynamic
input lengths, **not** Russian accuracy or diarization quality. The supplied
speaker model is hash-checked and must produce a finite, nonzero 192-dimensional
embedding from a short window of each supplied speech sample through the real
sherpa-onnx CPU frontend and model. Only after that succeeds does the report set
`speaker_runtime_tested` to `true`. This is desktop inference; it does not validate
Android native linking, cluster thresholds or speaker-turn accuracy.

The repository permits local editing and static inspection only. Packaging
tests, model export and Android executable checks run in GitHub Actions. A
successful inspected model-export/deployment run, an accessible verified
catalog and a real-device trial are required before claiming the app works with
these weights. This document describes the publishing pipeline; it does not by
itself establish a successful model export, deployment or device inference result.

## Sources checked

- [GigaAM model family and release history](https://github.com/salute-developers/GigaAM)
- [Pinned checkpoint names and hashes](https://github.com/salute-developers/GigaAM/blob/7447938d791c4f3e643386ee22c33777004293a5/gigaam/__init__.py)
- [Upstream waveform frontend](https://github.com/salute-developers/GigaAM/blob/7447938d791c4f3e643386ee22c33777004293a5/gigaam/preprocess.py)
- [CTC export uses features rather than raw waveform](https://github.com/salute-developers/GigaAM/blob/7447938d791c4f3e643386ee22c33777004293a5/gigaam/model.py)
- [Character vocabulary and blank convention](https://github.com/salute-developers/GigaAM/blob/7447938d791c4f3e643386ee22c33777004293a5/gigaam/decoding.py)
- [Upstream timestamp calculation](https://github.com/salute-developers/GigaAM/blob/7447938d791c4f3e643386ee22c33777004293a5/gigaam/timestamps_utils.py)
