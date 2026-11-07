# ML defense notes

Speaking notes for the speech lead at the defense, 7 November 2026. Every figure comes from
[model-report.md](model-report.md), which holds denominators, sources and conditions. Do not
quote a number that is not there, and say "host" or "CI" with each one: **no Android device
measurement exists yet.**

## The pipeline in one minute

Everything runs on the phone, offline, one stage after another:

1. **Speech to text: GigaAM Multilingual CTC** (about 220M parameters, MIT). A Conformer
   encoder with a character-level CTC head for Russian and English. The recording is cut
   into 20 s windows with 1.25 s of context, and the cut is moved into a pause so that words
   are not split. Each window is level-normalized (peak to -1 dBFS, at most +30 dB) before
   the model. Greedy CTC decoding gives words with frame times (40 ms frames).
2. **Who spoke: ERes2NetV2 speaker embeddings** (3D-Speaker, Apache-2.0) on short windows
   around recognized speech, then clustering. Pruned spectral discovery with an
   average-linkage fallback finds the voices. A short phrase may join an established voice
   but cannot create one. Every word gets a participant label; best-effort assignments are
   marked inferred internally and never train voice memory.
3. **Phrases:** words are grouped by speaker; runs longer than 20 s are split at the longest
   pause. Times in the JSON are these CTC times, in milliseconds.
4. **Local LLM: Qwen2.5 0.5B Instruct, int8 ONNX** (Apache-2.0). It adds
   punctuation and capitals with one forward pass: at each word boundary the model scores
   ", . ? !" against the next word. It can only choose marks and capitals, so it cannot
   change, drop or invent words, speakers or times. It runs on the ONNX Runtime the
   recognizer already ships, with no extra native library. **Status:** packaged and integrated
   in the embedded APK, including raw-word competition JSON export. Pre-gain Android CI
   [42387321849](https://github.com/pmixay/Ascent/actions/runs/42387321849) passed an actual
   synthetic text-model call on x86_64. The latest merged candidate is recorded in
   [PR #4](https://github.com/pmixay/Ascent/pull/4); physical ARM acceptance remains open.

## Why these choices

- **GigaAM for Russian and CIS languages:** the best open Russian model family we could run
  on ONNX Runtime; it also covers Kazakh, Kyrgyz and Uzbek; MIT licence; CTC keeps decoding
  simple and gives word times.
- **Why recognition is split between two models (proposed modes: Russian + CIS with GigaAM,
  multilingual with Parakeet TDT 0.6B v3):** among open models small enough for a phone we
  found none that is good at both Russian and English. On the same data GigaAM makes 36-39%
  fewer Russian errors than the multilingual Parakeet v3 (validation 4.30% vs 6.73%), while
  Parakeet v3 makes 2.9 times fewer English errors on meeting headsets (18.57% vs 54.65%).
  Models that are strong on both, such as Whisper large-v3 with about 1.5B parameters, are
  too large for on-device use. **Say clearly whether the multilingual mode is in the shipped
  APK:** at the time of writing it is measured on the host only, not integrated.
- **CTC times instead of forced alignment:** no second model on the phone. The cost: times
  are approximate (host check against an energy proxy: start median +87 ms, end -14.5 ms).
- **Constrained LLM instead of free generation:** free generation rewrites words, which would
  raise WER. Scoring only marks keeps the words identical by construction.
- **Level normalization:** quiet input made the model drop words or switch to Cyrillic on
  English. It was chosen on one AMI meeting and measured once on another.

## Numbers to quote

| What | Value | Kind |
| --- | --- | --- |
| Russian WER, two hours of Dialogs (15,740 words) | 2.79% | CI, public reference export |
| Russian cpWER / speaker time (completed) | 10.28% / 95.31% | CI |
| Fresh Russian validation WER (10,053 words) | 4.30% | host |
| Telephone-channel validation WER | 4.63% | host |
| English read speech (LibriSpeech, 1,901 words) | 6.73% | host |
| English meeting, headsets / distant microphone, with level rule | 54.65% / 74.96% | host |
| Same, Parakeet TDT 0.6B v3 (multilingual candidate, not shipped) | 18.57% / 44.74% | host |
| Russian validation, Parakeet v3 vs GigaAM | 6.73% vs 4.30% | host |
| Recognition RTF, x86, 2 threads | about 0.07 | host |
| Whole pipeline RTF, x86 | about 0.26 | host |

## Honest limitations (say them before you are asked)

- No device result: speed and quality on the jury's ARM emulator are not measured. On an
  ARM host the emulator runs near native speed; pure software emulation on x86 was about
  270 times slower in our worst-case test.
- Dialogs is acted, constructed dialogue: two clean speakers with synthetic gaps. Real calls
  and meetings with overlap are harder.
- Conversational English is weak (about half the words wrong on AMI headsets); English
  diarization is unmeasured.
- Short replies and similar voices are the main source of speaker errors: about three
  quarters of cpWER beyond WER comes from attribution.
- Numbers are written as words and text is lowercase from the recognizer; the LLM adds
  punctuation and capitals only.

## Likely questions

- **"Is the LLM real or decorative?"** It is a real local model that decides every
  punctuation mark and capital of the text it processes; on 120 development clips (host) it
  raised correct capitals from 84.87% (lowercase baseline) to 88.98%. It does not touch
  recognition. Check whether the shipped APK already runs it before answering.
- **"Why not Whisper?"** We measured the phone-sized English Whispers on AMI meetings:
  tiny.en 34.55%, base.en 30.75%, small.en 29.39%, distil-small.en 28.06% on headsets, all
  behind Parakeet (18.02%). Through sherpa-onnx they give no word times, which speaker
  attribution and the JSON timestamps need, and they pad every input to 30 s. Whisper
  large-v3 (about 1.5B) is too large; by the GigaAM Multilingual card it is also worse on
  Russian Common Voice (9.1% vs 7.1%) and better on Russian FLEURS (3.1% vs 4.4%).
- **"What about SenseVoice?"** Measured: 52.75% / 72.44% on AMI headsets / distant microphone,
  close to GigaAM and far behind Parakeet; it targets Chinese, Japanese, Korean and Cantonese
  first.
- **"Why this LLM?"** On the same stage and clips it beat Qwen2.5 1.5B, Qwen3 0.6B and
  Qwen3.5 0.8B overall (sentence-end F1 0.456 vs 0.188, 0.108-0.204 and 0.007-0.039) and is
  the fastest; bigger models were better only with free generation, which needs a runtime the
  app does not ship.
- **"Could a bigger model help?"** GigaAM Multilingual Large (600M) lowered host Russian
  validation WER from 4.30% to 3.20% and English too, at about three times the compute. It is
  measured but not shipped.
- **"Did you fine-tune?"** No weights were trained. No usable English fine-tune of GigaAM
  exists publicly (searched 7 November 2026).
- **"What leaves the phone?"** Nothing. Recognition, speakers and text processing are local.
