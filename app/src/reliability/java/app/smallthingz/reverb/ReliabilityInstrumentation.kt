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
    private var keyboardSessionNonce: String? = null
    private var fixtureSessionNonce: String? = null
    private var fixtureObservationDirectory: String? = null
    private var fixtureObservationSha256: String? = null
    private var fixtureRecoveryLedgerSha256: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        requestedTest = arguments?.getString("test")
        keyboardSessionNonce = arguments?.getString("keyboard_session_nonce")
        fixtureSessionNonce = arguments?.getString("fixture_session_nonce")
        fixtureObservationDirectory = arguments?.getString("fixture_observation_directory")
        fixtureObservationSha256 = arguments?.getString("fixture_observation_sha256")
        fixtureRecoveryLedgerSha256 = arguments?.getString("fixture_recovery_ledger_sha256")
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
            val parityOnly = targetContext.applicationContext.javaClass == android.app.Application::class.java
            check(!parityOnly || requestedTest in setOf(
                "native_font_notifications", "native_group_parity", "native_text_parity", "native_icon_parity",
                "native_text_backend", "native_motion_spline",
                "native_brand_parity", "native_switch_parity", "native_switch_motion",
                "native_input_parity", "native_onboarding_page_parity", "native_onboarding_screen_parity", "native_about_parity", "native_retention_input_parity",
                "native_home_parity", "native_home_input", "native_main_settings_parity",
                "native_home_input_common_grain", "native_main_settings_parity_common_grain",
                "native_library_admission",
                "native_main_incidents_visibility",
                "native_home_accessibility",
                "native_onboarding_audio_flow",
                "native_library_parity", "native_library_playback", "native_library_transactions", "native_library_menu", "native_range_parity", "native_content_restoration", "native_legacy_state_parity", "native_icon_clip_parity", "native_retention_keyboard",
                "native_activity_lifecycle", "native_incidents_parity", "native_onboarding_input_parity", "native_incidents_interaction",
                "native_settings_compression", "native_settings_duration", "native_settings_calibration", "native_fine_seek_leaf",
                "native_library_fixture_observation",
                "native_library_fixture_cleanup",
                "native_transaction_fixture_recovery",
            )) {
                "The native-parity APK requires an explicit UI-only fixture"
            }
            if (requestedTest == "native_text_backend" || requestedTest == "native_motion_spline") {
                requireNativeMathProbeHost(targetContext.packageName, parityOnly)
            }
            if (requestedTest == "native_text_backend") {
                executed++
                val result = nativeParityOnMain(this) { nativeTextBackendParity(targetContext) }
                val evidence = File(targetContext.filesDir, "native-text-backend-" + System.currentTimeMillis() + ".json")
                evidence.writeText(result.toString(2))
                report.appendLine("native_text_backend evidence=" + evidence.absolutePath)
                check(result.getBoolean("pass")) { "Native text backend differences: " + result.getInt("failures") }
                report.appendLine("PASS native_text_backend cases=" + result.getJSONArray("cases").length())
            } else if (requestedTest == "native_motion_spline") {
                executed++
                verifyNativeMotionSplineParity(report)
            } else if (requestedTest == "native_library_fixture_observation") {
                executed++
                observeNativeLibraryMigratedFixtures(this, report, fixtureSessionNonce)
            } else if (requestedTest == "native_library_fixture_cleanup") {
                executed++
                cleanupNativeLibraryMigratedFixtures(this, report, fixtureSessionNonce,
                    fixtureObservationDirectory, fixtureObservationSha256)
            } else if (requestedTest == "native_transaction_fixture_recovery") {
                executed++
                recoverExactNativeTransactionFixture(this, report, fixtureSessionNonce, fixtureRecoveryLedgerSha256)
            } else if (requestedTest == "native_fine_seek_leaf") {
                executed++
                verifyNativeFineSeekLeafParity(this, report)
            } else if (requestedTest == "native_settings_compression") {
                executed++
                verifyNativeSettingsCompressionInteraction(this, report)
            } else if (requestedTest == "native_settings_duration") {
                executed++
                verifyNativeSettingsDurationInteraction(this, report)
            } else if (requestedTest == "native_settings_calibration") {
                executed++
                verifyNativeSettingsCalibrationInteraction(this, report)
            } else if (requestedTest == "native_incidents_interaction") {
                executed++
                verifyNativeIncidentsInteraction(this, report)
            } else if (requestedTest == "native_onboarding_input_parity") {
                executed++
                verifyNativeOnboardingInputParity(this, report)
            } else if (requestedTest == "native_onboarding_audio_flow") {
                executed++
                verifyNativeOnboardingAudioFlow(this, report)
            } else if (requestedTest == "native_incidents_parity") {
                executed++
                verifyNativeIncidentsParity(this, report)
            } else if (requestedTest == "native_activity_lifecycle") {
                executed++
                verifyNativeActivityLifecycle(this, report)
            } else if (requestedTest == "native_font_notifications") {
                executed++
                verifyNativeFontNotifications(this, report)
            } else if (requestedTest == "native_brand_parity") {
                executed++
                verifyNativeBrandParity(this, report)
            } else if (requestedTest == "native_about_parity") {
                executed++
                verifyNativeAboutParity(this, report, keyboardSessionNonce)
            } else if (requestedTest == "native_main_settings_parity") {
                executed++
                verifyNativeMainSettingsParity(this, report)
            } else if (requestedTest == "native_main_settings_parity_common_grain") {
                executed++
                verifyNativeMainSettingsCommonGrainParity(this, report)
            } else if (requestedTest == "native_library_admission") {
                executed++
                verifyNativeLibraryAdmissionRegression(this, report)
            } else if (requestedTest == "native_main_incidents_visibility") {
                executed++
                verifyNativeMainIncidentsVisibility(this, report)
            } else if (requestedTest == "native_library_parity") {
                executed++
                verifyNativeLibraryParity(this, report)
            } else if (requestedTest == "native_library_transactions") {
                executed++
                verifyNativeLibraryTransactions(this, report)
            } else if (requestedTest == "native_library_menu") {
                executed++
                verifyNativeLibraryTransactions(this, report, menuOnly = true)
            } else if (requestedTest == "native_library_playback") {
                executed++
                verifyNativeLibraryPlayback(this, report)
            } else if (requestedTest == "native_legacy_state_parity") {
                executed++
                verifyNativeLegacySavedStateParity(this, report)
            } else if (requestedTest == "native_content_restoration") {
                executed++
                verifyNativeContentRestoration(this, report)
            } else if (requestedTest == "native_range_parity") {
                executed++
                verifyNativeRangeParity(this, report)
            } else if (requestedTest == "native_home_parity") {
                executed++
                verifyNativeHomeParity(this, report)
            } else if (requestedTest == "native_home_input") {
                executed++
                verifyNativeHomeInputParity(this, report)
            } else if (requestedTest == "native_home_input_common_grain") {
                executed++
                verifyNativeHomeInputCommonGrainParity(this, report)
            } else if (requestedTest == "native_home_accessibility") {
                executed++
                verifyNativeHomeAccessibilityParity(this, report)
            } else if (requestedTest == "native_retention_keyboard") {
                executed++
                verifyNativeRetentionKeyboardParity(this, report, keyboardSessionNonce)
            } else if (requestedTest == "native_retention_input_parity") {
                executed++
                verifyNativeRetentionInputParity(this, report)
            } else if (requestedTest == "native_onboarding_screen_parity") {
                executed++
                verifyNativeOnboardingScreenParity(this, report)
            } else if (requestedTest == "native_onboarding_page_parity") {
                executed++
                verifyNativeOnboardingPageParity(this, report)
            } else if (requestedTest == "native_input_parity") {
                executed++
                verifyNativeInputParity(this, report, keyboardSessionNonce)
            } else if (requestedTest == "native_switch_parity") {
                executed++
                verifyNativeSwitchStaticParity(this, report)
            } else if (requestedTest == "native_switch_motion") {
                executed++
                verifyNativeSwitchObservedMotion(this, report)
            } else if (requestedTest == "native_icon_clip_parity") {
                executed++
                verifyNativeIconClipParity(this, report)
            } else if (requestedTest == "native_icon_parity") {
                executed++
                verifyNativeIconParity(this, report)
            } else if (requestedTest == "native_group_parity") {
                executed++
                verifyNativeGroupParity(this, report)
            } else if (requestedTest == "native_text_parity") {
                executed++
                verifyNativeTextParity(this, report)
            } else {
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
                if (requestedTest == "flac_output_buffer_roundtrip") {
                    executed++
                    verifyFlacOutputBufferRoundTrip(targetContext, report)
                    report.append("PASS flac_output_buffer_roundtrip\n")
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
                    if (requestedTest == "document_rename_null_response") {
                        verifyDocumentRenameNullResponse(targetContext, tree, provider) { name, test ->
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
