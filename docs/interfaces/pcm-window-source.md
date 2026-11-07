# PcmWindowSource: how audio reaches the recognition engine

Status: the interface and the engine overload are pushed in `7782992` and have only been
reviewed statically. CI run 42365948389 had not finished when this was written, so nothing
here is a verified build or test result.

This is the engine side of plan item A2 ("Connect the existing engine to a bounded URI/WAV
source"). Source files are in
`prototypes/phone-transcriber/android/app/src/main/java/com/pmixay/ascent/`:
`PcmWindowSource.kt`, `PcmWave.kt` and `TranscriptionEngine.kt`.

## The contract

```kotlin
interface PcmWindowSource : Closeable {
    val sampleCount: Long                       // total samples at 16 kHz
    val durationMs: Long                        // default: sampleCount * 1000 / 16000
    fun read(start: Long, count: Int): FloatArray
}
```

Entry point: `TranscriptionEngine.transcribeWithSpeakers(source, onProgress)`. The older
`transcribeWithSpeakers(file, onProgress)` still exists and wraps the file in a `PcmWave`.

## What an implementation must do

- **Format.** `read` returns mono, 16 kHz, floats in [-1, 1]. Decoding, downmixing and
  resampling are the implementation's job; the engine does no conversion.
- **Coordinates.** Position `0` is the first sample of the original media. Leading silence
  and decoder delay must be accounted for, because every word time in the transcript is
  derived from these positions. Do not renumber after trimming.
- **Exact windows.** `read(start, count)` returns exactly `count` samples and throws
  `IllegalArgumentException` for a range outside `0..sampleCount`, as `PcmWave` does.
- **Random access, repeated reads.** The engine makes two passes: recognition walks the
  recording in windows (core 20 s, 1.25 s of context on each side, plus a 2 s search for a
  quiet seam, see `AsrChunking`), then speaker analysis re-reads short windows by position.
  A source that wraps a stream must be able to read earlier positions again, for example
  by reopening the `content://` stream and skipping, without keeping the whole recording.
- **No audio persistence.** Do not copy the user's file into app storage, caches or temporary
  directories. Keep at most the window being returned and do not retain returned arrays. The
  engine zeroes its speaker-analysis windows after use.
- **Ownership.** Whoever creates a source closes it. The engine only reads. The file
  entry point closes its own `PcmWave`.
- **Cancellation.** The engine checks the thread's interrupted flag between windows and
  aborts. A slow `read` should not block interruption.
- **Limit.** `PcmWave` rejects recordings over 30 minutes. A new source decides its own
  limit and must fail clearly instead of truncating silently.

## Minimal in-memory example

```kotlin
val source = object : PcmWindowSource {
    override val sampleCount = samples.size.toLong()
    override fun read(start: Long, count: Int): FloatArray {
        require(start >= 0 && count >= 0 && start + count <= sampleCount)
        return samples.copyOfRange(start.toInt(), start.toInt() + count)
    }
    override fun close() = Unit
}
```

`PcmWaveTest` contains this example as a test, plus a check that the file reader conforms.

## Not covered here

URI permission lifetime, format detection, job ownership and result storage belong to the
import and job layers. Timestamps stay CTC-derived and approximate.
