#!/usr/bin/env python3
"""Export pinned GigaAM Multilingual 220M CTC for offline Android inference.

The export is published only after frontend/model numerical parity checks and
file validation pass. Exporting is not a Russian speech accuracy benchmark.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from importlib import metadata
import json
import math
from pathlib import Path
import re
import shutil
import tempfile
import urllib.request
import zipfile

from model_pack import (
    MODEL_ID, REQUIRED_FILES, SPEAKER_MODEL_ID, SPEAKER_SHA256, UPSTREAM_CHECKPOINT_MD5,
    UPSTREAM_COMMIT, ctc_decode, sha256_file, validate_directory, validate_manifest, validate_zip,
)
from evaluate_speech import measure_public_speech

UPSTREAM_REPOSITORY = "https://github.com/salute-developers/GigaAM"
UPSTREAM_MODEL = "multilingual_ctc"
CHECKPOINT_URL = "https://cdn.chatwm.opensmodel.sberdevices.ru/GigaAM/multilingual_ctc.ckpt"
SAMPLE_RATE = 16000
MIN_SAMPLES = 4000
MAX_SAMPLES = 400000


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path,
                        help="Verified ZIP transport for the model server; users download files in-app.")
    parser.add_argument("--cache-dir", type=Path, default=Path(".cache/gigaam"))
    parser.add_argument("--speaker-model", required=True, type=Path)
    parser.add_argument("--speaker-sha256", required=True)
    parser.add_argument("--speaker-license", required=True, type=Path)
    parser.add_argument("--speaker-source", required=True)
    parser.add_argument("--speaker-license-id", default="Apache-2.0")
    parser.add_argument("--smoke-wav", action="append", type=Path, required=True,
                        help="16 kHz mono speech WAV for required ASR/speaker checks; may be repeated (0.5–25 s).")
    parser.add_argument("--threads", type=int, default=2)
    return parser.parse_args()


def check_installation():
    direct_url = metadata.distribution("gigaam").read_text("direct_url.json")
    provenance = json.loads(direct_url or "{}")
    if provenance.get("vcs_info", {}).get("commit_id") != UPSTREAM_COMMIT:
        raise RuntimeError("Install GigaAM from tools/requirements-gigaam.txt; source commit differs")
    if provenance.get("url", "").removesuffix(".git") != UPSTREAM_REPOSITORY:
        raise RuntimeError("GigaAM did not come from the pinned official repository")


def write_json(path: Path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def make_export_model(model):
    """Replace torch complex STFT with a real-valued ONNX-compatible DFT.

    Capture the loaded checkpoint's actual window/filterbank, not guessed mel
    settings. Conv1d computes real/imaginary Fourier coefficients at each hop.
    This is mathematically equivalent to torchaudio's power spectrogram.
    """
    import torch
    import torch.nn.functional as functional

    mel = model.preprocessor.featurizer[0]
    spectrogram = mel.spectrogram
    if (spectrogram.power != 2.0 or spectrogram.normalized is not False
            or spectrogram.pad != 0 or not spectrogram.onesided
            or spectrogram.pad_mode != "reflect"):
        raise RuntimeError("Upstream spectrogram configuration changed; exporter needs review")
    if mel.sample_rate != SAMPLE_RATE:
        raise RuntimeError("Unsupported upstream sample rate")

    class LogMelFrontend(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.n_fft = int(spectrogram.n_fft)
            self.hop_length = int(spectrogram.hop_length)
            self.win_length = int(spectrogram.win_length)
            self.center = bool(spectrogram.center)
            self.n_freqs = self.n_fft // 2 + 1
            # torch.stft centers the analysis window inside n_fft if necessary.
            window = spectrogram.window.detach().cpu().double()
            left = (self.n_fft - self.win_length) // 2
            window = functional.pad(window, (left, self.n_fft - self.win_length - left))
            frequency = torch.arange(self.n_freqs, dtype=torch.float64)[:, None]
            sample = torch.arange(self.n_fft, dtype=torch.float64)[None, :]
            angle = (2.0 * math.pi / self.n_fft) * frequency * sample
            real = torch.cos(angle) * window
            imaginary = -torch.sin(angle) * window
            self.register_buffer("dft", torch.cat((real, imaginary), dim=0).float()[:, None, :])
            self.register_buffer("mel_filters", mel.mel_scale.fb.detach().cpu().float().T)

        def forward(self, audio, lengths):
            samples = audio.unsqueeze(1)
            if self.center:
                samples = functional.pad(samples, (self.n_fft // 2, self.n_fft // 2), mode="reflect")
            transformed = functional.conv1d(samples, self.dft, stride=self.hop_length)
            power = transformed[:, :self.n_freqs].square() + transformed[:, self.n_freqs:].square()
            features = torch.log(torch.matmul(self.mel_filters, power).clamp(1e-9, 1e9))
            if self.center:
                feature_lengths = torch.div(lengths, self.hop_length, rounding_mode="floor") + 1
            else:
                feature_lengths = torch.div(lengths - self.win_length, self.hop_length, rounding_mode="floor") + 1
            return features, feature_lengths.long()

    class WaveformCtc(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.frontend = LogMelFrontend()
            self.encoder = model.encoder
            self.head = model.head

        def forward(self, audio, audio_lengths):
            features, lengths = self.frontend(audio, audio_lengths)
            encoded, lengths = self.encoder(features, lengths)
            return self.head(encoded), lengths.long()

    return WaveformCtc().eval()


def smoke_inputs(paths):
    import numpy as np
    import soundfile

    random = np.random.default_rng(20261024)
    yield "silence-minimum", np.zeros(MIN_SAMPLES, dtype=np.float32)
    for count in (16000, 49873, MAX_SAMPLES):
        yield f"noise-{count}", (random.standard_normal(count) * 0.03).astype(np.float32)
    for index, path in enumerate(paths):
        samples, sample_rate = soundfile.read(path, dtype="float32", always_2d=True)
        if sample_rate != SAMPLE_RATE or samples.shape[1] != 1:
            raise ValueError(f"Smoke WAV must be 16 kHz mono: {path}")
        if not MIN_SAMPLES <= len(samples) <= MAX_SAMPLES:
            raise ValueError(f"Smoke WAV must be 0.25–25 seconds: {path}")
        if not np.isfinite(samples).all() or np.max(np.abs(samples)) > 1.0:
            raise ValueError(f"Invalid normalized PCM in smoke WAV: {path}")
        # Do not put user filenames or transcript text into public CI artifacts.
        yield f"speech-{index + 1}", samples[:, 0]


def speaker_smoke_test(model_path, paths, threads):
    """Exercise the real speaker frontend/model on normalized speech on CPU.

    This checks model executability and embedding shape, not speaker accuracy.
    Neither embeddings, filenames nor transcripts are retained in the report.
    """
    import numpy as np
    import sherpa_onnx
    import soundfile

    config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
        model=str(model_path), num_threads=threads, debug=False, provider="cpu"
    )
    if not config.validate():
        raise RuntimeError("Invalid speaker embedding configuration")
    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(config)
    if extractor.dim != 192:
        raise RuntimeError("Expected ERes2NetV2's 192-dimensional embedding")
    records = []
    for index, path in enumerate(paths):
        samples, rate = soundfile.read(path, dtype="float32", always_2d=True)
        if rate != SAMPLE_RATE or samples.shape[1] != 1:
            raise ValueError("Speaker smoke WAV must be 16 kHz mono")
        samples = np.ascontiguousarray(samples[:, 0])
        if (not SAMPLE_RATE // 2 <= len(samples) <= MAX_SAMPLES
                or not np.isfinite(samples).all() or np.max(np.abs(samples)) > 1.0):
            raise ValueError("Speaker smoke needs 0.5–25 seconds of normalized speech")
        # Match the app's short sliding windows. Pick the highest-energy 1.5 s
        # window so leading/trailing silence does not dominate the smoke case.
        count = min(len(samples), SAMPLE_RATE * 3 // 2)
        offsets = range(0, len(samples) - count + 1, SAMPLE_RATE // 4)
        start = max(offsets, key=lambda offset: float(np.sum(np.square(samples[offset:offset + count]))))
        waveform = np.ascontiguousarray(samples[start:start + count])
        if float(np.max(np.abs(waveform))) == 0.0:
            raise RuntimeError("Speaker smoke speech window is silent")
        stream = extractor.create_stream()
        stream.accept_waveform(sample_rate=SAMPLE_RATE, waveform=waveform)
        stream.input_finished()
        if not extractor.is_ready(stream):
            raise RuntimeError("Speaker model is not ready after the speech window")
        embedding = np.asarray(extractor.compute(stream), dtype=np.float32)
        if embedding.shape != (192,) or not np.isfinite(embedding).all():
            raise RuntimeError("Speaker model returned an invalid embedding")
        norm = float(np.linalg.norm(embedding.astype(np.float64)))
        if not math.isfinite(norm) or norm <= 1e-8:
            raise RuntimeError("Speaker model returned a zero embedding")
        records.append({"case": f"speech-{index + 1}", "samples": count,
                        "dimensions": 192, "finite": True, "nonzero": True})
        print(f"Speaker smoke passed: speech-{index + 1} (192 dimensions)", flush=True)
    if not records:
        raise RuntimeError("At least one real speech speaker smoke case is required")
    return {"status": "passed", "runtime": "sherpa-onnx", "provider": "cpu",
            "version": metadata.version("sherpa-onnx"), "cases": records,
            "scope": "Desktop embedding inference; not Android JNI or diarization accuracy"}


def smoke_test(model, exported, session, tokens, paths):
    import numpy as np
    import torch

    records = []
    for name, samples in smoke_inputs(paths):
        audio = torch.from_numpy(samples[None, :])
        lengths = torch.tensor([len(samples)], dtype=torch.int64)
        with torch.inference_mode():
            expected_features, expected_feature_lengths = model.preprocessor(audio, lengths)
            features, feature_lengths = exported.frontend(audio, lengths)
            np.testing.assert_array_equal(expected_feature_lengths.numpy(), feature_lengths.numpy())
            np.testing.assert_allclose(features.numpy(), expected_features.numpy(), atol=0.002, rtol=0.0005)
            encoded, expected_lengths = model(audio, lengths)
            expected = model.head(encoded).numpy()
            upstream_text, upstream_ids, upstream_frames = model.decoding.decode(
                model.head, encoded, expected_lengths
            )[0]
        actual, actual_lengths = session.run(None, {"audio": audio.numpy(), "audio_lengths": lengths.numpy()})
        if actual.ndim != 3 or actual.shape[0] != 1 or actual.shape[2] != len(tokens) + 1:
            raise RuntimeError("Export output does not match the charwise CTC contract")
        if not np.isfinite(actual).all():
            raise RuntimeError("Non-finite ONNX output")
        np.testing.assert_array_equal(actual_lengths, expected_lengths.numpy())
        np.testing.assert_allclose(actual, expected, atol=0.01, rtol=0.0005)
        valid = int(actual_lengths[0])
        if not 0 < valid <= actual.shape[1]:
            raise RuntimeError("Invalid encoded length")
        actual_text, frames = ctc_decode(actual.argmax(-1)[0], tokens, valid)
        reference_text, reference_frames = ctc_decode(expected.argmax(-1)[0], tokens, valid)
        if reference_text != upstream_text or reference_frames != upstream_frames:
            raise RuntimeError(f"Local CTC decoder differs from the upstream decoder on {name}")
        if actual_text != upstream_text:
            raise RuntimeError(f"Greedy transcript differs from upstream on {name}")
        if name.startswith("speech-") and not actual_text.strip():
            raise RuntimeError(f"Speech smoke sample produced an empty transcript: {name}")
        records.append({
            "case": name, "samples": len(samples), "encoded_frames": valid,
            "max_frontend_abs_error": float(torch.max(torch.abs(features - expected_features)).item()),
            "max_log_probs_abs_error": float(np.max(np.abs(actual - expected))),
            "greedy_text_matches": True, "emitted_tokens": len(frames),
        })
        print(f"Smoke passed: {name} ({valid} encoder frames)", flush=True)
    return {
        "status": "passed", "cases": records,
        "speech_files_tested": len(paths),
        "scope": "Numerical export parity; not a WER or diarization accuracy benchmark",
        "speaker_runtime_tested": False,
    }


def main():
    args = arguments()
    destination = args.output
    if destination.exists():
        raise FileExistsError(f"Refusing to replace existing output: {destination}")
    if not re.fullmatch(r"[0-9a-f]{64}", args.speaker_sha256):
        raise ValueError("--speaker-sha256 must be the verified lowercase SHA-256 of the ONNX asset")
    if args.speaker_sha256 != SPEAKER_SHA256:
        raise ValueError("Speaker hash differs from the pinned ERes2NetV2 upstream export")
    if sha256_file(args.speaker_model) != args.speaker_sha256:
        raise ValueError("Speaker model SHA-256 does not match the pinned expected hash")
    if args.speaker_model.name != "3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx":
        raise ValueError("Expected the ERes2NetV2 2024 sherpa-onnx embedding export")
    if not args.speaker_source.startswith("https://"):
        raise ValueError("Speaker provenance must be an HTTPS source URL")
    if not 50 < args.speaker_license.stat().st_size < 100000:
        raise ValueError("Missing or oversized speaker license")
    if args.threads < 1:
        raise ValueError("Thread count must be positive")
    check_installation()

    import gigaam
    import onnx
    import onnxruntime
    import torch

    torch.set_num_threads(args.threads)
    if gigaam._MODEL_HASHES.get(UPSTREAM_MODEL) != UPSTREAM_CHECKPOINT_MD5:
        raise RuntimeError("Pinned upstream checkpoint checksum changed")
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    print("Loading pinned GigaAM Multilingual 220M on CPU", flush=True)
    model = gigaam.load_model(UPSTREAM_MODEL, device="cpu", fp16_encoder=False,
                              use_flash=False, download_root=str(args.cache_dir))
    if not model.decoding.tokenizer.charwise:
        raise RuntimeError("This exporter requires a character tokenizer")
    tokens = list(model.decoding.tokenizer.vocab)
    if model.decoding.blank_id != len(tokens):
        raise RuntimeError("Upstream blank ID convention changed")
    parameter_count = sum(parameter.numel() for parameter in model.parameters())
    if not 180_000_000 < parameter_count < 300_000_000:
        raise RuntimeError("Loaded model is not the expected 220M family")
    exported = make_export_model(model)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".ascent-model-export-", dir=destination.parent) as directory:
        staging = Path(directory) / "files"
        staging.mkdir()
        export_path = staging / "asr.onnx"
        # Fixed batch=1 avoids the upstream batch-dependent attention path.
        audio = torch.zeros(1, SAMPLE_RATE * 2)
        lengths = torch.tensor([audio.shape[1]], dtype=torch.int64)
        print("Exporting waveform frontend and CTC model", flush=True)
        with torch.inference_mode(), model.encoder.onnx_export_mode():
            exported(audio, lengths)  # Populate upstream positional buffers before tracing.
            torch.onnx.export(
                exported, (audio, lengths), str(export_path), opset_version=17,
                dynamo=False, input_names=["audio", "audio_lengths"],
                output_names=["log_probs", "encoded_lengths"],
                dynamic_axes={"audio": {1: "samples"}, "log_probs": {1: "frames"}},
                external_data=False,
            )
            onnx.checker.check_model(str(export_path))
            options = onnxruntime.SessionOptions()
            options.intra_op_num_threads = args.threads
            options.inter_op_num_threads = 1
            session = onnxruntime.InferenceSession(str(export_path), sess_options=options,
                                                  providers=["CPUExecutionProvider"])
            if [node.name for node in session.get_inputs()] != ["audio", "audio_lengths"]:
                raise RuntimeError("Unexpected exported input names")
            report = smoke_test(model, exported, session, tokens, args.smoke_wav)
            # Console-only development scores; neither references, audio nor
            # regression measurements become downloadable model-pack files.
            measure_public_speech(session, tokens)
            del session
        report["speaker_smoke"] = speaker_smoke_test(args.speaker_model, args.smoke_wav, args.threads)
        report["speaker_runtime_tested"] = True
        write_json(staging / "tokens.json", tokens)
        write_json(staging / "smoke-report.json", report)
        shutil.copyfile(args.speaker_model, staging / "speaker.onnx")
        shutil.copyfile(args.speaker_license, staging / "LICENSE-speaker.txt")
        license_url = f"https://raw.githubusercontent.com/salute-developers/GigaAM/{UPSTREAM_COMMIT}/LICENSE"
        with urllib.request.urlopen(license_url, timeout=60) as response:
            license_text = response.read(100000).decode("utf-8")
        if "MIT License" not in license_text or "GigaChat Team" not in license_text:
            raise RuntimeError("Unexpected upstream license")
        (staging / "LICENSE-asr.txt").write_text(license_text, encoding="utf-8")
        files = {name: {"sha256": sha256_file(staging / name), "bytes": (staging / name).stat().st_size}
                 for name in sorted(REQUIRED_FILES)}
        manifest = {
            "schema_version": 1, "model_id": MODEL_ID,
            "sample_rate": SAMPLE_RATE, "channels": 1,
            "min_samples": MIN_SAMPLES, "max_samples": MAX_SAMPLES,
            "blank_id": len(tokens), "vocabulary_size": len(tokens),
            "asr_file": "asr.onnx", "tokens_file": "tokens.json",
            "speaker_file": "speaker.onnx", "speaker_model_id": SPEAKER_MODEL_ID,
            "speaker_runtime": "sherpa-onnx", "files": files,
            "created_utc": datetime.now(timezone.utc).isoformat(),
            "asr": {
                "precision": "float32", "parameters": parameter_count,
                "inputs": {"audio": "float32[1,samples]", "audio_lengths": "int64[1]"},
                "outputs": {"log_probs": "float32[1,frames,vocabulary_size+1]", "encoded_lengths": "int64[1]"},
                "pcm_normalization": "signed_int16 / 32768.0",
                "frontend": "embedded log-mel; periodic Hann; source window and mel filters",
                "decoding": "character CTC greedy; collapse repeats then remove blank",
                "timestamps": "approximate; frame * samples / sample_rate / encoded_length",
                "punctuation": False,
            },
            "source": {
                "repository": UPSTREAM_REPOSITORY, "commit": UPSTREAM_COMMIT,
                "model": UPSTREAM_MODEL, "release": "2026-06", "license": "MIT",
                "checkpoint_url": CHECKPOINT_URL, "checkpoint_md5": UPSTREAM_CHECKPOINT_MD5,
                "checkpoint_sha256": sha256_file(args.cache_dir / f"{UPSTREAM_MODEL}.ckpt"),
            },
            "speaker_source": {"url": args.speaker_source, "sha256": args.speaker_sha256,
                               "license": args.speaker_license_id},
            "build_versions": {name: metadata.version(name) for name in
                               ("torch", "torchaudio", "numpy", "onnx", "onnxruntime", "sherpa-onnx",
                                "hydra-core", "omegaconf", "soundfile", "sentencepiece", "tqdm", "gigaam")},
        }
        validate_manifest(manifest)
        write_json(staging / "manifest.json", manifest)
        validate_directory(staging)
        temporary_output = destination.with_name(destination.name + ".part")
        try:
            with zipfile.ZipFile(temporary_output, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
                for file in sorted(staging.iterdir()):
                    archive.write(file, arcname=file.name)
            validate_zip(temporary_output)
            temporary_output.replace(destination)
        finally:
            temporary_output.unlink(missing_ok=True)
    digest = sha256_file(destination)
    destination.with_name(destination.name + ".sha256").write_text(
        f"{digest}  {destination.name}\n", encoding="utf-8")
    print(f"Created verified deployment transport: {destination}\nSHA-256: {digest}", flush=True)


if __name__ == "__main__":
    main()
