"""Checks for the local ASR study tooling; CI never runs model inference or downloads audio."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from asr_metrics import (align, analyze_errors, assign_words, classify_run, clip_timing, compare_runs, error_runs,
                         operation_times, percentile, score_blocks, summarize_timing)
from asr_study import load_study, role_manifest, validate_study
from evaluate_dialogs import MANIFEST, blocks
from evaluate_speech import edit_distance
from reference_asr import REFERENCE, load_tokens, verify_reference

HAS_NUMPY = importlib.util.find_spec("numpy") is not None


def synthetic_block():
    return {
        "id": "public-block-000", "duration_ms": 10000, "asr_seconds": 2.0, "model_seconds": 1.5,
        "references": [
            {"speaker": "M", "start_ms": 0, "end_ms": 2000, "text": "Привет, Дима!", "emotion": "happy",
             "energy_bounds": {"30": [100, 1900]}},
            {"speaker": "D", "start_ms": 2100, "end_ms": 5000, "text": "Как дела?", "emotion": "neutral",
             "energy_bounds": {"30": [200, 2700]}}],
        "words": [{"text": "привет", "start_ms": 150, "end_ms": 600}, {"text": "дима", "start_ms": 900, "end_ms": 1800},
                  {"text": "как", "start_ms": 2400, "end_ms": 2700}, {"text": "дело", "start_ms": 3000, "end_ms": 3600}],
        "asr_windows": [{"core_start": 0, "ids": [1, 2, 3]}, {"core_start": 48000, "ids": [4, 5, 6]}]}


class StudySelectionTest(unittest.TestCase):
    def setUp(self):
        self.study = load_study()
        self.earlier = json.loads(MANIFEST.read_text())

    def test_committed_selection_is_valid_and_disjoint_from_the_earlier_selection(self):
        validate_study(self.study, self.earlier)
        self.assertEqual(["masha_dima_part1"], [r["id"] for r in self.study["roles"]["development"]["recordings"]])
        self.assertEqual(["masha_dima_part3", "masha_sveta_part3"],
                         [r["id"] for r in self.study["roles"]["validation"]["recordings"]])

    def test_development_files_are_identical_to_the_frozen_two_hour_recording(self):
        earlier = next(r for r in self.earlier["recordings"] if r["id"] == "masha_dima_part1")
        self.assertEqual(earlier["files"], self.study["roles"]["development"]["recordings"][0]["files"])

    def test_validation_cannot_reuse_an_earlier_recording_or_payload(self):
        reused = copy.deepcopy(self.study)
        reused["roles"]["validation"]["recordings"][0]["id"] = "masha_sveta_part1"
        with self.assertRaisesRegex(ValueError, "earlier frozen recording"):
            validate_study(reused, self.earlier)
        overlapping = copy.deepcopy(self.study)
        overlapping["roles"]["validation"]["recordings"][0]["files"][0]["sha256"] = \
            self.earlier["recordings"][1]["files"][0]["sha256"]
        with self.assertRaises(ValueError):
            validate_study(overlapping, self.earlier)

    def test_validation_paths_cannot_escape_the_corpus(self):
        escaped = copy.deepcopy(self.study)
        escaped["roles"]["validation"]["recordings"][0]["files"][0]["path"] = "../../private.wav"
        with self.assertRaises(ValueError):
            validate_study(escaped, self.earlier)

    def test_blocks_keep_order_and_never_split_an_utterance(self):
        for role in ("development", "validation"):
            manifest = role_manifest(self.study, role)
            expected = [item["path"] for r in manifest["recordings"] for item in r["files"]]
            result = list(blocks(manifest))
            actual = [item["path"] for _, items in result for item in items]
            self.assertEqual(expected, actual)
            self.assertTrue(all(0 < sum(item["duration_seconds"] for item in items) <= 300 for _, items in result))

    def test_unknown_role_is_rejected(self):
        with self.assertRaises(ValueError):
            role_manifest(self.study, "test")


class MetricsTest(unittest.TestCase):
    def test_words_in_a_gap_go_to_the_nearest_clip(self):
        references = [{"start_ms": 0, "end_ms": 1000}, {"start_ms": 1100, "end_ms": 2000}]
        words = [{"text": "a", "start_ms": 100, "end_ms": 300}, {"text": "b", "start_ms": 1060, "end_ms": 1080},
                 {"text": "c", "start_ms": 1010, "end_ms": 1030}, {"text": "d", "start_ms": 1500, "end_ms": 1600}]
        first, second = assign_words(references, words)
        self.assertEqual(["a", "c"], [word["text"] for word in first])
        self.assertEqual(["b", "d"], [word["text"] for word in second])

    def test_clip_timing_reports_signed_errors_and_overlap(self):
        reference = {"start_ms": 1000, "end_ms": 3000, "energy_bounds": {"30": [200, 1800]}}
        words = [{"start_ms": 1300, "end_ms": 1500}, {"start_ms": 2500, "end_ms": 2700}]
        row = clip_timing(reference, words)
        self.assertEqual((300, -300), (row["start_error_ms"], row["end_error_ms"]))
        self.assertAlmostEqual(0.7, row["iou"])
        self.assertEqual((100, -100), (row["energy_start_error_ms"], row["energy_end_error_ms"]))
        self.assertAlmostEqual(0.875, row["energy_iou"])
        self.assertIsNone(clip_timing(reference, []))

    def test_timing_summary_keeps_bias_separate_from_absolute_error(self):
        summary = summarize_timing([-100, 50, 300, -400])
        self.assertEqual(4, summary["n"])
        self.assertEqual(-25.0, summary["median_signed_ms"])
        self.assertEqual(200.0, summary["median_abs_ms"])
        self.assertEqual(212.5, summary["mean_abs_ms"])
        self.assertEqual(370.0, summary["p90_abs_ms"])
        self.assertEqual(0.5, summary["within_100_ms"])
        self.assertEqual(1.0, summary["within_500_ms"])
        self.assertEqual({"n": 0}, summarize_timing([]))
        with self.assertRaises(ValueError):
            percentile([], .5)

    def test_alignment_separates_substitutions_deletions_and_insertions(self):
        operations = align(["a", "b", "c", "d"], ["a", "x", "c"])
        self.assertEqual(["ok", "sub", "ok", "del"], [operation for operation, _, _ in operations])
        self.assertEqual([([1], [1]), ([3], [])], error_runs(operations))
        self.assertEqual("insertion", classify_run([], ["x"], edit_distance))
        self.assertEqual("deletion", classify_run(["x"], [], edit_distance))

    def test_error_classes_recognize_word_boundaries_and_close_forms(self):
        self.assertEqual("word boundary (split or merge)", classify_run(["как", "то"], ["както"], edit_distance))
        self.assertEqual("close form (at most 2 letter edits)", classify_run(["красивый"], ["красивая"], edit_distance))
        self.assertEqual("other substitution", classify_run(["да"], ["нет"], edit_distance))

    def test_score_blocks_counts_words_groups_timing_and_speed(self):
        summary = score_blocks([synthetic_block()], edit_distance)
        self.assertEqual((1, 4), (summary["clip_level"]["word_errors"], summary["clip_level"]["words"]))
        self.assertEqual((1, 4), (summary["block_level"]["word_errors"], summary["block_level"]["words"]))
        self.assertEqual(1, summary["groups"]["speaker"]["D"]["word_errors"])
        self.assertEqual(0, summary["groups"]["speaker"]["M"]["word_errors"])
        self.assertEqual(0.2, summary["speed"]["rtf"])
        self.assertEqual(2, summary["timing_vs_clip_bounds"]["start"]["n"])
        self.assertEqual(225.0, summary["timing_vs_clip_bounds"]["start"]["median_signed_ms"])
        self.assertEqual(0, summary["clip_level"]["clips_without_words"])

    def test_deleted_clip_is_an_error_not_a_skip(self):
        block = synthetic_block()
        block["words"] = block["words"][:2]
        summary = score_blocks([block], edit_distance)
        self.assertEqual((2, 4), (summary["clip_level"]["word_errors"], summary["clip_level"]["words"]))
        self.assertEqual(1, summary["clip_level"]["clips_without_words"])

    def test_error_analysis_reports_counts_without_transcripts(self):
        analysis = analyze_errors([synthetic_block()], edit_distance)
        self.assertEqual((4, 1), (analysis["reference_words"], analysis["error_words"]))
        self.assertEqual({"close form (at most 2 letter edits)": 1}, analysis["classes"])
        self.assertEqual([1, 2], [analysis["by_chunk_seam"]["within 1.5 s of a chunk seam"][key]
                                  for key in ("errors", "reference_words")])
        self.assertEqual([0, 2], [analysis["by_chunk_seam"]["elsewhere"][key] for key in ("errors", "reference_words")])
        self.assertEqual(1, analysis["by_position"]["last word"]["errors"])
        self.assertEqual([], analysis["repeated_single_word_substitutions"])

    def test_a_deleted_word_takes_the_time_of_its_nearest_recognized_neighbour(self):
        operations = align(["a", "b", "c"], ["a", "c"])
        self.assertEqual(["ok", "del", "ok"], [operation for operation, _, _ in operations])
        self.assertEqual([100.0, 100.0, 900.0], operation_times(operations, [100.0, 900.0], 0))
        leading = align(["b", "c"], ["c"])
        self.assertEqual([900.0, 900.0], operation_times(leading, [900.0], 0))
        self.assertEqual([5000], operation_times([("del", 0, None)], [], 5000))

    def test_run_comparison_distinguishes_identical_and_changed_tokens(self):
        first, second = synthetic_block(), synthetic_block()
        same = compare_runs([first], [second], edit_distance)
        self.assertEqual((1, 0, 6), (same["blocks_bitwise_identical"], same["different_frames"], same["frames"]))
        second["asr_windows"][1]["ids"] = [4, 5, 7]
        changed = compare_runs([first], [second], edit_distance)
        self.assertEqual((0, 1, 1), (changed["blocks_bitwise_identical"], changed["different_frames"],
                                     changed["windows_with_different_ids"]))


class ReferenceModelTest(unittest.TestCase):
    def test_reference_pins_are_immutable_revisions_and_sha256_values(self):
        self.assertRegex(REFERENCE["revision"], r"^[0-9a-f]{40}$")
        self.assertRegex(REFERENCE["upstream"]["revision"], r"^[0-9a-f]{40}$")
        for record in REFERENCE["files"].values():
            self.assertRegex(record["sha256"], r"^[0-9a-f]{64}$")
            self.assertGreater(record["bytes"], 0)

    def test_missing_or_altered_reference_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "failed verification"):
                verify_reference(directory)

    def test_vocabulary_must_have_seventy_characters_then_the_blank(self):
        entries = ["▁"] + [chr(0x430 + index) for index in range(1, 70)]
        lines = [f"{token} {index}" for index, token in enumerate(entries)] + ["<blk> 70"]
        with tempfile.TemporaryDirectory() as directory:
            good = Path(directory) / "vocab.txt"
            good.write_text("\n".join(lines) + "\n", encoding="utf-8")
            self.assertEqual(entries, load_tokens(good))
            bad = Path(directory) / "short.txt"
            bad.write_text("\n".join(lines[:-2]) + "\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                load_tokens(bad)

    @unittest.skipUnless(HAS_NUMPY, "NumPy is installed only in the evaluation environment")
    def test_features_use_bfloat16_window_and_filterbank_like_the_upstream_checkpoint(self):
        import numpy as np
        from reference_asr import bfloat16, hann_window, log_mel, mel_filterbank
        self.assertEqual(1.0, float(bfloat16(np.array([1 + 2.0 ** -9], dtype=np.float32), np)[0]))
        self.assertEqual(0.10009765625, float(bfloat16(np.array([0.1], dtype=np.float32), np)[0]))
        window = hann_window(np)
        self.assertEqual((320,), window.shape)
        self.assertEqual((0.0, 1.0), (float(window[0]), float(window[160])))
        bank = mel_filterbank(np)
        self.assertEqual((161, 64), bank.shape)
        self.assertTrue((bank >= 0).all() and (bank.sum(axis=0) > 0).all())
        silence = log_mel(np.zeros(16000, dtype=np.float32), np)
        self.assertEqual((64, 99), silence.shape)
        self.assertTrue(np.allclose(silence, np.log(1e-9)))
        with self.assertRaises(ValueError):
            log_mel(np.zeros(100, dtype=np.float32), np)


if __name__ == "__main__":
    unittest.main()
