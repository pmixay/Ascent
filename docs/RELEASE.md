# CI identity and release handoff

The checked APKs are **internal candidates**, not an accepted competition release.
Current integration and exact-run evidence are tracked in
[PR #5](https://github.com/pmixay/Ascent/pull/5); historical identities and cross-role
blockers live in [Timofey's remaining-work register](roles/TIMOFEY_REMAINING_STATUS.md).
No local Android build, SDK preparation or test command is needed for this process.

## Identify each CI-produced APK

The existing push-only **Ascent Android CI** keeps both required jobs, optimization,
payload/native checks, the 25 compact runtime cases and four offline runtime cases (bootstrap, Parakeet
creation and two synthetic ViewModel lifecycle/import/export checks).
Only `ascent.apk` and `ascent-offline.apk` remain downloadable artifacts. Tests,
corpus, standalone models, signing material and diagnostic reports are not uploaded.

Phone `versionCode` is the existing Android workflow's positive run number;
`versionName` is `0.1.<run-number>-<8-character-source-commit>` with
`-offline-baseline` on the embedded variant. Rerunning the same run retains its
version number; a new push receives a new number. The compiled full source commit
and embedded bundle digest remain available to the content-free storage probe.
This identifies builds; it does not promise identical bytes under ephemeral keys.

After `check_apk.py` succeeds, `record_apk_identity.py` uses CI's already installed
`aapt2` and `apksigner` to verify the actual package/version/ABI/non-debuggable flag
and APK signature. It prints one `ASCENT_APK_IDENTITY` JSON line plus a short step
summary: raw APK size/hash, certificate fingerprint, signing mode, commit/run/
attempt and (offline only) bundle/file hashes and export-validation producer.
The parser accepts numbered, SDK-range and Build Tools 37 scheme-prefixed signer
labels ([AOSP format change](https://android.googlesource.com/platform/tools/apksig/+/d177d78e4649d08499d90e6cbf9ca47d77c9b865)).
It requires one verified signer and the same certificate across SDK ranges; a
rotated/different certificate does not qualify. Signature verification is independent of ZIP hashing. No signer distinguished
name, credential, audio, source URI or transcript is emitted.

After the exact run finishes, from `prototypes/phone-transcriber`:

```sh
python3 scripts/collect_ci_evidence.py --repo pmixay/Ascent \
  --sha FULL_40_CHARACTER_SOURCE_COMMIT --output /path/to/new-evidence.json
```

Supply a token with read access to Actions logs through `GH_TOKEN` or
`GITHUB_TOKEN`, never a command argument. The collector performs read-only GitHub
requests, not builds/tests or APK/model downloads. It checks the newest exact-SHA
push run, both successful jobs from that run attempt, structured identities,
actual required instrumentation counts, artifact metadata/expiry and matching
x86_64 storage records. A missing/failed/skipped/stale/expired result fails closed.
Signed log downloads never receive the GitHub Authorization header. Existing
output files are not overwritten. Legacy runs without structured identity need
manual log inspection; they must not be retroactively labeled as using new checks.

Keep Actions ZIP digest/bytes, raw APK digest/bytes, signer certificate fingerprint,
embedded bundle digest and acoustic manifest digest separate. Store only the
content-free evidence, never full logs or user input, in the handoff register.

## Protected signing: owner action still required

No release key was present at the inspected repository. No new key was generated,
no credentials were changed, and an arbitrary generated key is not a team-owned
stable identity. A key custodian/repository administrator must:

1. Select the team's signing key, retain an encrypted offline recovery copy and
   independently obtain its certificate SHA-256. Do not send the key in chat or
   commit it. Changing from old CI debug certificates requires a deliberate fresh
   test install; uninstalling removes history and must never happen silently.
2. Protect the intended release branch/ref with reviewed workflow changes and
   required checks. Configure GitHub environment **`android-signing`** with deployment
   branch/tag rules restricted to the intended protected release refs (and required
   reviewers if appropriate). Do not rely on a workflow `if` as the secret-access
   security boundary: unprotected branch authors can edit workflow code.
   Feature CI uses the empty **`android-baseline`** environment, with no signing secrets.
3. Configure four **`android-signing` environment secrets**, not broadly accessible
   repository secrets: `ASCENT_SIGNING_KEYSTORE_BASE64`,
   `ASCENT_SIGNING_STORE_PASSWORD`, `ASCENT_SIGNING_KEY_ALIAS`,
   `ASCENT_SIGNING_KEY_PASSWORD`. Set public repository variable
   `ASCENT_SIGNING_CERT_SHA256` to the independently verified certificate digest.
4. Set repository variable `ASCENT_REQUIRE_STABLE_SIGNING=true` on that protected
   release path. Push normally; no manual workflow trigger is added.

`ci_signing.py` requires the complete configuration or none. Partial/invalid
configuration, a missing required key or an unexpected actual APK certificate
fails the job, never falling back to debug signing. The key/properties are written
only under a mode-0700 runner-temporary directory with mode-0600 files. Only paths
and the public fingerprint enter `GITHUB_ENV`. Passwords are not Gradle arguments,
source, console output or APK fields. An `always()` cleanup removes only the two
owned files/directory; hosted-runner destruction is the fallback after hard abort.
This is ordinary protected CI signing, not a hardware-backed signing service.

Without configured credentials, CI keeps optimized, non-debuggable, debug-signed
internal artifacts and labels them `unprotected-debug`. A green run in that mode
does **not** close stable-signing or final-release acceptance. The positive protected
key path needs its own real CI execution after the owner supplies the key; synthetic
configuration tests are not actual protected-key signing evidence.

## Durable publication and final promotion

The inspected offline APK at `42d4de9` is **2,167,276,006 bytes**, exceeding GitHub
Releases' [per-file limit of under 2 GiB](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases#storage-and-bandwidth-quotas)
by 19,792,358 bytes relative to 2 GiB. Its Actions ZIP is a different size.
Do not promise a Releases APK URL, split the required APK, drop/compress the
user-required uncompressed model set, weaken packaging checks or extend the
restricted model-server SSH publisher to accept APKs as a workaround.

Petya/server operator must supply an approved durable HTTPS location capable of
serving the complete APK. The current Caddy configuration serves only `/models/`
and `/health`; the restricted CI SSH key permits only validated model publication,
not arbitrary uploads. Timofey's available GitHub push permission is not server
publication or branch-protection access. A newly authorized hosting route must be
reviewed separately; no deployment/server change was made in this work.

Once Pasha accepts the application, Andrey approves the model/runtime and Petya
provides exact-build offline ARM evidence, freeze the source and configuration,
inspect all applicable CI checks for that exact revision, and hand off the actual
APK hash/signature/version/bundle plus measured device table. Publish those exact
bytes without rebuilding/re-signing; download them from the durable URL and rehash.
Link the source commit, checked runs, device results and video to the same identity.
Retest affected paths after every fix. Never publish an internal candidate as a
compliant release merely because the deadline or Actions expiry is approaching.
