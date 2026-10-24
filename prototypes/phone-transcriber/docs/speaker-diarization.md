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
- Perform deterministic average-linkage clustering with at most 256 anchors
  spread across the session. This bounds the pairwise score matrix and avoids
  training only on the beginning of a long conversation.
- Consolidate compatible aggregate centroids after average-linkage discovery.
  Suppress an isolated degraded fragment only when the same established voice
  surrounds it and acoustic similarity plus the runner-up margin support that
  voice. Clear short replies remain eligible to represent another speaker.
  Reject a mixed bridge between two different voices only with immediate
  temporal evidence; resembling two people elsewhere in the session does not
  by itself disqualify a third speaker.
- Separate assigning a short reply from teaching a voice: 320 ms of usable
  speech can receive a label with sufficient acoustic evidence; establishing a
  voice still needs at least 800 ms in the extraction pass and the stronger
  cluster quality gate. Exceeding the speaker budget remains unknown.
- Use the strongest covering context per speaker for each word, weighted by
  temporal proximity, similarity and quality. Overlapping windows no longer
  multiply one speaker's vote. Repair a short unknown run only when clear
  labels on both sides agree and each ambiguous word already favors that
  voice. Exact ties, conflicting neighbors and missing coverage remain unknown.
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

The initial cluster cosine threshold remains 0.72 and assignment margin 0.08;
centroid consolidation requires 0.80. A low-occupancy short reply requires
similarity 0.80 and margin 0.12. A bounded, two-sided recovery can use similarity
0.62 with a margin of 0.08 when an established voice is independently supported
on both sides. Recovered contexts cannot recursively recover more contexts or
teach remembered profiles. Word-run recovery is limited to 800 ms with gaps no
larger than 500 ms and clear same-voice neighbors.

Signature windows still require similarity of at least 0.80 and margin of at
least 0.12, in addition to quality and boundary checks. These values are
engineering starting points, not measured confidence probabilities or a
calibration on held-out Russian phone conversations.

Current CTC word timestamps are estimates, and ASR errors can affect the regions
used for embeddings. Short replies, similar voices, background speech, distant
microphones, and rapid turn changes can remain unknown. This implementation does
not separate simultaneously speaking people or provide an overlap detector.

Synthetic regression tests cover returning voices, noisy 192-dimensional voice
variation, extraction order, mixed boundaries, clear short replies, weak
fragments, invalid input, bounded anchors, unique speech support, sparse CTC
emissions, pause boundaries, and ambiguous word votes. GitHub CI executes them.

The [public Russian fixtures](../tools/fixtures/speakers/README.md) add six real
utterances from three credited LibriVox readers. CI verifies their hashes and
the exact ERes2NetV2 model, extracts embeddings from clean speech, reduced gain,
and deterministic 20 dB white-noise variants, and runs the app's activity and
clustering code on those samples. The generated model inputs are cached test
resources; neither audio nor embeddings enter the APK or downloadable artifact.
This checks a fixed set of real-speaker regressions. It is development data,
not a held-out benchmark, and does not measure natural-dialogue DER or ASR word
timing errors. A passing build alone does not establish improvement on the
user's recordings.

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
