# Independent offline judge rehearsal

This is Petya's repeatable H11 procedure. Record every result in [TEST_RESULTS.md](TEST_RESULTS.md) with the exact APK commit/hash, model manifest/hash, device, Android version and input reference. Do not convert an unrun step into a pass. Keep raw/private recordings and transcripts outside public artifacts.

## Prepare

1. Select a CI-built ARM64 verification APK whose full workflow result has been inspected. Record its SHA-256 and model manifest/hash. Use two devices if available: one modest-memory Android 12+ ARM64 phone and a second ARM64 Android version. Record free storage and RAM.
2. Freeze three authorized, unfamiliar recordings and their source hashes: A = Russian call-like speech, B = Russian meeting-like speech with different speakers, C = English or mixed-language speech. Prefer original continuous media and keep this set out of development/tuning. Prepare word, speaker and time references independently. Include a separate genuine-silence input.
3. Remove prior installation and app data. Disable Wi-Fi and mobile data, then enable airplane mode **before the first launch**. Confirm there is no preinstalled model directory. Install the exact APK. Observe model readiness without enabling networking.

## Three-file sequence

Perform the following on A, B and C in order, using the same fresh installation. Do not grant microphone permission.

1. Tap the primary import action, select the source in the Android picker, and record the displayed filename, duration/format if available, and exact processing stages. Preserve the original source hash before and after import.
2. Wait for real local processing to finish. Record wall time, media duration, model load time where available, errors and displayed status. Verify no wrong-file result or speaker-name leak from an earlier input.
3. Inspect every participant turn and its source-relative time. Check a known leading silence, a pause and the last turn. Note unknown/inferred attribution honestly.
4. Export JSON to a user-selected destination. Copy the exported file to the development machine and run `python3 tools/qa/validate_transcript.py PATH --duration-ms DURATION`. Compare words, speakers and times to the frozen reference. Export a second time and check stable IDs/turn order.
5. Process the next file without clearing the app. After C, return to A's saved text and confirm it is still the correct conversation.

For the silence input, require a visible empty-result message and export `{"transcript":[]}`. A failed export must leave the in-app transcript intact. A canceled picker must leave the previous view intact and display no processing error.

## Negative and lifecycle cases

Run each applicable case independently; record `pass`, `fail`, `blocked` or `not run`, plus content-free error evidence and exact reproduction steps.

| ID | Case | Expected result | Status |
| --- | --- | --- | --- |
| N01 | Deny microphone permission, then import | Import remains available and processes the selected source. | not run |
| N02 | Cancel source picker | No processing starts; prior result remains. | not run |
| N03 | Revoked/unreadable URI, unknown size, Unicode filename | Clear error or valid processing; no crash/source modification. | not run |
| N04 | Empty, truncated or malformed audio; unsupported codec | Clear rejection, no fabricated transcript, retry possible. | not run |
| N05 | 16 kHz mono; 44.1/48 kHz stereo; MP3/AAC/M4A where declared supported | Correct decoding/time origin or documented unsupported message. | not run |
| N06 | Silence, leading/trailing silence, seam word, repeated word, short final tail | No invented words, lost seams or timestamp reset; silence exports empty array. | not run |
| N07 | One, two similar and three speakers; short replies; overlap; call/meeting/noise | Record speaker/word/timing errors by condition. | not run |
| N08 | 1, 5, 15 and 30 minute inputs; over-limit input | Stable completion or clear declared limit; no crash. | not run |
| N09 | Rotate, background/return, screen off at each stage | Coherent state and result according to documented recovery contract. | not run |
| N10 | Cancel during native work, then import another file | One active job; no late result overwrites the new job. | not run |
| N11 | Kill/reopen during install and processing | Recover or clearly report interrupted work, preserve valid history and source. | not run |
| N12 | Ten repeated jobs | No wrong-file output, unbounded memory growth or resource leak. | not run |
| N13 | Insufficient storage, corrupted model/history, failed export provider | Clear failure/recovery; original source and valid prior result retained. | not run |
| N14 | Large font, TalkBack, long text, touch targets | Readable controls/turns and accessible labels/focus. | not run |

For a reproducible failure, record the case ID, build/model hashes, device, source hash, exact steps, expected versus observed behavior, content-free error text, owner, fix commit and retest build. If a held-out result is used to tune the model, retire that set from final validation and reserve a fresh set.

For N11 during an imported-file job, force-stop the process after source reading
has begun, reopen and require an interrupted-file notice. Previously committed
history must remain accessible; reselect the original file to start again. Reopen
once more and check that the notice does not repeat. Repeat after a successful
job and after cancellation: neither should report an interrupted import. This
procedure is prepared; it is not a recorded device result.

## Watch companion matrix

Use a CI-built watch APK on Galaxy Watch7 SM-L305F (Wear OS 6.0) and its matching phone APK on Samsung Android 17. Record **both** APK hashes and installed model revision. Pair devices through Bluetooth and enable the phone receiver from a visible activity. Never substitute a phone microphone recording for a watch-capture pass.

| ID | Case | Expected result | Status |
| --- | --- | --- | --- |
| W01 | Fresh pair, watch start with microphone/Bluetooth permissions | Watch connects directly to bonded phone; microphone starts only after phone accepts; visible notification and source status. | not run |
| W02 | Denied/revoked microphone or Bluetooth permission | No hidden capture; clear watch error; retry after grant. | not run |
| W03 | Watch screen off while listening | Captured frames continue only within supported foreground-service policy; measure coverage and battery. | not run |
| W04 | Pause, resume and stop from watch and phone | Phone control shows pending until the watch sends matching state acknowledgement; stop releases mic. | not run |
| W05 | Bluetooth loss, phone unavailable, delayed ACK or missing Ready | Connect/Ready expires within 10 seconds; audio outside 2.5-second limit is discarded; watch and phone show gap/disconnection, with no recovered backlog. | not run |
| W06 | Phone model unready, low resources or processing overload | Watch does not report false normal listening; accepted/rejected frames and missing coverage are visible. | not run |
| W07 | Known spoken phrase on watch | Phone locally processes bounded frames, stores text without app-owned watch audio files and returns latest text to watch. | not run |
| W08 | 30-minute session, restart and another pair | No stale key/nonce reuse or old transcript, bounded memory, measured latency/battery/thermal behavior. | not run |
| W09 | Long continuous speech, rapid pause/resume and dropped frames | Adjacent captured frames have sample-continuous offsets; resumed capture preserves its wall-time gap and neither device invents recovered audio. | not run |

