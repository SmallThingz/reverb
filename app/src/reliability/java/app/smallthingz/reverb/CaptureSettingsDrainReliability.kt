package app.smallthingz.reverb

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic PCM through the actual Service/coordinator; does not model Android native overflow. */
internal fun verifyCaptureSettingsDrain(context: Context, report: StringBuilder) {
    for (token in listOf("settings_looping_drain", "settings_other_pause", "settings_one_shot_resize")) {
        val otherPause = token == "settings_other_pause"
        val oneShotResize = token == "settings_one_shot_resize"
        withFramePipeline(context, token, isolateSaveCoordinator = true) { fixture ->
            val input = PipelineInput(holdFirst = true, automaticReads = 2) { ordinal, _ ->
                ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN).apply {
                    repeat(512) { putShort(((ordinal - 1) * 512 + it + 1).toShort()) }
                }.array()
            }
            val syncEntered = CountDownLatch(1)
            val releaseSync = CountDownLatch(1)
            val nextControl = CountDownLatch(1)
            val secondReturnReleasedDuringSync = AtomicBoolean()
            if (!otherPause && !oneShotResize) {
                input.beforeReadReturn = { ordinal ->
                    if (ordinal == 2) {
                        // Early admission may precede the writer's sync call. Hold this return
                        // until that call is blocked, so read 3 proves progress during the stall.
                        check(syncEntered.await(10, TimeUnit.SECONDS)) { "Second return never reached Settings sync" }
                        secondReturnReleasedDuringSync.set(releaseSync.count == 1L)
                    }
                }
            }
            val observations = File(fixture.root, "observations.txt")
            var blockedEvidenceRecorded = false
            fun recordBlockedEvidence(thirdReadReached: Boolean? = null) {
                val current = fixture.io.currentInput()
                val status = current?.storage?.ring?.status()
                observations.writeText(
                    "path=production_coordinator\nnativeSource=synthetic\nrealMicrophoneConstructed=false\n" +
                        "otherPausingControl=$otherPause\noneShotResize=$oneShotResize\n" +
                        "retentionSyncReached=${syncEntered.count == 0L}\nbarrierHeld=${releaseSync.count == 1L}\n" +
                        "nativeReadsAtBlockedSync=${input.reads.get()}\nthirdReadReached=$thirdReadReached\n" +
                        "secondReturnReleasedDuringSync=${secondReturnReleasedDuringSync.get()}\n" +
                        "processedFramesAtBlockedSync=${current?.processedFrames}\n" +
                        "ringReading=${status?.reading}\nringOccupiedPackets=${status?.occupiedPackets}\n" +
                        "ringOccupiedBytes=${status?.occupiedBytes}\nringFence=${status?.fence}\n" +
                        "ringTerminal=${status?.terminal}\nnativeFactoryCalls=${fixture.admission.factoryCalls.get()}\n" +
                        "expectedReturnedFrames=1024\nandroidOverrunReproduced=false\n")
                blockedEvidenceRecorded = true
            }
            fixture.configure(input, ReverbService.BufferSlot.LOOPING, foreground = true)
            fixture.onAudio {
                // Fixture setup changes effective native format only; requested identity stays
                // exactly the production configuration sampled during Service initialization.
                fixture.service.frameSet("silenceGateConfig", SilenceGatePreferences.read(fixture.admission.context).config)
                val retention = RetentionConfiguration(RetentionMode.SIZE, 60,
                    if (oneShotResize) 512 else 1_048_576, 60, 2_097_152)
                check(writeRetentionRecoveryConfiguration(fixture.admission.context, retention))
                check(restoreRetentionConfigurationToPreferences(getRecorderPreferences(fixture.admission.context), retention))
            }
            fixture.admission.retentionSyncBarrier.set {
                File(fixture.root, "settings-sync-stack.txt").writeText(Thread.currentThread().stackTrace.joinToString("\n"))
                syncEntered.countDown()
                check(releaseSync.await(10, TimeUnit.SECONDS)) { "Settings sync barrier was not released" }
            }
            try {
                fixture.start()
                check(input.firstEntered.await(5, TimeUnit.SECONDS))
                check(fixture.service.applyUpdatedPreferences())
                if (otherPause) {
                    check(fixture.io.post(pauseInput = true, allowTerminal = true,
                        rejected = { error("Following control rejected") }, work = { nextControl.countDown() }) ==
                        CaptureControlMailbox.Admission.ACCEPTED)
                }
                input.allowFirst.countDown()
                check(syncEntered.await(5, TimeUnit.SECONDS)) { "Actual Settings retention sync was not reached" }
                if (otherPause || oneShotResize) {
                    recordBlockedEvidence()
                    check(input.reads.get() == 1 && !fixture.io.inputIsReading())
                    check(nextControl.count == 1L) { "Later control overtook blocked Settings" }
                    if (oneShotResize) {
                        check(fixture.service.selectCaptureBuffer(ReverbService.BufferSlot.ONE_SHOT).accepted)
                    }
                    releaseSync.countDown()
                    if (otherPause) check(nextControl.await(5, TimeUnit.SECONDS))
                    check(input.thirdEntered.await(5, TimeUnit.SECONDS))
                } else {
                    val thirdReadReached = input.thirdEntered.await(5, TimeUnit.SECONDS)
                    recordBlockedEvidence(thirdReadReached)
                    check(thirdReadReached) {
                        "Native input did not drain during unchanged-format retention publication"
                    }
                    check(releaseSync.count == 1L && input.reads.get() == 3)
                    check(secondReturnReleasedDuringSync.get()) {
                        "Second native return was not released during the held Settings sync"
                    }
                    // Stop races the blocked Settings writer after the second native return.
                    check(fixture.service.disableListening().accepted)
                    input.unblockAll()
                    releaseSync.countDown()
                }
                if (otherPause || oneShotResize) check(fixture.service.disableListening().accepted)
                input.unblockAll()
                check(input.released.await(10, TimeUnit.SECONDS))
                val (actual, actualOne) = fixture.onAudio { fixture.loop.pipelinePcm() to fixture.one.pipelinePcm() }
                fun pcm(ids: List<Int>) = ByteBuffer.allocate(ids.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    ids.forEach { putShort(it.toShort()) }
                }.array()
                val expected = pcm(if (oneShotResize) (1..512).toList() + (769..1024).toList() else (1..1024).toList())
                val expectedOne = pcm(if (oneShotResize) (513..768).toList() else emptyList())
                File(fixture.root, "actual.pcm16le").writeBytes(actual)
                File(fixture.root, "expected.pcm16le").writeBytes(expected)
                File(fixture.root, "actual-one-shot.pcm16le").writeBytes(actualOne)
                File(fixture.root, "expected-one-shot.pcm16le").writeBytes(expectedOne)
                observations.appendText("actualReturnedFrames=${(actual.size + actualOne.size) / 2}\n")
                check(actual.contentEquals(expected)) { "Settings changed the returned PCM prefix" }
                check(actualOne.contentEquals(expectedOne)) { "Settings changed the selected One-shot PCM prefix" }
                check(fixture.admission.factoryCalls.get() == 0) { "Settings unexpectedly restarted native input" }
            } finally {
                try {
                    if (!blockedEvidenceRecorded) recordBlockedEvidence()
                } finally {
                    fixture.admission.retentionSyncBarrier.set(null)
                    releaseSync.countDown()
                    input.unblockAll()
                }
            }
        }
        report.append("PASS capture_").append(token).append(" synthetic PCM; native overflow unmodeled\n")
    }
}
