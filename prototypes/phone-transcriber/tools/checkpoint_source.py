"""Pinned official Hugging Face checkpoint transport; no remote Python execution.

This is a separately identified serialization, not a byte-identical mirror of
the CDN .ckpt. Keep the original upstream loader available for comparison.
"""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request

REPOSITORY = "ai-sage/GigaAM-Multilingual"
REVISION = "2f8a57144e6ec3adfd32fe0484d9ea9913305bc8"
FILES = {
    "config.json": {
        "bytes": 2623,
        "sha256": "c830232c7d51688a630a221517b52585ab5ee57e1d3c21bcbae01759351d2653",
    },
    "pytorch_model.bin": {
        "bytes": 883170115,
        "sha256": "e1db43873ec5e296f229572e06e2470fc157ac9f8d4aacabda295630b9b91728",
    },
}
TARGETS = {
    "modeling_gigaam.FeatureExtractor": "gigaam.preprocess.FeatureExtractor",
    "modeling_gigaam.ConformerEncoder": "gigaam.encoder.ConformerEncoder",
    "modeling_gigaam.CTCHead": "gigaam.decoder.CTCHead",
    "modeling_gigaam.CTCGreedyDecoding": "gigaam.decoding.CTCGreedyDecoding",
}


def verified(path: Path, record: dict) -> bool:
    if path.is_symlink() or not path.is_file() or path.stat().st_size != record["bytes"]:
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() == record["sha256"]


class HttpsOnlyRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise ValueError("Checkpoint redirect must remain HTTPS")
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def fetch_file(name: str, directory: Path) -> Path:
    record = FILES[name]
    target = directory / name
    if verified(target, record):
        return target
    directory.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".part")
    opener = urllib.request.build_opener(HttpsOnlyRedirect())
    url = f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/{name}"
    for attempt in range(3):
        try:
            # Remove abandoned staging, including links, before creating our file.
            temporary.unlink(missing_ok=True)
            request = urllib.request.Request(url, headers={"User-Agent": "Ascent-model-export/0.1"})
            with opener.open(request, timeout=60) as source, temporary.open("xb") as output:
                if urllib.parse.urlsplit(source.geturl()).scheme != "https":
                    raise ValueError("Checkpoint transport must remain HTTPS")
                count = 0
                while block := source.read(1024 * 1024):
                    count += len(block)
                    if count > record["bytes"]:
                        raise ValueError("Checkpoint exceeds pinned size")
                    output.write(block)
            if not verified(temporary, record):
                raise ValueError("Checkpoint size/SHA-256 verification failed")
            temporary.replace(target)
            return target
        except (OSError, ValueError):
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)
        finally:
            temporary.unlink(missing_ok=True)
    raise AssertionError("Unreachable")


def upstream_config(document: dict) -> dict:
    """Map only the four reviewed classes to the already pinned upstream code."""
    model = document["cfg"]["model"]
    if model["_target_"] != "modeling_gigaam.GigaAMASR":
        raise ValueError("Unsupported Hugging Face model class")
    config = copy.deepcopy(model["cfg"])
    if (config["model_name"] != "multilingual_ctc" or config["model_class"] != "ctc"
            or config["sample_rate"] != 16000):
        raise ValueError("Unsupported Hugging Face model configuration")
    for section, expected in zip(("preprocessor", "encoder", "head", "decoding"), TARGETS):
        if config[section]["_target_"] != expected:
            raise ValueError(f"Unsupported checkpoint component: {section}")
        config[section]["_target_"] = TARGETS[expected]
    return config


def upstream_state(state: dict) -> dict:
    """Strip the HF wrapper only; strict upstream loading checks all tensors."""
    if not state or any(not isinstance(key, str) or not key.startswith(
            ("model.preprocessor.", "model.encoder.", "model.head.")) for key in state):
        raise ValueError("Unexpected Hugging Face state dictionary keys")
    return {key.removeprefix("model."): value for key, value in state.items()}


def load_huggingface(directory: Path):
    import torch
    from gigaam.model import GigaAMASR
    from omegaconf import OmegaConf

    directory = directory / REVISION
    config_file = fetch_file("config.json", directory)
    weights = fetch_file("pytorch_model.bin", directory)
    config = upstream_config(json.loads(config_file.read_text(encoding="utf-8")))
    # No AutoModel, trust_remote_code, checkpoint unpickling of arbitrary classes,
    # quantization, tensor reshaping, or permissive missing-key loading.
    state = upstream_state(torch.load(weights, map_location="cpu", weights_only=True, mmap=True))
    model = GigaAMASR(OmegaConf.create(config))
    model.load_state_dict(state, strict=True)
    source = {
        "checkpoint_format": "huggingface-state-dict",
        "checkpoint_repository": REPOSITORY,
        "checkpoint_revision": REVISION,
        "checkpoint_url": f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/pytorch_model.bin",
        "checkpoint_sha256": FILES["pytorch_model.bin"]["sha256"],
        "checkpoint_bytes": FILES["pytorch_model.bin"]["bytes"],
        "config_sha256": FILES["config.json"]["sha256"],
    }
    return model.eval().to("cpu"), source
