# Applied to the optimized debug app/test boundary only. The instrumentation
# APK is compiled independently, so app-only R8 cannot see these consumers.
# Retain public APIs used directly by runtime tests, including Kotlin default
# constructors. Private implementations and all other app code remain optimized.
# Evidence: teammate run 42381902354 loses TranscriptSegment's default constructor
# and WorkManager/Configuration classes even after the Kotlin API fix.
# Combined run 42382620664 also loses AudioSources; preserve the reviewed
# application-test boundary explicitly, without keeping the whole app package.
-keep class com.pmixay.ascent.ConversationStore { public *; protected *; }
-keep class com.pmixay.ascent.SavedConversation { public *; protected *; }
-keep class com.pmixay.ascent.TranscriptSegment { public *; protected *; }
-keep class com.pmixay.ascent.ModelSelection { public *; protected *; }
-keep class com.pmixay.ascent.ModelLease { public *; protected *; }
-keep class com.pmixay.ascent.ModelDownloads { public *; protected *; }
-keep class com.pmixay.ascent.ModelDownloaderKt { public *; protected *; }
-keep class com.pmixay.ascent.ModelDownloadService { public *; protected *; }
-keep class com.pmixay.ascent.ModelDownloadService$Companion { public *; protected *; }
-keep class com.pmixay.ascent.ModelDownloadWorker { public *; protected *; }
-keep class com.pmixay.ascent.ModelUpdateWorker { public *; protected *; }
-keep class com.pmixay.ascent.ModelTransferControl { public *; protected *; }
-keep class com.pmixay.ascent.ModelTransferState { public *; protected *; }
-keep class com.pmixay.ascent.ModelDownloadProgress { public *; protected *; }
-keep class com.pmixay.ascent.ModelTransferPhase { public *; protected *; }
-keep class com.pmixay.ascent.ModelVersionStatus { public *; protected *; }
-keep class com.pmixay.ascent.ModelCatalog { public *; protected *; }
-keep class com.pmixay.ascent.ModelSource { public *; protected *; }
-keep class com.pmixay.ascent.ModelSpec { public *; protected *; }
-keep class com.pmixay.ascent.ModelSpec$Companion { public *; protected *; }
-keep class com.pmixay.ascent.ModelManifest { public *; protected *; }
-keep class com.pmixay.ascent.ModelManifest$Companion { public *; protected *; }
-keep class com.pmixay.ascent.PcmWindowSource { public *; protected *; }
-keep class com.pmixay.ascent.AudioSources { public *; protected *; }
-keep class com.pmixay.ascent.DescriptorInput { public *; protected *; }
-keep class com.pmixay.ascent.RandomAccessInput { public *; protected *; }
-keep class com.pmixay.ascent.ImportFailure { public *; protected *; }
-keep class com.pmixay.ascent.AudioImportException { public *; protected *; }
-keep class com.pmixay.ascent.TextStages { public *; protected *; }
-keep class com.pmixay.ascent.TextStageOutcome { public *; protected *; }
-keep class com.pmixay.ascent.TextStageReport { public *; protected *; }
-keep class com.pmixay.ascent.TextStageStatus { public *; protected *; }
-keep class com.pmixay.ascent.EmbeddedModels { public *; protected *; }
-keep class com.pmixay.ascent.EmbeddedInstallation { public *; protected *; }
-keep class com.pmixay.ascent.ModelStorageDiagnostics { public *; protected *; }

# work-testing calls the WorkManager library across the APK boundary, including
# public implementation APIs. Preserve its binary API without keeping AndroidX
# generally or disabling shrinking/optimization.
-keep class androidx.work.** { public *; protected *; }

# work-testing creates its coroutine scope through the shared coroutines module;
# combined CI 42383364630 exposed a removed CoroutineContext factory method.
# Keep this library's public/protected ABI on the debug/test boundary as well.
-keep class kotlinx.coroutines.** { public *; protected *; }

# This small UI policy helper is directly asserted by the runtime status test.
-keep class com.pmixay.ascent.ModelStatusPolicyKt {
    public static java.lang.String modelCheckAgeLabel(long, long);
}
