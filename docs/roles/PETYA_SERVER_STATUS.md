# Model publication repair — 7 November 2026

Petya owns this follow-up to Timofey T1/T2. Publication is restored; phone and
offline/local-LLM acceptance remain separate and unrun.

## Repair and inspected CI

- The domain resolves to this server’s assigned address. Its existing Caddy HTTPS
  configuration serves the model root; TLS verification remained enabled.
- Read the ED25519 host public key directly from this server, then pinned that
  authenticated identity in the protected `ASCENT_MODEL_HOST_KEY` repository secret.
- Restored a dedicated deployment key with `restrict` and the root-owned forced
  publisher command. Arbitrary SSH commands were rejected in an authenticated
  connection. The private key is stored outside the repository and in the protected
  `ASCENT_MODEL_DEPLOY_KEY` secret; no key material is included here.
- Reran the existing push-created [Model Export 42369233159, attempt 2](https://github.com/pmixay/Ascent/actions/runs/42369233159/attempts/2)
  at `a83fdfffbcceb731d76330717c973aefedc4e51d`. Job `128424780303` and the
  whole workflow concluded **success**, including export, parity/speech/speaker
  validation and strict-SSH publication. No workflow trigger or check was bypassed.
- Petya’s branch now includes Timofey’s same pinned Hugging Face checkpoint
  recovery and exporter, so a later allowed push after integration can repeat the
  repaired path. The branch allowlist and push-only trigger remain unchanged; that
  later revision’s Model Export run must still be inspected.

## Independent HTTPS verification

Completed `2026-11-07T14:10:40Z`. Downloaded the live catalog, manifest and
download index with certificate verification, checked their pinned lengths/hashes,
then fetched every compressed chunk. Verified compressed size/hash, decompressed
size/hash, the 4 MiB expanded chunk bound and each complete payload hash. No model
inference or Android build was run locally.

All 234 chunks across six files passed; total expanded payload bytes: 957283224.
The catalog responded with `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.

| Item | Bytes | SHA-256 |
| --- | ---: | --- |
| Manifest | 3295 | `759890e4b6c3f6c640f117fc7b624a5dd8667a89284d991c0fd6681b76ec84c5` |
| Download index | 45614 | `8e9eb1bec7c3cad7ba08ae18110ea41c1caa523cc2e3749df5eba01b4ac77f24` |
| `LICENSE-asr.txt` | 1070 | `f00de6715714c7a63d08639cdbfaa40224eefc407302614bd19f1a8b98c875aa` |
| `LICENSE-speaker.txt` | 11357 | `c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4` |
| `asr.onnx` | 885826856 | `9e423f2690bfaf3e0fbb81185a080bfff27c4ec5aacef5e615676fffbe2545ff` |
| `smoke-report.json` | 1880 | `2097cc1c494f9e69e66d9b5d6b336957469a720285607c1f34228099b5006647` |
| `speaker.onnx` | 71441526 | `bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239` |
| `tokens.json` | 535 | `f3c52d56529a595c9de0c2b2f2fd95553ef8834861e6d2ee720a1626bcd01a8a` |

The ASR and speaker graph digests match Timofey’s verified T1 graph identities.
The manifest digest is specific to this export and is not substituted for an
APK hash. The live [catalog](https://ascent.arbuz.lol/models/catalog.json) may
advance on a later successful publication; retain the immutable digest paths for
a trial’s exact source identity.

This catalog supplies the acoustic pack, not the separate text-model assets.
It restores the compact prototype’s normal download path and does not make a
fresh offline install or meaningful ARM local-LLM execution pass.
