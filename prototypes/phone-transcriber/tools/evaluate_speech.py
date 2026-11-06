"""CI-only ASR measurements on checksum-pinned public Russian reader fixtures.

These are development regression measurements, not a held-out conversational
benchmark. No weights are trained and no application audio is read. Aggregate
scores (never transcripts) remain in the CI console, outside the model pack.
"""

import json
import unicodedata

from generate_speaker_fixtures import FIXTURE_ROOT, load_fixture, variants
from model_pack import ctc_decode

SAMPLE_RATE = 16000


def normalize_text(text):
    text = unicodedata.normalize("NFKC", text).lower().replace("ё", "е")
    return " ".join("".join(char if char.isalnum() else " " for char in text).split())


def edit_distance(reference, hypothesis):
    """Levenshtein edit count; memory is bounded by the hypothesis length."""
    previous = list(range(len(hypothesis) + 1))
    for row, expected in enumerate(reference, 1):
        current = [row]
        for column, actual in enumerate(hypothesis, 1):
            current.append(min(current[-1] + 1, previous[column] + 1,
                               previous[column - 1] + (expected != actual)))
        previous = current
    return previous[-1]


def error_counts(reference, hypothesis):
    expected = normalize_text(reference)
    actual = normalize_text(hypothesis)
    if not expected:
        raise ValueError("Speech reference must not be empty")
    expected_chars = expected.replace(" ", "")
    actual_chars = actual.replace(" ", "")
    return {"word_errors": edit_distance(expected.split(), actual.split()),
            "words": len(expected.split()), "char_errors": edit_distance(expected_chars, actual_chars),
            "chars": len(expected_chars)}


def timed_words(ids, tokens, offset_ms, duration_ms):
    """Host mirror of CtcDecoder.decode, including repeated-token word end times."""
    words = []
    text = []
    start = end = 0
    blank = len(tokens)
    previous = blank

    def flush():
        if text:
            begin = offset_ms + start * duration_ms // len(ids)
            finish = max(begin + 1, offset_ms + end * duration_ms // len(ids))
            words.append(("".join(text), begin, finish))
            text.clear()

    for frame, token_id in enumerate(ids):
        token_id = int(token_id)
        if not 0 <= token_id <= blank:
            raise ValueError("CTC token outside vocabulary")
        if token_id != blank:
            if token_id != previous:
                for char in tokens[token_id].replace("▁", " "):
                    if char.isspace():
                        flush()
                    else:
                        if not text:
                            start = frame
                        text.append(char)
                        end = frame + 1
            elif text:
                end = frame + 1
        previous = token_id
    flush()
    return words


def chunk_windows(samples, silence_aware, np):
    """Host mirror of AsrChunking.next; baseline is the previous 20s/0.5s policy."""
    core_start = 0
    context = SAMPLE_RATE * 5 // 4 if silence_aware else SAMPLE_RATE // 2
    while core_start < len(samples):
        core_end = min(core_start + 20 * SAMPLE_RATE, len(samples))
        if silence_aware and core_end < len(samples):
            search_start = core_end - 2 * SAMPLE_RATE
            search = samples[search_start:core_end].astype(np.float64)
            energy = np.mean(np.square(search.reshape(-1, SAMPLE_RATE // 50)), axis=1)
            if np.isfinite(energy).all():
                threshold = float(np.clip(np.sort(energy)[len(energy) * 9 // 10] * .02, .00000025, .000064))
                run_start = None
                seam = len(search)
                for frame in range(len(energy) + 1):
                    if frame < len(energy) and energy[frame] <= threshold:
                        if run_start is None:
                            run_start = frame
                    elif run_start is not None:
                        if frame - run_start >= 10:
                            seam = (run_start + frame) * (SAMPLE_RATE // 50) // 2
                        run_start = None
                core_end = search_start + seam
        yield core_start, core_end, max(0, core_start - context), min(len(samples), core_end + context)
        core_start = core_end


def infer_ids(session, audio, tokens, np):
    logits, lengths = session.run(None, {
        "audio": audio[None, :], "audio_lengths": np.array([len(audio)], dtype=np.int64)})
    valid = int(lengths[0])
    if (logits.ndim != 3 or logits.shape[0] != 1 or logits.shape[2] != len(tokens) + 1
            or not 0 < valid <= logits.shape[1] or not np.isfinite(logits).all()):
        raise RuntimeError("Invalid ASR output for public regression corpus")
    return logits.argmax(-1)[0][:valid]


def chunked_transcript(session, samples, tokens, silence_aware, np):
    words = []
    count = 0
    duration_ms = len(samples) * 1000 // SAMPLE_RATE
    for core_start, core_end, start, end in chunk_windows(samples, silence_aware, np):
        audio = samples[start:end]
        if len(audio) < 4000:
            audio = np.pad(audio, (0, 4000 - len(audio)))
        ids = infer_ids(session, audio, tokens, np)
        decoded = timed_words(ids, tokens, start * 1000 // SAMPLE_RATE, len(audio) * 1000 // SAMPLE_RATE)
        for word, begin, finish in decoded:
            if begin >= duration_ms:
                continue
            finish = min(finish, duration_ms)
            if core_start * 1000 // SAMPLE_RATE <= (begin + finish) // 2 < core_end * 1000 // SAMPLE_RATE:
                words.append((word, begin))
        count += 1
    return " ".join(word for word, _ in sorted(words, key=lambda item: item[1])), count


def require_chunk_nonregression(comparisons):
    """Do not let an improvement in one condition hide a regression in another."""
    regressions = [f"{condition}: {after['word_errors']} > {before['word_errors']} word errors"
                   for condition, (before, after) in comparisons.items()
                   if after["word_errors"] > before["word_errors"]]
    if regressions:
        raise RuntimeError("ASR chunk policy regressed against the fixed-window baseline: " + "; ".join(regressions))


def measure_chunk_policy(session, tokens, manifest, np):
    # The public clips are deliberately concatenated, not represented as a real
    # conversation. Alternating 0.3/1.3s pauses exercise known quiet seams.
    reference = " ".join(item["reference_text"] for item in manifest["fixtures"])
    comparisons = {}
    for condition in ("clean", "channel"):
        pieces = []
        for index, item in enumerate(manifest["fixtures"]):
            samples = load_fixture(item, np)
            audio = next(audio for name, audio in variants(samples, item["id"], np) if name == condition)
            pcm = np.clip(np.rint(audio * 32768.0), -32768, 32767).astype("<i2")
            if index:
                pieces.append(np.zeros(SAMPLE_RATE * (13 if index % 2 else 3) // 10, dtype=np.float32))
            pieces.append(pcm.astype(np.float32) / 32768.0)
        sequence = np.concatenate(pieces)
        counts_by_policy = []
        for silence_aware in (False, True):
            hypothesis, chunks = chunked_transcript(session, sequence, tokens, silence_aware, np)
            counts = error_counts(reference, hypothesis)
            counts_by_policy.append(counts)
            policy = "silence-aware20s/context1.25s" if silence_aware else "fixed20s/context0.5s"
            print(f"Russian ASR chunk comparison {condition}/{policy}: chunks={chunks}, "
                  f"WER={counts['word_errors'] / counts['words']:.4f}, "
                  f"CER={counts['char_errors'] / counts['chars']:.4f}", flush=True)
        before, after = counts_by_policy
        comparisons[condition] = (before, after)
        print(f"Russian ASR chunk delta {condition}: "
              f"word_errors={after['word_errors'] - before['word_errors']:+d}, "
              f"char_errors={after['char_errors'] - before['char_errors']:+d}", flush=True)
    print("Chunk comparison scope: host mirror of Kotlin windows/CTC/ownership on synthetic sequences; "
          "not Android runtime or natural conversational accuracy.", flush=True)
    # Compare identical public inputs and model weights; no absolute WER target
    # or claimed improvement is inferred from this small development corpus.
    require_chunk_nonregression(comparisons)


def measure_public_speech(session, tokens):
    """Use the already verified exported ONNX session; no extra checkpoint download."""
    import numpy as np

    manifest = json.loads((FIXTURE_ROOT / "manifest.json").read_text(encoding="utf-8"))
    totals = {}
    for item in manifest["fixtures"]:
        samples = load_fixture(item, np)
        for condition, audio in variants(samples, item["id"], np):
            # Match the app's signed PCM16 input; avoid measuring a float-only path.
            pcm = np.clip(np.rint(audio * 32768.0), -32768, 32767).astype("<i2")
            normalized = pcm.astype(np.float32) / 32768.0
            ids = infer_ids(session, normalized, tokens, np)
            hypothesis, _ = ctc_decode(ids, tokens, len(ids))
            counts = error_counts(item["reference_text"], hypothesis)
            total = totals.setdefault(condition, {"clips": 0, "word_errors": 0, "words": 0,
                                                   "char_errors": 0, "chars": 0, "empty": 0,
                                                   "wer_sum": 0.0, "cer_sum": 0.0})
            total["clips"] += 1
            total["empty"] += int(not normalize_text(hypothesis))
            for key, value in counts.items():
                total[key] += value
            total["wer_sum"] += counts["word_errors"] / counts["words"]
            total["cer_sum"] += counts["char_errors"] / counts["chars"]
    for condition, total in totals.items():
        print(f"Russian ASR {condition}: clips={total['clips']}, words={total['words']}, "
              f"WER={total['word_errors'] / total['words']:.4f}, "
              f"CER={total['char_errors'] / total['chars']:.4f}, "
              f"macro_WER={total['wer_sum'] / total['clips']:.4f}, "
              f"macro_CER={total['cer_sum'] / total['clips']:.4f}, empty={total['empty']}", flush=True)
    print("ASR scope: fixed public audiobook development corpus; no training, held-out claim or phone measurement.",
          flush=True)
    if totals["clean"]["empty"]:
        raise RuntimeError("Clean public regression speech produced an empty transcript")
    measure_chunk_policy(session, tokens, manifest, np)
