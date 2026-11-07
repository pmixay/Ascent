"""Contract cases for the independent jury-export validator (run in GitHub CI)."""

import json
import unittest

from validate_transcript import ContractError, validate_bytes


def encoded(turns, **extra):
    return json.dumps({"transcript": turns, **extra}, ensure_ascii=False).encode("utf-8")


def turn(speaker_id="speaker_1", start_ms=0, end_ms=1000, text="Привет!"):
    return dict(speaker_id=speaker_id, start_ms=start_ms, end_ms=end_ms, text=text)


class ValidateTranscriptTest(unittest.TestCase):
    def test_empty_speech_is_exportable(self):
        self.assertEqual(validate_bytes(encoded([]), duration_ms=5000), 0)

    def test_unicode_escaping_and_real_overlap(self):
        turns = [turn(text='Он сказал: "да"\nПроверка'),
                 turn(speaker_id="speaker_2", start_ms=500, end_ms=1200, text="Yes")]
        self.assertEqual(validate_bytes(encoded(turns), duration_ms=1200), 2)

    def test_exact_root_and_turn_keys(self):
        bad = [encoded([turn()], schemaVersion=1), encoded([{**turn(), "inferred": True}]),
               encoded([{k: v for k, v in turn().items() if k != "speaker_id"}])]
        for payload in bad:
            with self.subTest(payload=payload), self.assertRaises(ContractError):
                validate_bytes(payload)

    def test_duplicate_json_keys_are_rejected(self):
        with self.assertRaises(ContractError):
            validate_bytes(b'{"transcript":[],"transcript":[]}')

    def test_boolean_float_and_string_times_are_rejected(self):
        for start in [True, 1.0, "1"]:
            with self.subTest(start=start), self.assertRaises(ContractError):
                validate_bytes(encoded([turn(start_ms=start)]))

    def test_source_relative_bounds_and_chronology(self):
        invalid = [encoded([turn(start_ms=-1)]), encoded([turn(end_ms=0)]),
                   encoded([turn(end_ms=2000)]),
                   encoded([turn(start_ms=900), turn(start_ms=100, end_ms=200)])]
        for payload in invalid:
            with self.subTest(payload=payload), self.assertRaises(ContractError):
                validate_bytes(payload, duration_ms=1500)

    def test_invalid_utf8_empty_fields_and_non_json_numbers(self):
        invalid = [b'\xff', encoded([turn(text="  ")]), encoded([turn(speaker_id="")]),
                   b'{"transcript":[{"speaker_id":"s","start_ms":0,"end_ms":NaN,"text":"a"}]}']
        for payload in invalid:
            with self.subTest(payload=payload), self.assertRaises(ContractError):
                validate_bytes(payload)


if __name__ == "__main__":
    unittest.main()
