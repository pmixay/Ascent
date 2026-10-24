#!/usr/bin/env python3
"""Generate real ERes2NetV2 regression inputs in GitHub CI, never on a device.

Only public test audio from the adjacent fixture manifest is accepted. This is
not a recorder, enrollment utility, or production model-export path.
"""

import argparse
import hashlib
import json
from pathlib import Path
import wave


SAMPLE_RATE = 16000
MODEL_SHA256 = "bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239"
FIXTURE_ROOT = Path(__file__).resolve().parent / "fixtures" / "speakers"


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def load_fixture(item, np):
    path = FIXTURE_ROOT / item["file"]
    if path.parent != FIXTURE_ROOT or path.suffix != ".wav":
        raise ValueError("Unexpected fixture path")
    if path.stat().st_size != item["bytes"] or digest(path) != item["sha256"]:
        raise ValueError(f"Fixture integrity mismatch: {item['id']}")
    with wave.open(str(path), "rb") as source:
        if (source.getnchannels(), source.getsampwidth(), source.getframerate(),
                source.getcomptype(), source.getnframes()) != (
                    1, 2, SAMPLE_RATE, "NONE", item["frames"]):
            raise ValueError(f"Unexpected fixture format: {item['id']}")
        samples = np.frombuffer(source.readframes(source.getnframes()), dtype="<i2")
    return samples.astype(np.float32) / 32768.0


def variants(samples, fixture_id, np):
    yield "clean", samples
    yield "gain035", samples * np.float32(0.35)
    seed = int.from_bytes(hashlib.sha256(fixture_id.encode()).digest()[:8], "little")
    rng = np.random.default_rng(seed)
    noise = rng.standard_normal(len(samples)).astype(np.float32)
    speech_rms = float(np.sqrt(np.mean(np.square(samples.astype(np.float64)))))
    noise_rms = float(np.sqrt(np.mean(np.square(noise.astype(np.float64)))))
    mixed = samples + noise * np.float32(speech_rms / (10.0 * noise_rms))
    # A common gain prevents clipping while preserving the requested 20 dB SNR.
    peak = float(np.max(np.abs(mixed)))
    if peak > 0.98:
        mixed = mixed * np.float32(0.98 / peak)
    yield "noise20db", mixed


def write_pcm(path, samples, np):
    pcm = np.clip(np.rint(samples * 32768.0), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as target:
        target.setnchannels(1)
        target.setsampwidth(2)
        target.setframerate(SAMPLE_RATE)
        target.writeframes(pcm.tobytes())
    # Feed exactly the PCM16 samples read by PcmWave in the JVM test.
    return pcm.astype(np.float32) / 32768.0


def embeddings(extractor, samples, np):
    width = min(len(samples), SAMPLE_RATE * 18 // 10)
    step = SAMPLE_RATE * 9 // 10
    starts = sorted(set(range(0, len(samples) - width + 1, step)) | {len(samples) - width})
    result = []
    for start in starts:
        stream = extractor.create_stream()
        stream.accept_waveform(sample_rate=SAMPLE_RATE,
                               waveform=np.ascontiguousarray(samples[start:start + width]))
        stream.input_finished()
        if not extractor.is_ready(stream):
            raise RuntimeError("Speaker extractor is not ready for the public speech fixture")
        embedding = np.asarray(extractor.compute(stream), dtype=np.float32)
        if (embedding.shape != (192,) or not np.isfinite(embedding).all()
                or float(np.linalg.norm(embedding.astype(np.float64))) <= 1e-8):
            raise RuntimeError("Speaker extractor returned an invalid embedding")
        result.append({"start_ms": start * 1000 // SAMPLE_RATE,
                       "end_ms": (start + width) * 1000 // SAMPLE_RATE,
                       "embedding": embedding.tolist()})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if digest(args.model) != MODEL_SHA256:
        raise ValueError("Speaker model checksum mismatch")
    manifest = json.loads((FIXTURE_ROOT / "manifest.json").read_text())
    if manifest["schema_version"] != 1 or manifest["sample_rate"] != SAMPLE_RATE:
        raise ValueError("Unexpected source fixture manifest")

    import numpy as np
    import sherpa_onnx

    config = sherpa_onnx.SpeakerEmbeddingExtractorConfig(
        model=str(args.model), num_threads=2, debug=False, provider="cpu")
    if not config.validate():
        raise RuntimeError("Invalid speaker embedding configuration")
    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(config)
    if extractor.dim != 192:
        raise RuntimeError("Expected the 192-dimensional ERes2NetV2 model")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fixtures = []
    for item in manifest["fixtures"]:
        samples = load_fixture(item, np)
        output = {"id": item["id"], "speaker": item["speaker"],
                  "duration_ms": len(samples) * 1000 // SAMPLE_RATE, "variants": []}
        for name, augmented in variants(samples, item["id"], np):
            filename = f"{item['id']}-{name}.wav"
            quantized = write_pcm(args.output.parent / filename, augmented, np)
            windows = embeddings(extractor, quantized, np)
            output["variants"].append({"name": name, "audio_file": filename,
                                       "windows": windows})
            print(f"Speaker fixture {item['id']} / {name}: {len(windows)} windows", flush=True)
        fixtures.append(output)
    result = {"schema_version": 1, "model_sha256": MODEL_SHA256,
              "sample_rate": SAMPLE_RATE, "fixtures": fixtures}
    args.output.write_text(json.dumps(result, separators=(",", ":"), allow_nan=False) + "\n")
    print(f"Generated {len(fixtures)} public speech fixtures; no app or report artifact created.")


if __name__ == "__main__":
    main()
