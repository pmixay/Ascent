# Speaker separation and voice learning

## Findings

The previous implementation sampled every 750 ms using a 1.5-second context,
without respecting speech pauses. A context could contain silence or two people
at a turn boundary. Every context updated an online centroid immediately, so a
mixed voice could alter subsequent assignments. There was no confidence margin
between the best and second-best speaker.

The [3D-Speaker diarization recipe](https://github.com/modelscope/3D-Speaker/blob/main/egs/3dspeaker/speaker-diarization/README.md)
separates activity detection, segmentation, embedding extraction, and clustering;
it also treats overlap detection as a separate component. Its
[clustering implementation](https://github.com/modelscope/3D-Speaker/blob/main/speakerlab/process/cluster.py)
includes average-linkage agglomerative clustering as well as spectral clustering.
These are architectural references, not evidence for thresholds calibrated on
Ascent's Russian phone recordings.

## Implemented changes

- Build speech regions from recognized word intervals, splitting gaps above
  300 ms and applying 80 ms of bounded context at each edge. Extract contexts up
  to 1.8 seconds long, with 900 ms steps inside longer regions.
- Reject near-silent or heavily clipped input. An adaptive energy gate estimates
  usable speech support inside each ASR-supported region. This is a quality
  heuristic, not a neural speech detector.
- Exclude contexts whose preceding and following embeddings disagree from
  cluster training. They may still receive an attribution if the evidence is
  sufficient.
- Perform deterministic average-linkage clustering with at most 256 anchors
  spread across the session. This bounds the pairwise score matrix and avoids
  training only on the beginning of a long conversation.
- Reject short, ambiguous bridge clusters between two distinct voices. Use a
  minimum cosine score and a runner-up margin during final assignment. Exceeding
  the speaker budget produces an unknown label instead of a forced merge.
- Attribute each word using all covering contexts with distance, similarity,
  and quality weighting. Do not borrow a nearby speaker across an uncovered
  pause. Keep ambiguous votes unknown.
- Export only stable, high-confidence voice signatures for the separate local
  voice-memory component. Count the union of active frame intervals, so
  overlapping windows cannot inflate the amount of enrollment evidence.

The existing ERes2NetV2 embedding model remains in use. Its
[upstream model documentation](https://github.com/modelscope/3D-Speaker/blob/main/egs/voxceleb/sv-eres2netv2/README.md)
describes its 192-dimensional embeddings and short-duration verification design.
Verification benchmark results do not establish diarization performance on
Russian phone conversations.

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

The initial cluster cosine threshold is 0.72 and assignment margin is 0.08.
Signature windows require similarity of at least 0.80 and margin of at least
0.12, in addition to quality and boundary checks. These values are conservative
engineering starting points. They are not a measured confidence probability.

Current CTC word timestamps are estimates, and ASR errors can affect the regions
used for embeddings. Short replies, similar voices, background speech, distant
microphones, and rapid turn changes can remain unknown. This implementation does
not separate simultaneously speaking people or provide an overlap detector.

Synthetic regression tests cover returning voices, extraction order, mixed
boundary windows, invalid/poor-quality input, bounded anchors, unique speech
support, pause boundaries, and ambiguous word votes. GitHub CI executes those
checks. They do not measure real-world diarization quality.

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
