# Local text model feasibility (plan H07 / P2)

The brief requires a local LLM. This is the speech lead's first feasibility pass:
a shortlist, a host measurement on public data, and the edit contract that the
integration must enforce. **No model is integrated, packaged or run on Android
yet.** Nothing here satisfies the requirement until a CI-built ARM APK runs the
selected model on the device.

## Job and contract

The model's only job is to add punctuation and capitals to recognized words, as
the plan proposes. It never adds, removes, reorders or respells words, never
assigns speakers and never changes times.

1. Input: the recognized words of one stored phrase (bounded; at most a few dozen
   words) and a fixed Russian instruction.
2. Generation is constrained by a per-phrase GBNF grammar that admits only the
   input words in order, each with an optional capital first letter and an
   optional mark from `, . ? !`. `tools/text_model_study.py edit_grammar` builds
   it; llama.cpp applies the same grammar format on Android.
3. The output is validated anyway: after the shared normalization (lowercase,
   `ё` to `е`, punctuation removed) its words must equal the input words. On any
   mismatch, runtime error or time limit, the phrase keeps its raw text and a
   content-free fallback counter increases.
4. Accepted capitals and marks are projected onto the original word objects, so
   word identity, order, speaker labels and timestamps cannot change. Raw ASR text
   stays stored beside the presentation text.
5. The stage runs after acoustic processing has released its sessions, with a
   capped context (1,024 tokens in the study), capped output (6 tokens per word
   plus 32) and greedy decoding.

## Shortlist

All candidates are official Qwen GGUF files with Apache-2.0 licences, which allow
redistribution inside the APK with the licence and notice. Gemma 3 1B was left out
because its official GGUF is gated behind manual approval and its own terms;
Llama 3.2 1B was left out because of its separate community licence and
attribution terms. Neither was measured.

| Candidate | Repository revision | File | Bytes | SHA-256 |
| --- | --- | --- | --- | --- |
| Qwen2.5 0.5B Instruct Q4_K_M | `Qwen/Qwen2.5-0.5B-Instruct-GGUF` `9217f5db79a29953eb74d5343926648285ec7e67` | `qwen2.5-0.5b-instruct-q4_k_m.gguf` | 491,400,032 | `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` |
| Qwen3 0.6B Q8_0 | `Qwen/Qwen3-0.6B-GGUF` `23749fefcc72300e3a2ad315e1317431b06b590a` | `Qwen3-0.6B-Q8_0.gguf` | 639,446,688 | `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031` |
| Qwen2.5 1.5B Instruct Q4_K_M | `Qwen/Qwen2.5-1.5B-Instruct-GGUF` `91cad51170dc346986eccefdc2dd33a9da36ead9` | `qwen2.5-1.5b-instruct-q4_k_m.gguf` | 1,117,320,736 | `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` |
| Qwen3 1.7B Q8_0 | `Qwen/Qwen3-1.7B-GGUF` `90862c4b9d2787eaed51d12237eafdfe7c5f6077` | `Qwen3-1.7B-Q8_0.gguf` | 1,834,426,016 | `061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a` |

Every downloaded file matched its repository's LFS SHA-256.

## Host measurement

Input: recognized words of the first 120 development clips (`masha_dima_part1`,
reference-export run) whose words equal the reference words, so the Dialogs
reference punctuation can be scored word by word: 2,115 words, 1,067.59 s of
speech. Runtime: `llama-cpp-python` 0.3.36 prebuilt CPU wheel, 2 threads, greedy
decoding, on the same 4-core x86 container as the ASR baseline. Raw ASR text has
no punctuation, so its F1 is 0 for every mark. Fallback phrases count with no
punctuation and lowercase words. "Accepted" means the output passed validation.

| Candidate | Grammar | Accepted | Comma F1 | Sentence-end F1 | Question F1 | Capitals correct | Added RTF (host) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Qwen2.5 0.5B Q4_K_M | no | 72 / 120 | 0.162 | 0.177 | 0.095 | 86.5% | 0.217 |
| Qwen3 0.6B Q8_0 | no | 42 / 120 | 0.246 | 0.285 | 0.140 | 87.3% | 0.481 |
| Qwen2.5 1.5B Q4_K_M | no | 92 / 120 | 0.594 | 0.655 | 0.491 | 91.4% | 0.377 |
| Qwen3 1.7B Q8_0 | no | 111 / 120 | 0.575 | 0.561 | 0.140 | 93.0% | 0.784 |
| Qwen2.5 0.5B Q4_K_M | yes | 120 / 120 | 0.339 | 0.415 | 0.298 | 89.1% | 0.309 |
| **Qwen2.5 1.5B Q4_K_M** | **yes** | **120 / 120** | **0.649** | **0.792** | **0.644** | **94.1%** | **0.469** |

Without the grammar, the small models often changed words (for example a
diminutive replaced by its base form, or a short phrase dropped), and validation
had to reject those outputs. The grammar removed every such rejection.
Qwen3 ran with its `/no_think` switch and was not tried with the grammar.

"Added RTF" is generation time divided by speech duration on this x86 container;
model loading took about 1 s and is excluded. Phone CPUs are usually slower, so
the on-device cost is unknown and could be several times higher.

## Recommendation and open work

- **Proposed candidate:** Qwen2.5 1.5B Instruct Q4_K_M with the grammar
  contract. It was the only configuration that combined full lexical preservation
  with the best punctuation scores. Qwen2.5 0.5B with the grammar is the fallback
  if the device budget cannot absorb the 1.5B model (1.1 GB file).
- **Not yet established:** ARM speed and memory, behaviour of the Android llama.cpp
  build, packaging into the verification APK, and quality on held-out or
  conversational text. These scores come from 120 development clips of acted
  dialogue and one annotator's punctuation style.
- **Speed risk:** on the host, the 1.5B stage alone costs more than six times the
  ASR time (0.469 against 0.069). Before freezing, Timofey's ARM measurements
  should decide between the 1.5B and 0.5B models, a cap on processed phrases, or
  cheaper scoring of marks instead of generation.
- **Handoffs:** Andrey integrates the runtime and the grammar/validation/fallback
  contract above; Timofey packages the pinned file and its licence and measures
  ARM cost. Report total RTF with this stage included.
- **Reproduce:** `python3 tools/text_model_study.py RUN_DIR --model FILE.gguf
  --threads 2 --limit 120 --grammar` with `llama-cpp-python` installed from a
  prebuilt wheel; `RUN_DIR` is a development output of `asr_study.py infer`.
