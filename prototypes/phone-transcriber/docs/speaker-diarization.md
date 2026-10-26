# Speaker separation and voice learning

## Findings

The latest user report describes one person being split into several speakers
and frequent unidentified turns. Review found several mechanisms that could
produce those symptoms in the previous batch pipeline:

- Sparse CTC character emissions were treated as complete acoustic word
  intervals. Splitting gaps above 300 ms, padding by only 80 ms, and rejecting
  contexts below 640 ms could remove the useful context around a short phrase.
- A window lost its eligibility to establish a voice whenever its left and
  right neighbors had cosine similarity below 0.72. That also rejected natural
  variation within one voice, without checking whether the middle window
  actually mixed two different voices.
- Average-linkage fragments were never compared again using their cleaner
  aggregate centroids. Word votes summed overlapping windows, allowing window
  count to outweigh the closest acoustic evidence.

These are source-level findings, not measured frequencies in the user's audio.
The follow-up report of excessive speaker labels exposed another gap: a short
component can survive discovery by matching itself, even when it resembles a
less common acoustic mode of an established voice. The activity `quality` value
measures energy occupancy and clipping, not certainty about speaker identity;
a clear-sounding phonetic change can score highly. A single aggregate centroid
can also obscure the different sounds made by the same person. The new regression
work compares short, pause-separated phrases with a frozen copy of the grouping
policy from `3fee6da`, using the same real model outputs for both policies.

The PCM reader supplies normalized mono 16 kHz samples, and the
[pinned sherpa extractor](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.0/sherpa-onnx/csrc/speaker-embedding-extractor-general-impl.h)
uses model metadata for feature configuration and mean normalization. This
review found no sample-rate or PCM-scale mismatch. Voice memory runs after
session clustering and changes display names; it does not generate unknown
speaker labels.

The [3D-Speaker diarization recipe](https://github.com/modelscope/3D-Speaker/blob/main/egs/3dspeaker/speaker-diarization/README.md)
separates activity detection, segmentation, embedding extraction, and clustering;
it also treats overlap detection as a separate component. Its
[clustering implementation](https://github.com/modelscope/3D-Speaker/blob/main/speakerlab/process/cluster.py)
includes average-linkage agglomerative clustering, spectral clustering, and a
separate centroid-merging pass. Its default AHC cutoff differs from Ascent's;
copying a threshold from a different model and corpus would not establish a
calibrated operating point for Russian phone recordings.

## Implemented changes

- Join CTC emissions across gaps up to 500 ms and add 240 ms of acoustic context
  at each edge. Expand short regions toward 1.2 seconds, bounded by 600 ms from
  the emissions, the recording edges, and the midpoint to a neighboring region.
  Retain contexts from 320 ms; longer regions use 1.8-second windows with 900 ms
  steps and an end-anchored final window.
- Reject near-silent or heavily clipped input. An adaptive energy gate estimates
  usable speech support inside each ASR-supported region. This is a quality
  heuristic, not a neural speech detector.
- Keep natural voice variation eligible for clustering. A middle window is
  excluded from cluster training only when reliable, overlapping neighbors
  differ strongly and it matches both similarly, providing evidence of a
  mixed boundary. It can still receive an attribution.
- Perform deterministic average-linkage clustering with at most 256 anchors.
  When candidates exceed that budget, reserve up to 24 acoustically different
  examples using farthest-first coverage and the existing discovery cutoff;
  fill the remaining budget across the session by quality within time buckets.
  This prevents periodic equal-quality turns from repeatedly selecting only
  the same voice. Final anchors retain chronological order.
- Consolidate compatible aggregate centroids after average-linkage discovery.
  If both groups contain independent, nonoverlapping seed pairs, their center
  agreement must also reach each group's mean internal pair agreement. This
  prevents additional samples from denoising two distinct centroids into a false
  merge. Pair sums/counts are accumulated without repeatedly recomputing pairs;
  groups without independent support retain the existing consolidation policy.
  Suppress a bounded degraded fragment only when the same established voice
  surrounds it and acoustic similarity plus the runner-up margin support that
  voice. A single acoustic disturbance can affect several overlapping windows;
  recovery considers that contiguous run together, up to three seconds with
  gaps no larger than 500 ms. Every recovered window must satisfy the quality,
  similarity and margin checks. Clear short replies and recurring fragments
  remain eligible to represent another speaker.
  Reject a mixed bridge between two different voices only with immediate
  temporal evidence; resembling two people elsewhere in the session does not
  by itself disqualify a third speaker.
- Separate assigning a short reply from teaching a voice: 320 ms of usable
  speech can receive a label with sufficient acoustic evidence; establishing a
  voice still needs at least 800 ms in the extraction pass and the stronger
  cluster quality gate. Exceeding the speaker budget remains unknown in the
  strict internal evidence, before final participant completion.
- Reconcile a smaller extra group when every anchor strongly matches
  two nonoverlapping original examples of the same established voice. This
  compares different acoustic modes within that voice instead of relying only
  on its average centroid. Its parent needs at least three anchors,
  three seconds of speech and twice the fragment's unique speech support. Both independent
  matches must reach the centroid cutoff (normally 0.70), with a margin of 0.08
  over other groups' centroids or independent examples. These are fixed original
  examples: recovered groups cannot corroborate another recovery. Recurrence
  alone does not establish a new person; the same phonetic variation can recur.
  Duplicate overlapping examples and ambiguous parent choices do not qualify.
  Apply the recovered label after temporal recovery, and exclude
  the fragment from centroids and voice enrollment.
- Before accepting a small group as another person, check its original members
  against established voices with the ordinary assignment cutoff (0.60) and a
  stronger competing-voice margin (0.12). This path requires less than three
  seconds of unique source support, two nonoverlapping original parent seeds,
  and at least four seconds of independently assigned parent speech, also at
  least twice the source support. Every source member must support the same
  parent. All support is measured before recovery, and proposed parents cannot
  themselves be recovered groups. This prevents a small component from becoming
  a person solely through matching its own seeds, while retaining the stronger
  threshold for consolidating two established voices. Recovered observations
  cannot train centroids or voice templates.
  The comparison can also use a frozen average of speech that already passed
  strict attribution before recovery. This supplies additional phonetic evidence
  when only a few windows were eligible to establish a voice. Both candidates and
  competitors use the stronger of this temporary template and the seed centroid;
  the fragment cannot contribute to its proposed parent's template. These
  temporary comparisons do not replace discovery centroids or stored profiles.
  Freeze each small group's preference among independently established voices
  before considering rival fragments. If every original observation in two
  small groups independently supports the same parent under the unchanged
  cutoff and margin, those groups do not act as evidence for separate people
  merely by competing with one another. Unsupported groups and groups preferring
  another parent remain competitors. Recovered labels never enter this decision,
  and the existing no-chain and voice-enrollment exclusions remain in force.
  This policy has an open-set tradeoff: a genuine new speaker with only brief,
  acoustically similar evidence can be assigned to an established voice. It
  cannot establish identity from such evidence; measured comparisons and wider
  natural-conversation validation remain necessary.
- Use the strongest covering context per speaker for each word, weighted by
  temporal proximity, similarity and quality. Overlapping windows no longer
  multiply one speaker's vote. Repair a short unknown run only when clear
  labels on both sides agree and each ambiguous word already favors that
  voice. Exact ties, conflicting neighbors and missing coverage remain unknown
  in this strict evidence pass.
- Complete every recognized word with a participant label in a separate final
  pass. Preserve strict assignments; fill unresolved words from frozen local
  acoustic evidence, then nearest existing temporal anchors, with one stable
  participant when no evidence exists. Short conversations without a strict seed
  use a bounded short-evidence discovery policy. Every fallback stays marked as
  inferred and cannot corroborate another fallback or teach a remembered voice.
  Stored legacy unknown/blank labels are completed from original attributed
  phrases without changing text, times or source capture time. JSON export
  retains inferred provenance. Removing the unidentified display state does not
  establish correct speaker recognition.
- Export only stable, high-confidence voice signatures for the separate local
  voice-memory component. Count the union of active frame intervals, so
  overlapping windows cannot inflate the amount of enrollment evidence.

The existing ERes2NetV2 embedding model remains in use. Its
[upstream model documentation](https://github.com/modelscope/3D-Speaker/blob/main/egs/voxceleb/sv-eres2netv2/README.md)
describes 192-dimensional embeddings. The downloaded model's SHA-256 remains
`bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239`.
Verification results from an upstream training recipe do not establish
diarization performance for this artifact on Russian phone conversations.

## Remembering confirmed voices

The encoder weights remain fixed. Learning means saving small, normalized voice
templates locally, not retraining GigaAM or ERes2NetV2 and not keeping enrollment
recordings. The official [sherpa speaker-identification example](https://github.com/k2-fsa/sherpa-onnx/blob/master/python-api-examples/speaker-identification.py)
supports enrollment from multiple embeddings and returns unknown below a
similarity threshold. [Research published in 2024](https://ieeexplore.ieee.org/document/10599875)
studies template updates under changing voice conditions; its thresholds are not
calibrated for this application.

A current conversation's signature can enroll only after the user names that
speaker and only with at least six seconds of unique usable speech, three trusted
windows and mean cohesion of 0.80. Unconfirmed signatures remain in memory and
are unavailable after process restart. Renaming an older conversation still
changes its displayed name but does not reconstruct a voice from text.

Later conversations can reuse a confirmed name only above cosine similarity
0.78 with a runner-up margin of 0.08. Competing local speakers cannot both claim
one remembered profile. Automatic matches never update their own templates;
explicit naming confirms a new contribution. A different entered name corrects
a suggestion without relabeling or contaminating the previous profile. Equal
name strings alone never merge profiles, and text mentioning a name is not
identity evidence.

Each profile retains at most eight source-linked samples, with equal weight per
confirmed conversation and a total cap of 128 profiles. Capture time and source
conversation ID accompany every contribution. Removing/expiring its source
removes that contribution and recomputes the remaining template; renaming does
not extend expiry. Profiles are encrypted with AES-256-GCM under an Android
Keystore key, consistent with [Android cryptography guidance](https://developer.android.com/privacy-and-security/cryptography).
No embeddings or names are sent to the model server. This is fallible voice
matching, not authentication or proof of a person's real identity.

## Calibration and limits

Cluster discovery now uses cosine 0.55, while ordinary assignment requires
0.60 with a runner-up margin of 0.08. Centroid consolidation requires 0.70.
These decisions have separate thresholds because individual short-window
embeddings and aggregate voice centroids have different variability. A
low-occupancy short reply still requires similarity 0.80 and margin 0.12.
A bounded, two-sided recovery can use similarity 0.48 with a margin of 0.08
when an established voice is independently supported on both sides. An
isolated degraded run requires similarity 0.50 and margin 0.12 for every window,
with quality below 0.75, before being removed as a separate voice. Both unknown
and degraded-run recovery require neighboring evidence within 500 ms and a run
no longer than three seconds. Recovered contexts cannot recursively recover
more contexts or teach remembered profiles. Word-run recovery is limited to 800 ms with gaps no
larger than 500 ms and clear same-voice neighbors.

Signature windows still require similarity of at least 0.80 and margin of at
least 0.12, in addition to quality and boundary checks. These values are
development operating points, not measured confidence probabilities or a
calibration on held-out Russian phone conversations. Lowering the discovery
cutoff is intended to reduce fragmentation but can merge similar
voices not represented by those readers; that tradeoff needs broader evaluation.

Current CTC word timestamps are estimates, and ASR errors can affect the regions
used for embeddings. Short replies, similar voices, background speech, distant
microphones, and rapid turn changes can lack reliable voice evidence. This implementation does
not separate simultaneously speaking people or provide an overlap detector.
Those cases can still lack strict evidence internally; the completed transcript
now supplies best-effort participant labels and retains inferred provenance
instead of displaying an unidentified speaker.

Synthetic regression tests cover returning voices, noisy 192-dimensional voice
variation, extraction order, mixed boundaries, clear short replies, weak
fragments, invalid input, bounded anchors, unique speech support, sparse CTC
emissions, pause boundaries, and ambiguous word votes. GitHub CI executes them.

The [public Russian fixtures](../tools/fixtures/speakers/README.md) now contain 16
real utterances from six credited LibriVox readers (110.18 seconds), including
additional chapters and reference transcripts. CI verifies their hashes and
the exact ERes2NetV2 model, extracts embeddings from clean speech, reduced gain,
deterministic 20 dB white noise and a mild synthetic channel response, and runs the app's activity and
clustering code on those samples. The generated model inputs are cached test
resources; neither audio nor embeddings enter the APK or downloadable artifact.
This checks a fixed set of real-speaker regressions. It is development data,
not a held-out benchmark, and does not measure natural-dialogue DER or ASR word
timing errors. A passing build alone does not establish improvement on the
user's recordings.

### Observed development regression

[CI run 40125534342](https://github.com/pmixay/Ascent/actions/runs/40125534342)
at commit `5c4fa7b` reproduced the reported fragmentation and failed both real
speaker checks. This revision already included the first continuity changes;
it retained the old 0.72 clustering cutoff. It is not a measurement of the
earlier shipped APK.

| Development case | Speaker labels observed | Unknown windows |
| --- | --- | --- |
| Reader 2671 alone | 5 | 26.32% |
| Reader 3056 alone | 12 | 5.88% |
| Reader 4471 alone | 2 | 7.14% |
| Alternating three readers | 12 | 16.00% |

The alternating sequence had **53.33% window assignment error**, including
unknowns, after the best one-to-one mapping of predicted labels to reference
readers. Clean cross-utterance cosine similarities had 10th/50th/90th
percentiles of **0.587/0.680/0.760 for the same reader** and
**0.198/0.281/0.371 for different readers**. This exposed the mismatch between
the old cutoff and this model's actual short-window output; idealized
orthogonal-vector tests had not exposed it.

Those historical measurements used only six source utterances, totaling 47.88 seconds, from three
readers. The 150 evaluated windows include overlapping contexts and three
variants of each utterance; they are not 150 independent examples. Cosine
percentiles weight window pairs rather than giving each reader equal weight.
The alternating sequence uses 1.2-second gaps and embeddings computed inside
each source clip, so it does not evaluate mixed boundaries or the ASR pass.
These measurements guide development and must not be presented as held-out
accuracy or diarization error rate.

### Expanded baseline and the 75% target

[Android CI 40292806122](https://github.com/pmixay/Ascent/actions/runs/40292806122)
at `c4cd4f3` exercised the expanded 16-utterance development corpus. Its 460
accepted windows had **97.17% correct assignment** after one-to-one label
matching, including unknowns as errors. The run nevertheless failed correctly:
reader 2826 acquired two labels, and the combined case found seven identities
for six readers. This is evidence of remaining fragmentation, not completion.
The other five single-reader cases retained one identity with no unknown windows.

[Development diagnostics in CI 40293770070](https://github.com/pmixay/Ascent/actions/runs/40293770070)
at `7f38ec2` compared centroid cutoffs 0.65, 0.70 and 0.75 on those development
clips only. Both 0.65 and 0.70 restored one label for reader 2826 and six labels
with 100% assignment for the combined case. The selected default is 0.70, the
highest candidate that fixed the split; discovery and assignment thresholds
remain unchanged. The split's assigned-window centroids had cosine 0.734,
whereas the other combined-case centroid pairs were at most 0.435. Separate
synthetic regressions protect similar distinct voices and clear same-voice
fragments. No evaluation-reader result was used to select this cutoff.

That older metric excludes windows rejected by the activity gate and gives
overlapping windows separate weight. The requested target is strictly above 75%,
so an additional fixed evaluation uses 12 source clips from six different
readers, totaling 98.67 seconds. Reader IDs are disjoint from development; their
absence from the upstream model's training data is unknown. The exact manifest
hash, per-reader durations and clip selection are frozen before evaluating them.

For each of clean, reduced-gain, 20 dB noise and synthetic channel conditions,
the clips form a continuous sequence with variable pauses, returning voices
and six 800 ms replies. Embedding windows can cross actual constructed speaker
boundaries. Every source sample belongs to one nonoverlapping reference interval
of at most 100 ms. Internal clip silence stays in the denominator; only known
inserted silence is excluded. Missing evidence and unknown labels count as errors.
The score uses a global one-to-one, maximum-duration mapping of anonymous labels
to readers. It reports total, per-reader, macro and short-reply accuracy, with
the short replies using that same full-session mapping. Overall and macro scores
must exceed 75% for every condition and the pooled result.

Python extraction intervals must exactly match Kotlin's context generation.
The app's activity gate, stable-seed filtering, clustering and word-assignment
code then process the continuous contexts. Reference probes replace ASR word
timings in this test: it is **clip-time speaker-backend attribution**, not
end-to-end transcription accuracy, speech-only DER, overlap separation or a
measurement on phones. DER also includes speech-detection errors and overlap;
see the [pyannote metric definition](https://pyannote.github.io/pyannote-metrics/reference.html#diarization).
The existing stricter development checks remain required. Natural-conversation
acceptance stays open until representative annotated sessions demonstrate it.

The initial fixed evaluation at `7f38ec2`, before the development-selected
centroid update, measured 94.86% pooled clip-time accuracy (94.72% per-reader
mean), with condition scores from 93.89% to 95.26%. Its separate 800 ms reply
score was only 33.33%. Long turns dominate overall duration: passing the
aggregate target does not establish reliable short replies. The 1.8-second
contexts can mix neighboring voices around rapid changes; improving that needs
dedicated development material and subsequent independent validation.

### Sparse-context development regression

An additional fixed development sequence uses 0.9-, 1.2- and 1.8-second excerpts
with synthetic pauses and sparse emission intervals. Longer voice references
come from different source utterances than the excerpts. Both clean and channel
variants are scored using all source time, including unknown attribution. The
same excerpts occur in single-reader and alternating-reader cases, so the
243,820 ms pooled denominator contains correlated examples.

[CI 40300835163](https://github.com/pmixay/Ascent/actions/runs/40300835163)
at `e7a73ff` reproduced four single readers splitting into two labels and six
alternating readers splitting into ten. Correct attribution was
212,620/243,820 ms (87.20%), unchanged from the frozen `3fee6da` policy. The first
independent-example recovery passed its focused synthetic regression but did
not improve these real-model cases. Passing that CI run therefore did not
establish that the reported fragmentation was fixed.

[CI 40303879173](https://github.com/pmixay/Ascent/actions/runs/40303879173)
at `b946ea1` reduced the alternating case from ten labels to seven for six readers.
The paired score improved from 212,620 to 235,420 correct milliseconds out of
243,820 (87.20% to 96.55%); unknown time in the alternating case fell from 5.41%
to 1.97%. Three of the four reproduced single-reader splits were corrected;
reader 4372 still split. Six additional cases remove one participant's longer
reference and retain only their brief replies: none lost attribution relative
to the frozen baseline. The separate reader-disjoint evaluation remained at
377,080/394,680 ms (95.54%). These are controlled development/evaluation
measurements, not device or natural-conversation accuracy. The latest sparse
regression now requires exactly one label per actual reader, in addition to
the paired accuracy checks, so the original splitting cannot pass as unchanged.

### Recognition boundaries and timeline

The app previously cut ASR cores exactly every 20 seconds with 500 ms of context.
It now searches the final two seconds of each nonfinal core for at least 200 ms
of low energy and places the seam in the latest qualifying interval. If none
exists it retains the 20-second seam. Each side receives 1.25 seconds of context,
keeping the maximum input at 22.5 seconds. Quiet samples are never discarded;
word ownership still uses nonoverlapping core intervals. This is an energy
heuristic, not a learned VAD. GigaAM's official [longform implementation](https://github.com/salute-developers/GigaAM/blob/main/gigaam/vad_utils.py)
also separates segmentation from recognition, but its neural VAD is not included
in this prototype.

Model CI measures reference-normalized WER/CER on the downloaded development
clips and compares the old/new chunk policies on concatenated speech. The
comparison rejects an increase in word errors on either clean or channel audio.
Host evaluation mirrors the Kotlin timing and ownership logic; it is not Android
device accuracy validation. Fixed model weights are unchanged.

Development diagnostics also exposed duplicate words when adjacent contextual
CTC decodes move the same emission across a core seam. The candidate stitcher
reconciles matching, overlapping owned boundary words only when each decode
contains exactly one matching occurrence in their shared audio. It preserves
the union of their acoustic span. Repetitions, conflicts and nonoverlapping
words remain unchanged. This changes text assembly without changing model
weights, inference windows or acoustic context support. Candidate measurements
are pending.

[Model CI 40292806058](https://github.com/pmixay/Ascent/actions/runs/40292806058)
at `c4cd4f3` passed validation and publication. On the fixed concatenated
215-word development sequence, WER fell from 9.30% to 6.51% in both clean and
channel conditions (six fewer word errors in each). The isolated-clip WER was
6.51% clean, 6.98% reduced gain, 7.91% noise and 6.98% channel. These measurements
establish the controlled ASR comparison, not natural-dialogue or phone accuracy.

The display groups consecutive phrases with the same known speaker ID into one
timeline element, including across pauses and former 20-second element limits.
It retains stored phrases for export, shows decimal elapsed seconds, and marks
gaps above 700 ms with a timer icon and duration. Final participant completion
also normalizes legacy unknown IDs before display; shared fallback labels remain
best-effort attribution rather than proof of a shared voice.
Pause durations are estimates between CTC-derived phrase times, not verified
silence; the interface says that reply and pause timings are approximate.
After listening stops, processing has one stable loading status. The separate
model panel retains its last successful catalog comparison and relative check
age through failed update attempts; an advertised newer revision remains an
available update until activation, and initial downloads retain their progress.

### Frozen two-hour dialogue evaluation

The new Android CI job uses the
[frozen Dialogs selection](../tools/fixtures/dialogs/README.md): 1,134 distinct
public audio payloads, three actors and 7,233.107 seconds of publisher metadata
duration. Every source checksum and annotation file is pinned before inference.
CI verifies at least 7,200 seconds of actual resampled source audio, excluding
inserted gaps. It runs the deployed GigaAM and ERes2NetV2 revisions without
training model weights or enrolling benchmark actors into voice memory.

Before baseline inference or score inspection, `masha_dima_part1` is frozen as
the development subset for diagnosing failures and choosing algorithm changes.
The three other selected recordings (`masha_sveta_part1`, `masha_sveta_part2`,
and `masha_dima_part2`) remain reserved until a candidate is chosen, then compare
that candidate with the frozen baseline without further tuning to their scores.
Report development and validation separately. This split separates recordings
but shares M/D across subsets; S appears only in validation. Existing six-reader
regression gates remain unchanged.

Source clips retain their numeric turn order in approximately five-minute blocks
with 100 ms inserted gaps. The source is expressive acted studio dialogue;
original continuous pauses and overlap cannot be reconstructed. Three actors
give limited identity diversity, and their absence from upstream model training
is unknown. This is a separate evaluation selection, not natural meeting audio.

ASR WER/CER score all reference and recognized text, including deletions and
empty hypotheses. Joint cpWER concatenates words per speaker and uses one-to-one
label matching, penalizing extra or missing speaker streams. Kotlin verifies
the host chunk, CTC and embedding-context mirrors before running production
clustering and completion on actual recognized words. Separate clip-time probes
report strict and completed attribution, per-actor scores, inferred coverage and
speaker-count errors. Their denominator includes source clip silence and excludes
only inserted silence; unknown/missing strict evidence remains an error.

Validated checkpoints cache inference, while the dedicated job always reruns
scoring and rejects a skipped evaluation. Public audio, transcripts, embeddings
and reports are not download artifacts.

The inspected development baseline at `e9df416` passed
[CI 40378549620](https://github.com/pmixay/Ascent/actions/runs/40378549620);
[diagnostic CI 40381522671](https://github.com/pmixay/Ascent/actions/runs/40381522671)
at `3ae8975` also passed. For the 336 `masha_dima_part1` clips in ten blocks,
WER is 160/5,572 (2.8715%), CER is 254/29,095 (0.8730%), and joint cpWER is
278/5,572 (4.9892%). Completed clip-time attribution is
2,799,686/2,866,068 ms (97.6839%); strict attribution is 2,573,284 ms on the
same denominator. Completion inferred 56 of 5,567 recognized words; the
separate reference-probe diagnostic inferred 255,893 ms of clip time.

The candidate adds bounded anchor diversity, an independent-cohesion guard for
centroid merging, and conservative duplicate-word seam reconciliation. Its
comparative measurements are pending. Validation recordings remain reserved
until a candidate is selected. These measurements cannot establish speech-only
DER, overlap separation, phone speed or natural-conversation acceptance;
those requirements remain open.

### Remaining validation

Voice-memory tests cover explicit enrollment, encrypted restart, short/invalid
evidence rejection, runner-up and conflicting-speaker ambiguity, absence of
self-training, source deletion, seven-day expiry, failed writes and discarded
sessions. Passing these tests establishes policy behavior, not a measured
false-match rate on Russian speakers.

A next candidate is the small multilingual
[Silero VAD 6.2.3 release](https://github.com/snakers4/silero-vad/releases/tag/v6.2.3),
which can supply learned speech activity rather than ASR/energy heuristics. Adding
it requires a pinned model artifact, license and checksum, Android runtime
validation, and measured comparison. A VAD alone does not solve overlapping
speech. Sherpa's [diarization interface](https://k2-fsa.github.io/sherpa/onnx/c-api/html/speaker_diarization.html)
uses a separate segmentation model together with embeddings and clustering.

Before claiming improved accuracy, compare the old and new pipelines on a
consented, speaker-annotated Russian evaluation set containing single-speaker
speech, alternating voices, brief replies, overlap, noise, and recurrence in later
conversations. Report speaker-count errors, word attribution error, diarization
error, unknown coverage, false recurring-person matches, runtime, and memory.
Keep enrollment and evaluation conversations separate. Do not calibrate and
report results on the same sessions.
