# Configuration source map

The organizer template has a `config/` directory. Ascent's current nonsecret model-pack schema and source identifiers live in [model_pack.py](../prototypes/phone-transcriber/tools/model_pack.py), Android build/ABI settings in [app/build.gradle.kts](../prototypes/phone-transcriber/android/app/build.gradle.kts), and fixed model-catalog policy in the Android `ModelDownloads`/`ModelPackImporter` sources. The competition manifest and local text-model selection are pending integration; record their exact hashes here after they exist and are measured.

No API key or service URL setting belongs in a jury-facing configuration file. The verification app must perform all speech and text processing offline on the device.
