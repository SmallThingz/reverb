package app.smallthingz.reverb

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/** Standalone runner: no production/test framework dependency and a separate installed package. */
class ReliabilityInstrumentation : Instrumentation() {
    private var requestedTest: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        requestedTest = arguments?.getString("test")
        start()
    }

    override fun onStart() {
        val report = StringBuilder()
        var failures = 0
        var executed = 0
        try {
            check(targetContext.packageName.endsWith(".reliability")) {
                "Reliability tests must never run against a user's Reverb installation"
            }
            if (requestedTest == null || requestedTest == "brand_geometry") {
                executed++
                try {
                    verifyBrandGeometry(targetContext)
                    report.append("PASS brand_geometry\n")
                } catch (error: Throwable) {
                    failures++
                    report.append("FAIL brand_geometry\n").append(Log.getStackTraceString(error)).append('\n')
                }
            }
            if (requestedTest == "ui_seed_large_history") {
                executed++
                prepareLargeHistoryUi(targetContext)
                report.append("PASS ui_seed_large_history\n")
            }
            if (requestedTest == "ui_range_loading") {
                executed++
                verifyLargeHistoryRangeUi(this, report)
            }
            if (requestedTest == "large_history_performance") {
                executed++
                measureLargeHistory(targetContext, report)
                report.append("PASS large_history_performance\n")
            }
            if (requestedTest == "cold_history_profile") {
                executed++
                profileColdHistory(targetContext, report)
                report.append("PASS cold_history_profile\n")
            }
            if (requestedTest == "saved_file_performance") {
                executed++
                measureSavedFilePerformance(targetContext, report)
                report.append("PASS saved_file_performance\n")
            }
            if (requestedTest == "provider_fingerprint_performance") {
                executed++
                measureProviderFingerprintPerformance(targetContext, report)
                report.append("PASS provider_fingerprint_performance\n")
            }
            if (requestedTest == "crash_prepare") {
                val pid = prepareDurableCaptureForProcessDeath(targetContext)
                sendStatus(0, Bundle().apply { putString("stream", "PREPARED durable PCM; terminating isolated QA pid=$pid\n") })
                Process.killProcess(pid)
                error("Isolated process termination did not occur")
            }
            val tree = DocumentsContract.buildTreeDocumentUri(
                "${targetContext.packageName}.reliability.documents",
                ReliabilityDocumentsProvider.ROOT_ID,
            )
            targetContext.contentResolver.acquireContentProviderClient(tree).use { client ->
                val provider = requireNotNull(client?.localContentProvider as? ReliabilityDocumentsProvider)
                val cases = listOf(
                    Triple("rename_new_id_missing_old_uri", ReliabilityDocumentsProvider.Mode.RENAME_NEW_ID, true),
                    Triple("rename_same_id", ReliabilityDocumentsProvider.Mode.RENAME_SAME_ID, true),
                    Triple("rename_noop_rejected", ReliabilityDocumentsProvider.Mode.RENAME_NOOP, false),
                    Triple("provider_chosen_final_name", ReliabilityDocumentsProvider.Mode.RENAME_PROVIDER_SUFFIX, true),
                    Triple("rename_then_transport_failure", ReliabilityDocumentsProvider.Mode.RENAME_THEN_THROW, true),
                    Triple("copy_like_rename_rejected", ReliabilityDocumentsProvider.Mode.COPY_INSTEAD_OF_RENAME, false),
                    Triple("changed_bytes_rejected", ReliabilityDocumentsProvider.Mode.CORRUPT_RENAMED_BYTES, false),
                    Triple("loading_listing_rejected", ReliabilityDocumentsProvider.Mode.LOADING_AFTER_RENAME, false),
                    Triple("error_listing_rejected", ReliabilityDocumentsProvider.Mode.ERROR_AFTER_RENAME, false),
                    Triple("unavailable_listing_rejected", ReliabilityDocumentsProvider.Mode.UNAVAILABLE_AFTER_RENAME, false),
                    Triple("duplicate_listing_rejected", ReliabilityDocumentsProvider.Mode.DUPLICATE_LISTING_AFTER_RENAME, false),
                )
                for ((name, mode, expectedSuccess) in cases) {
                    if (requestedTest != null && requestedTest != name) continue
                    executed++
                    try {
                        provider.reset(mode)
                        val payload = ByteArray(32_000) { ((it * 17 + 3) and 0xff).toByte() }
                        val unchangedSource = payload.clone()
                        val target = createOutputTargetInDirectory(
                            targetContext, tree, "fixture-${System.nanoTime()}.wav", "audio/wav", 1_790_000_000_000L,
                            StagingOutputKind.EXPORT_TRACKED,
                        )
                        val writer = WavAudioFileWriter(targetContext, target, 16_000, 1)
                        writer.use { it.write(payload, 0, payload.size) }
                        val fingerprint = verifyWavOutputTargetAndDigest(
                            targetContext, target, writer.totalFileBytesWritten, writer.expectedHeaderBytes,
                            writer.payloadOffsetBytes, writer.totalSampleBytesWritten, writer.payloadSha256,
                        )
                        requireVerifiedOutputRecoveryMarker(putVerifiedExportStaging(targetContext, target, fingerprint))
                        val result = runCatching { finalizeOutputTarget(targetContext, target, fingerprint) }
                        check(provider.renamed) { "Fixture did not exercise publication" }
                        check(provider.deleteCalls == 0) { "Publication deleted a possibly unique recording" }
                        check(payload.contentEquals(unchangedSource)) { "Export mutated source PCM" }
                        check(provider.retainedBytes().isNotEmpty()) { "Publication discarded every output" }
                        if (expectedSuccess) {
                            val published = result.getOrThrow()
                            check(!published.staging)
                            val expectedName = if (mode == ReliabilityDocumentsProvider.Mode.RENAME_PROVIDER_SUFFIX) {
                                target.displayName.removeSuffix(".wav") + " (provider).wav"
                            } else target.displayName
                            check(published.displayName == expectedName)
                            val actual = targetContext.contentResolver.openInputStream(requireNotNull(published.uri))
                                ?.use { it.readBytes() }
                            check(requireNotNull(actual).contentEquals(writer.expectedHeaderBytes + payload)) {
                                "Published bytes do not equal the verified source"
                            }
                            if (mode != ReliabilityDocumentsProvider.Mode.RENAME_SAME_ID) {
                                check(provider.oldIdQueries > 0) { "Invalidated old URI was not exercised" }
                            }
                            check(removeVerifiedExportStaging(targetContext, target.storageType, target.id, fingerprint))
                        } else {
                            check(result.isFailure) { "Unsafe/incomplete publication was reported as successful" }
                        }
                        report.append("PASS ").append(name).append('\n')
                    } catch (error: Throwable) {
                        failures++
                        report.append("FAIL ").append(name).append('\n').append(Log.getStackTraceString(error)).append('\n')
                    }
                }
                if (requestedTest == null || requestedTest == "wav_format_matrix") {
                    verifyWavPublicationMatrix(targetContext, tree, provider) { name, test ->
                        executed++
                        try {
                            test()
                            report.append("PASS ").append(name).append('\n')
                        } catch (error: Throwable) {
                            failures++
                            report.append("FAIL ").append(name).append('\n')
                                .append(Log.getStackTraceString(error)).append('\n')
                        }
                    }
                }
                if (requestedTest == null || requestedTest == "provider_terminal_sweep") {
                    verifyProviderTerminalMatrix(targetContext, tree, provider) { name, test ->
                        executed++
                        try {
                            test()
                            report.append("PASS ").append(name).append('\n')
                        } catch (error: Throwable) {
                            failures++
                            report.append("FAIL ").append(name).append('\n')
                                .append(Log.getStackTraceString(error)).append('\n')
                        }
                    }
                }
                if (requestedTest == null || requestedTest == "compatibility_sweep") {
                    verifyCompatibilityMatrix(targetContext, tree, provider) { name, test ->
                        executed++
                        try {
                            test()
                            report.append("PASS ").append(name).append('\n')
                        } catch (error: Throwable) {
                            failures++
                            report.append("FAIL ").append(name).append('\n')
                                .append(Log.getStackTraceString(error)).append('\n')
                        }
                    }
                }
            }
            if (requestedTest == null || requestedTest == "tile_hydration_convergence") {
                executed++
                try {
                    verifyTileHydrationConverges(targetContext)
                    report.append("PASS tile_hydration_convergence\n")
                } catch (error: Throwable) {
                    failures++
                    report.append("FAIL tile_hydration_convergence\n").append(Log.getStackTraceString(error)).append('\n')
                }
            }
            if (requestedTest == null || requestedTest == "parallel_history_recovery") {
                executed++
                try {
                    verifyParallelHistoryRecovery(targetContext)
                    report.append("PASS parallel_history_recovery\n")
                } catch (error: Throwable) {
                    failures++
                    report.append("FAIL parallel_history_recovery\n").append(Log.getStackTraceString(error)).append('\n')
                }
            }
            if (requestedTest == null || requestedTest == "header_cache_safety") {
                executed++
                try {
                    verifyChunkHeaderCacheOwnership(targetContext)
                    report.append("PASS header_cache_safety\n")
                } catch (error: Throwable) {
                    failures++
                    report.append("FAIL header_cache_safety\n").append(Log.getStackTraceString(error)).append('\n')
                }
            }
            if (requestedTest == null || requestedTest == "capture_restart_failure") {
                executed++
                try {
                    verifyFailedCaptureRestartIsNotSilentlyListening(targetContext)
                    report.append("PASS capture_restart_failure\n")
                } catch (error: Throwable) {
                    failures++
                    report.append("FAIL capture_restart_failure\n").append(Log.getStackTraceString(error)).append('\n')
                }
            }
            if (requestedTest == "crash_recover") {
                executed++
                verifyDurableCaptureAfterProcessDeath(targetContext)
                report.append("PASS durable_pcm_and_incident_after_process_death\n")
            }
            check(executed > 0) { "No tests matched: $requestedTest" }
        } catch (error: Throwable) {
            failures++
            report.append(Log.getStackTraceString(error)).append('\n')
        }
        report.append("RESULT tests=").append(executed).append(" failures=").append(failures).append('\n')
        File(targetContext.filesDir, "reliability-results.txt").writeText(report.toString())
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "\n$report")
            putInt("tests", executed)
            putInt("failures", failures)
        })
    }
}
