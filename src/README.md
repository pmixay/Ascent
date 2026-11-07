# Source location

The organizer [template](https://github.com/grigorevmp/hacathon_template) uses a `src/` directory. Ascent keeps its existing Android project at [prototypes/phone-transcriber/android](../prototypes/phone-transcriber/android/) to preserve its CI and source history. Its app module contains the Russian phone UI, local speech pipeline, encrypted conversation storage and tests. The model export and evaluation utilities are under [prototypes/phone-transcriber/tools](../prototypes/phone-transcriber/tools/), the static model publisher under [server](../server/), and CI definitions under [.github/workflows](../.github/workflows/).

The source map is intentional; no duplicate Android tree is maintained here. Follow [INSTALLATION.md](../docs/INSTALLATION.md) for the eventual CI-built judge APK and [ARCHITECTURE.md](../docs/ARCHITECTURE.md) for component boundaries. All Android builds, lint and application tests run through the existing GitHub workflows under [AGENTS.md](../AGENTS.md).

