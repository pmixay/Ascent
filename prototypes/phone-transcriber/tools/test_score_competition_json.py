import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from score_competition_json import load_transcript, score_item, summarize


def seg(speaker, start, end, text):
    return {"speaker_id": speaker, "start_ms": start, "end_ms": end, "text": text}


REFERENCE = [seg("A", 0, 2000, "Привет, как дела?"), seg("B", 2100, 4000, "Нормально, а у тебя?"),
             seg("A", 4100, 6000, "Тоже ёлки хорошо.")]


class ScoreCompetitionJsonTest(unittest.TestCase):
    def test_perfect_prediction_with_renamed_speakers_has_no_errors(self):
        predicted = [dict(s, speaker_id={"A": "speaker_2", "B": "speaker_1"}[s["speaker_id"]],
                          text=s["text"].lower().replace("ё", "е")) for s in REFERENCE]
        item = score_item(REFERENCE, predicted, 6000, 600)
        self.assertEqual((item["word_errors"], item["cp_word_errors"], item["char_errors"]), (0, 0, 0))
        self.assertEqual((item["speaker_correct_ms"], item["speech_ms"]), (5800, 5800))
        self.assertEqual((item["matched_segments"], item["unmatched_segments"]), (3, 0))
        total = summarize([item])
        self.assertEqual((total["WER"], total["cpWER"], total["speaker_time_accuracy"], total["RTF"]), (0.0, 0.0, 1.0, 0.1))

    def test_one_predicted_speaker_counts_the_other_speakers_words_as_cp_errors(self):
        predicted = [dict(s, speaker_id="speaker_1") for s in REFERENCE]
        item = score_item(REFERENCE, predicted)
        self.assertEqual(item["word_errors"], 0)
        # B's four words are deleted from B and inserted into the single predicted speaker.
        self.assertEqual(item["cp_word_errors"], 8)
        self.assertEqual((item["speaker_correct_ms"], item["predicted_speakers"]), (3900, 1))

    def test_joined_phrases_are_reported_as_unmatched_boundaries(self):
        predicted = [seg("x", 0, 6000, "привет как дела нормально а у тебя тоже елки хорошо")]
        item = score_item(REFERENCE, predicted)
        self.assertEqual((item["matched_segments"], item["unmatched_segments"]), (0, 3))
        shifted = [dict(s, start_ms=s["start_ms"] + 100, end_ms=s["end_ms"] + 40) for s in REFERENCE]
        item = score_item(REFERENCE, shifted)
        self.assertEqual(summarize([item])["start_error_median_ms"], 100)
        self.assertEqual(summarize([item])["end_error_p95_ms"], 40)

    def test_silence_scores_without_division_by_zero(self):
        total = summarize([score_item([], [], 1000, 50)])
        self.assertEqual((total["WER"], total["speaker_time_accuracy"], total["RTF"]), (None, None, 0.05))

    def test_rejects_anything_but_the_exact_brief_structure(self):
        good = {"transcript": [seg("A", 0, 10, "да")]}
        self.assertEqual(len(load_transcript(good, 10)), 1)
        for bad in ({"transcript": [], "extra": 1}, {"transcript": [dict(seg("A", 0, 10, "да"), inferred=True)]},
                    {"transcript": [seg("A", 0.0, 10, "да")]}, {"transcript": [seg("A", 10, 5, "да")]},
                    {"transcript": [seg(1, 0, 10, "да")]}, {"transcript": [seg("A", True, 10, "да")]}):
            with self.assertRaises(ValueError):
                load_transcript(bad)
        with self.assertRaises(ValueError):
            load_transcript(good, 5)

    def test_manifest_command_prints_only_aggregates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "ref.json").write_text(json.dumps({"transcript": REFERENCE}, ensure_ascii=False), encoding="utf-8")
            (root / "hyp.json").write_text(json.dumps({"transcript": REFERENCE}, ensure_ascii=False), encoding="utf-8")
            (root / "manifest.json").write_text(json.dumps({"items": [
                {"name": "call", "condition": "ru-call", "hypothesis": "hyp.json", "reference": "ref.json",
                 "duration_ms": 6000, "processing_ms": 1200}]}), encoding="utf-8")
            output = subprocess.run([sys.executable, str(Path(__file__).with_name("score_competition_json.py")),
                                     str(root / "manifest.json")], capture_output=True, text=True, check=True).stdout
            report = json.loads(output)
            self.assertEqual(report["per_condition"]["ru-call"]["RTF"], 0.2)
            self.assertNotIn("Привет", output)


if __name__ == "__main__":
    unittest.main()
