import unittest

from evaluate_speech import edit_distance, error_counts, normalize_text, require_chunk_nonregression, timed_words


class SpeechEvaluationTest(unittest.TestCase):
    def test_russian_normalization_preserves_numbers_and_words(self):
        self.assertEqual("елка 2026 год", normalize_text("Ёлка, 2026 год!"))

    def test_counts_insertions_deletions_and_substitutions(self):
        self.assertEqual(1, edit_distance(["один", "два"], ["один", "три"]))
        self.assertEqual(1, edit_distance(["один", "два"], ["один"]))
        self.assertEqual(2, edit_distance([], ["один", "два"]))
        self.assertEqual(2, edit_distance(["один", "два"], []))

    def test_wer_and_cer_use_reference_denominators(self):
        self.assertEqual({"word_errors": 1, "words": 2, "char_errors": 1, "chars": 6},
                         error_counts("мой кот", "мой кит"))

    def test_empty_hypothesis_counts_as_missing_speech(self):
        self.assertEqual({"word_errors": 2, "words": 2, "char_errors": 6, "chars": 6},
                         error_counts("мой кот", ""))
        with self.assertRaises(ValueError):
            error_counts("...", "текст")

    def test_timed_ctc_keeps_repeated_letters_after_blank(self):
        self.assertEqual([("аа", 100, 500), ("б", 600, 700)],
                         timed_words([0, 0, 3, 0, 2, 1], ["а", "б", " "], 100, 600))

    def test_timed_ctc_rejects_bad_vocabulary_ids(self):
        with self.assertRaises(ValueError):
            timed_words([3], ["а"], 0, 100)

    def test_chunk_gate_does_not_hide_one_condition_regression_in_total_gain(self):
        with self.assertRaisesRegex(RuntimeError, "channel: 4 > 3"):
            require_chunk_nonregression({
                "clean": ({"word_errors": 8}, {"word_errors": 2}),
                "channel": ({"word_errors": 3}, {"word_errors": 4}),
            })

    def test_chunk_gate_accepts_unchanged_or_better_word_accuracy(self):
        require_chunk_nonregression({
            "clean": ({"word_errors": 3}, {"word_errors": 3}),
            "channel": ({"word_errors": 5}, {"word_errors": 4}),
        })
