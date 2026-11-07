"""Public, hash-pinned GigaAM Multilingual 220M CTC export for local ASR studies.

The deployed Ascent model pack is published by the project's own server and
could not be fetched when this study was written, so the reproducible baseline
uses the public ONNX conversion of the same upstream family. It is an
equivalent reference, **not** the deployed artifact: the graph takes mel
features instead of a waveform, the feature extractor below is a NumPy mirror
of the upstream one, and numerical results may differ slightly from the
production export. Nothing here is shipped, and no weights are trained.

`ReferenceSession` exposes the same ``run`` contract as the deployed
waveform graph, so the existing ``evaluate_speech`` / ``evaluate_dialogs``
chunking, CTC timing and ownership code run unchanged.
"""
from __future__ import annotations

import hashlib
import os
import platform
import re
import sys
from pathlib import Path

RATE = 16000
WIN = N_FFT = 320
HOP = 160
MELS = 64
SUBSAMPLING = 4
BLANK_CLASSES = 71  # 70 characters plus the CTC blank

REFERENCE = {
    "repository": "istupakov/gigaam-multilingual-ctc-onnx",
    "revision": "458860e1983aef670dd9795fb6af603c82767d5d",
    "license": "MIT",
    "upstream": {"repository": "ai-sage/GigaAM-Multilingual",
                 "revision": "2f8a57144e6ec3adfd32fe0484d9ea9913305bc8", "license": "MIT"},
    "files": {
        "multilingual_ctc.onnx": {"bytes": 885388622,
                                  "sha256": "8bc803289f9cb5147ee95451fd9bdba219b1ecf1ddcd59a3651177c103c9eeec"},
        "multilingual_vocab.txt": {"bytes": 393,
                                   "sha256": "4d130287892e1099fedfb3f93c4b4cf8a263151158801680b28977d1be4133f4"},
        "multilingual_ctc.yaml": {"bytes": 1172,
                                  "sha256": "b0235f72dc1ebee082162527d1576c6a6915647a22e5b0414d0c5bbc43c026ab"},
    },
}


def reference_url(name):
    return f"https://huggingface.co/{REFERENCE['repository']}/resolve/{REFERENCE['revision']}/{name}"


def load_tokens(path):
    """Vocabulary in the app's layout: 70 characters, ``▁`` for space, blank = len(tokens)."""
    entries = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"(.+) ([0-9]+)", line)
        if not match or int(match[2]) != len(entries):
            raise ValueError("Malformed reference vocabulary")
        entries.append(match[1])
    if len(entries) != BLANK_CLASSES or entries[-1] != "<blk>":
        raise ValueError("Unexpected reference vocabulary size")
    return entries[:-1]


def bfloat16(values, np):
    """Round float32 values to bfloat16 (nearest even) and return them as float32.

    The upstream GigaAM v3-family checkpoints store the extractor's Hann window
    and mel filterbank as bfloat16 buffers. Mirroring that rounding reproduces
    the published arrays bit for bit; skipping it shifts filter weights by up to
    2**-9 (verified against the ``onnx-asr`` reference arrays).
    """
    bits = np.ascontiguousarray(values, dtype=np.float32).view(np.uint32)
    rounded = (bits + np.uint32(0x7FFF) + ((bits >> np.uint32(16)) & np.uint32(1))) & np.uint32(0xFFFF0000)
    return rounded.view(np.float32)


def hann_window(np):
    return bfloat16(np.hanning(WIN + 1)[:-1].astype(np.float32), np)  # periodic Hann


def mel_filterbank(np):
    """HTK mel triangles, no area normalization (torchaudio ``melscale_fbanks``), bfloat16 buffer."""
    bins = N_FFT // 2 + 1
    frequencies = np.linspace(0.0, RATE / 2, bins, dtype=np.float64)

    def to_mel(hertz):
        return 2595.0 * np.log10(1.0 + hertz / 700.0)

    points = 700.0 * (10.0 ** (np.linspace(to_mel(0.0), to_mel(RATE / 2), MELS + 2) / 2595.0) - 1.0)
    widths = np.diff(points)
    slopes = points[None, :] - frequencies[:, None]
    down = -slopes[:, :-2] / widths[:-1]
    up = slopes[:, 2:] / widths[1:]
    return bfloat16(np.maximum(0.0, np.minimum(down, up)).astype(np.float32), np)


def log_mel(samples, np, bank=None, window=None):
    """Log-mel features, shape (MELS, frames); matches the upstream ``center=False`` extractor."""
    samples = np.ascontiguousarray(samples, dtype=np.float32)
    if len(samples) < WIN:
        raise ValueError("Audio is shorter than one analysis window")
    bank = mel_filterbank(np) if bank is None else bank
    frames = (len(samples) - WIN) // HOP + 1
    window = hann_window(np) if window is None else window
    view = np.lib.stride_tricks.sliding_window_view(samples, WIN)[::HOP][:frames]
    power = np.abs(np.fft.rfft(view * window, n=N_FFT, axis=1)).astype(np.float32) ** 2
    return np.log(np.clip(power @ bank, 1e-9, 1e9)).T.astype(np.float32)


class ReferenceSession:
    """Waveform-in, logits-out wrapper with the deployed graph's ``run`` signature."""

    def __init__(self, model_path, threads, ort, np):
        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        self.np = np
        self.threads = threads
        self.bank = mel_filterbank(np)
        self.window = hann_window(np)
        self.session = ort.InferenceSession(str(model_path), sess_options=options,
                                            providers=["CPUExecutionProvider"])
        names = [item.name for item in self.session.get_inputs()]
        if names != ["features", "feature_lengths"]:
            raise ValueError("Unexpected reference graph inputs")

    def run(self, _outputs, feeds):
        np = self.np
        audio = feeds["audio"]
        if audio.ndim != 2 or audio.shape[0] != 1:
            raise ValueError("The reference wrapper handles one waveform at a time")
        features = log_mel(audio[0], np, self.bank, self.window)[None]
        logits, lengths = self.session.run(None, {
            "features": features, "feature_lengths": np.array([features.shape[2]], dtype=np.int64)})
        return [logits, lengths.astype(np.int64)]


def sha256_stream(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_reference(directory):
    """Every reference file must match the pinned byte count and SHA-256."""
    directory = Path(directory)
    for name, record in REFERENCE["files"].items():
        path = directory / name
        if not path.is_file() or path.stat().st_size != record["bytes"] or sha256_stream(path) != record["sha256"]:
            raise ValueError(f"Reference model file failed verification: {name}")


def environment_fingerprint(ort, np, threads):
    """Facts that can change float results; recorded with every inference checkpoint."""
    model, flags = "unknown", set()
    try:
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            key, _, value = line.partition(":")
            if key.strip() == "model name" and model == "unknown":
                model = value.strip()
            if key.strip() == "flags" and not flags:
                flags = set(value.split())
    except OSError:
        pass
    return {"python": sys.version.split()[0], "numpy": np.__version__, "onnxruntime": ort.__version__,
            "platform": platform.platform(), "cpu_model": model,
            "cpu_features": sorted(flags & {"avx", "avx2", "avx512f", "avx512_vnni", "fma", "amx_tile"}),
            "logical_cores": os.cpu_count(), "intra_op_threads": threads, "inter_op_threads": 1,
            "execution_mode": "sequential", "graph_optimization": "ORT default (ENABLE_ALL)"}
