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
    # A reproducible mild channel response tests identity across spectral changes.
    # This is synthetic coloration, not a measured phone/microphone impulse response.
    channel = np.convolve(samples, np.array([.1, .2, .4, .2, .1], dtype=np.float32), mode="same")
    channel[1:] -= np.float32(.35) * channel[:-1].copy()
    channel_rms = float(np.sqrt(np.mean(np.square(channel.astype(np.float64)))))
    if channel_rms > 0:
        channel *= np.float32(speech_rms / channel_rms)
    peak = float(np.max(np.abs(channel)))
    if peak > .98:
        channel *= np.float32(.98 / peak)
    yield "channel", channel


def write_pcm(path, samples, np):
    pcm = np.clip(np.rint(samples * 32768.0), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as target:
        target.setnchannels(1)
        target.setsampwidth(2)
        target.setframerate(SAMPLE_RATE)
        target.writeframes(pcm.tobytes())
    # Feed exactly the PCM16 samples read by PcmWave in the JVM test.
    return pcm.astype(np.float32) / 32768.0


def embeddings(extractor, samples, np, contexts=None):
    if contexts is None:
        width = min(len(samples), SAMPLE_RATE * 18 // 10)
        step = SAMPLE_RATE * 9 // 10
        starts = sorted(set(range(0, len(samples) - width + 1, step)) | {len(samples) - width})
        contexts = [{"start_ms": start * 1000 // SAMPLE_RATE,
                     "end_ms": (start + width) * 1000 // SAMPLE_RATE} for start in starts]
    result = []
    for context in contexts:
        start = context["start_ms"] * SAMPLE_RATE // 1000
        end = context["end_ms"] * SAMPLE_RATE // 1000
        stream = extractor.create_stream()
        stream.accept_waveform(sample_rate=SAMPLE_RATE,
                               waveform=np.ascontiguousarray(samples[start:end]))
        stream.input_finished()
        if not extractor.is_ready(stream):
            raise RuntimeError("Speaker extractor is not ready for the public speech fixture")
        embedding = np.asarray(extractor.compute(stream), dtype=np.float32)
        if (embedding.shape != (192,) or not np.isfinite(embedding).all()
                or float(np.linalg.norm(embedding.astype(np.float64))) <= 1e-8):
            raise RuntimeError("Speaker extractor returned an invalid embedding")
        result.append({**context, "embedding": embedding.tolist()})
    return result


def probe_contexts(probes, duration_ms):
    """Mirror SpeakerSpeechSupport.contexts; JVM asserts every returned context."""
    regions = []
    for probe in probes:
        start = min(duration_ms, max(0, probe["start_ms"]))
        end = min(duration_ms, max(start, probe["end_ms"]))
        if start == end:
            continue
        if regions and start - regions[-1][1] <= 500:
            regions[-1][1] = max(regions[-1][1], end)
        else:
            regions.append([start, end])
    result = []
    for index, (region_start, region_end) in enumerate(regions):
        lower = max(0, region_start - 600,
                    regions[index - 1][1] + (region_start - regions[index - 1][1]) // 2 if index else 0)
        upper = min(duration_ms, region_end + 600,
                    region_end + (regions[index + 1][0] - region_end) // 2
                    if index + 1 < len(regions) else duration_ms)
        start = max(lower, region_start - 240)
        end = min(upper, region_end + 240)
        if end - start < 1200:
            target = min(1200, upper - lower)
            center = region_start + (region_end - region_start) // 2
            start = min(upper - target, max(lower, center - target // 2))
            end = start + target
        if end - start < 320:
            continue
        if end - start <= 1800:
            result.append({"start_ms": start, "end_ms": end, "region": index})
        else:
            position = start
            while position + 1800 < end:
                result.append({"start_ms": position, "end_ms": position + 1800, "region": index})
                position += 900
            final_start = end - 1800
            if not result or result[-1]["start_ms"] != final_start:
                result.append({"start_ms": final_start, "end_ms": end, "region": index})
    return result


def conversation_fixtures(extractor, manifest, output_dir, np):
    """Continuous sequences from readers absent from the development fixtures.

    All source samples appear exactly once per condition. Initial turns use the
    first clip, short replies the first 800ms of the second clip, and returns its
    remaining samples. No scores/embeddings determine this frozen arrangement.
    Reference cells include internal clip silence; only inserted gaps are absent.
    """
    by_reader = {}
    for item in manifest["fixtures"]:
        by_reader.setdefault(item["speaker"], []).append(item)
    if len(by_reader) != 6 or any(len(items) != 2 for items in by_reader.values()):
        raise ValueError("Frozen evaluation requires six readers with two source clips each")
    readers = sorted(by_reader)
    cases = []
    for condition in ("clean", "gain035", "noise20db", "channel"):
        audio = {}
        for item in manifest["fixtures"]:
            source = load_fixture(item, np)
            audio[item["id"]] = next(value for name, value in variants(source, item["id"], np)
                                     if name == condition)
        turns = []
        for reader in readers:
            item = by_reader[reader][0]
            turns.append((item, 0, len(audio[item["id"]]), "long"))
        for reader in reversed(readers):
            item = by_reader[reader][1]
            turns.append((item, 0, SAMPLE_RATE * 8 // 10, "short"))
        for reader in readers:
            item = by_reader[reader][1]
            turns.append((item, SAMPLE_RATE * 8 // 10, len(audio[item["id"]]), "return"))
        pieces = []
        probes = []
        position = 0
        gap_ms = (0, 100, 1300, 300, 0, 900)
        for index, (item, begin, finish, kind) in enumerate(turns):
            if index:
                gap = gap_ms[(index - 1) % len(gap_ms)] * SAMPLE_RATE // 1000
                pieces.append(np.zeros(gap, dtype=np.float32))
                position += gap
            part = audio[item["id"]][begin:finish]
            for local in range(0, len(part), SAMPLE_RATE // 10):
                probes.append({"start_ms": (position + local) * 1000 // SAMPLE_RATE,
                               "end_ms": (position + min(local + SAMPLE_RATE // 10, len(part))) * 1000 // SAMPLE_RATE,
                               "speaker": item["speaker"], "turn_kind": kind})
            pieces.append(part)
            position += len(part)
        filename = f"speaker-conversation-{condition}.wav"
        samples = write_pcm(output_dir / filename, np.concatenate(pieces), np)
        duration_ms = len(samples) * 1000 // SAMPLE_RATE
        contexts = probe_contexts(probes, duration_ms)
        windows = embeddings(extractor, samples, np, contexts)
        cases.append({"condition": condition, "audio_file": filename, "duration_ms": duration_ms,
                      "probes": probes, "windows": windows})
        print(f"Speaker clip-time evaluation {condition}: {len(readers)} readers, "
              f"{len(probes)} nonoverlapping reference cells, {len(windows)} continuous contexts", flush=True)
    return {"scope": "Fixed reader-disjoint clip-time attribution; not speech DER or ASR word accuracy",
            "source_manifest_sha256": digest(FIXTURE_ROOT / "evaluation_manifest.json"),
            "cases": cases}


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
    evaluation = json.loads((FIXTURE_ROOT / "evaluation_manifest.json").read_text())
    if evaluation["schema_version"] != 1 or evaluation["sample_rate"] != SAMPLE_RATE:
        raise ValueError("Unexpected evaluation fixture manifest")
    if {item["speaker"] for item in manifest["fixtures"]} & {item["speaker"] for item in evaluation["fixtures"]}:
        raise ValueError("Evaluation readers must be disjoint from development readers")
    result = {"schema_version": 1, "model_sha256": MODEL_SHA256,
              "sample_rate": SAMPLE_RATE, "fixtures": fixtures,
              "evaluation": conversation_fixtures(extractor, evaluation, args.output.parent, np)}
    args.output.write_text(json.dumps(result, separators=(",", ":"), allow_nan=False) + "\n")
    print(f"Generated {len(fixtures)} public speech fixtures; no app or report artifact created.")


if __name__ == "__main__":
    main()
