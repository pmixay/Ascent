# Ascent model distribution

The fixed HTTPS host is `https://ascent.arbuz.lol`. It serves only public model
files and a health endpoint. Speech recognition runs on the phone. This service
does not accept audio, transcripts, or inference requests.

`Caddyfile` configures automatic HTTPS and static files under `/srv/ascent`.
`ascent-models.service` runs Caddy as an unprivileged service account. Access
logging is not enabled. The domain must resolve to this host and ports 80/443
must be reachable for certificate issuance and downloads.

The app fetches `/models/catalog.json`, then derives the immutable directory
`/models/<model_id>/<manifest_sha256>/`. It verifies the manifest length/hash
and all six payload lengths/hashes before selecting the installed models.
The catalog is replaced atomically after a complete revision is published.

Model CI sends a temporary, verified ZIP over SSH. Users never download or
import that transport archive. A dedicated `ascent-models` account has a
restricted SSH key with a forced `upload_models.py` command; it cannot open an
interactive shell, forward ports, or modify the root-owned upload code.
The server validates the file set, size limits, hashes and passing smoke report
again before publication. Existing model revisions are immutable and retained
so in-progress downloads remain valid.

Install `upload_models.py` and the repository's `tools/model_pack.py` together
under `/usr/local/lib/ascent-model-service/`, owned by root. The dedicated
deployment account owns `/srv/ascent/models` and its private staging directory.
GitHub secrets `ASCENT_MODEL_DEPLOY_KEY` and `ASCENT_MODEL_HOST_KEY` hold the
restricted deployment key and pinned SSH host key. They are never app secrets
and are never included in an APK.

Builds, model export and executable validation run in GitHub CI. Server
provisioning is separate from Android compilation. No application release tag
is needed to publish models.
