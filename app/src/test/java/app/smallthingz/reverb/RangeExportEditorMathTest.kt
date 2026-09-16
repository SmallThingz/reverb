package app.smallthingz.reverb

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeExportEditorMathTest {
    @Test
    fun previewExecutorRejection_isALifecycleNoOpInsteadOfCallerCrash() {
        var ran = false
        val direct = java.util.concurrent.Executor { task -> task.run() }
        assertTrue(executeIfAccepted(direct, Runnable { ran = true }))
        assertTrue(ran)

        val rejecting = java.util.concurrent.Executor {
            throw java.util.concurrent.RejectedExecutionException("closed")
        }
        assertFalse(executeIfAccepted(rejecting, Runnable { error("must not run") }))
    }

    @Test
    fun previewDrainWatchdogRequiresContinuousStall() {
        assertFalse(previewPlaybackDrainStalled(1_000L, 2_999L, 2_000L))
        assertTrue(previewPlaybackDrainStalled(1_000L, 3_000L, 2_000L))
        assertFalse(previewPlaybackDrainStalled(3_000L, 2_000L, 2_000L))
    }

    @Test
    fun playbackHeadCounterExtendsUnsignedWraps() {
        val counter = PlaybackHeadFrameCounter()
        assertEquals(0L, counter.update(0))
        assertEquals(2_147_483_647L, counter.update(Int.MAX_VALUE))
        assertEquals(2_147_483_648L, counter.update(Int.MIN_VALUE))
        assertEquals(4_294_967_295L, counter.update(-1))
        assertEquals(4_294_967_301L, counter.update(5))
    }

    @Test
    fun rememberedRangeExportStoresLengthAndPositionFromTimelineEnd() {
        assertEquals(
            RememberedRangeExport(selectionLengthMillis = 20_000L, endOffsetMillis = 15_000L),
            rememberedRangeExportFromSavedRange(
                availableSeconds = 100.0,
                startSeconds = 65f,
                endSeconds = 85f,
            ),
        )
        assertEquals(null, rememberedRangeExportFromSavedRange(100.0, 20f, 20f))
        assertEquals(null, rememberedRangeExportFromSavedRange(Double.NaN, 10f, 20f))
        assertEquals(
            RememberedRangeExport(selectionLengthMillis = 12_000L, endOffsetMillis = 15_000L),
            rememberedRangeExportFromSavedRange(
                availableSeconds = 100.0,
                startSeconds = 50f,
                endSeconds = 85f,
                actualSelectionMillis = 12_000L,
            ),
        )
    }

    @Test
    fun rememberedRangeExportRestoresEndRelativeAndFailsOverGracefully() {
        val remembered = RememberedRangeExport(
            selectionLengthMillis = 20_000L,
            endOffsetMillis = 15_000L,
        )

        // A longer timeline preserves both the 20 second length and 15 second end offset.
        assertEquals(RestoredRangeExport(85f, 105f), restoreRememberedRangeExport(120f, remembered))

        // If the old offset leaves too little room, preserve the length and shift toward the end.
        assertEquals(RestoredRangeExport(0f, 20f), restoreRememberedRangeExport(25f, remembered))

        // If even the old export length no longer fits, select everything that still exists.
        assertEquals(RestoredRangeExport(0f, 12f), restoreRememberedRangeExport(12f, remembered))

        // Invalid memory is ignored instead of manufacturing a broken range.
        assertEquals(
            RestoredRangeExport(0f, 50f),
            restoreRememberedRangeExport(50f, RememberedRangeExport(0L, 10_000L)),
        )
        assertEquals(RestoredRangeExport(0f, 50f), restoreRememberedRangeExport(50f, null))
    }

    @Test
    fun blobMorphUsesMeasuredRendererGeometryExactly() {
        assertEquals(0.330f, rangeBlobBaseRadiusFraction(active = true, enabled = true, activity = 0f), 0.000001f)
        assertEquals(0.348f, rangeBlobBaseRadiusFraction(active = true, enabled = true, activity = 1f), 0.000001f)
        assertEquals(0.1843f, rangeBlobBaseRadiusFraction(active = false, enabled = true, activity = 1f), 0.000001f)
        assertEquals(0.1796f, rangeBlobBaseRadiusFraction(active = false, enabled = false, activity = 1f), 0.000001f)

        val source = rangeMorphSourceGeometry(
            rootBoundsInRoot = Rect(10f, 20f, 1010f, 2020f),
            blobBoundsInRoot = Rect(210f, 320f, 810f, 920f),
            active = true,
            enabled = true,
            activity = 0f,
        )!!
        assertEquals(396f, source.bodyDiameterPx, 0.0001f)
        assertEquals(500f, source.centerXInLocalPx, 0.0001f)
        assertEquals(600f, source.centerYInLocalPx, 0.0001f)

        assertEquals(0.4f, rangeBlobMorphStartScaleX(200f, 500f), 0.0001f)
        val renderedSource = rangeMorphSourceGeometry(
            rootBoundsInRoot = Rect(10f, 20f, 610f, 820f),
            blobBoundsInRoot = Rect(160f, 220f, 460f, 520f),
            active = false,
            enabled = true,
            activity = 0f,
            renderedBaseRadiusFraction = 0.25f,
        )!!
        assertEquals(150f, renderedSource.bodyDiameterPx, 0.0001f)

        assertEquals(200f / 146f, rangeBlobMorphStartScaleY(200f, 146f), 0.0001f)
        assertEquals(-150f, rangeBlobMorphTranslation(0f, 100f, 250f), 0.0001f)
        assertEquals(-75f, rangeBlobMorphTranslation(0.5f, 100f, 250f), 0.0001f)
        assertEquals(0f, rangeBlobMorphTranslation(1f, 100f, 250f), 0.0001f)
        assertTrue(RANGE_BLOB_MORPH_HANDOFF_PROGRESS in 0.05f..0.10f)
        assertEquals(128, rangeWaveformRenderPointCount(512, 0.5f))
        assertEquals(256, rangeWaveformRenderPointCount(512, 0.9f))
        assertEquals(512, rangeWaveformRenderPointCount(512, 1f))
    }

    @Test
    fun movingRangeMarkerDragUsesTimelineStableCoordinates() {
        assertEquals(
            -350f,
            rangeTimelineDragDeltaPx(
                initialMarkerCenterPx = 1_000f,
                currentMarkerCenterPx = 650f,
                downLocalX = 32f,
                currentLocalX = 32f,
            ),
            0f,
        )
        assertEquals(
            -350f,
            rangeTimelineDragDeltaPx(
                initialMarkerCenterPx = 1_000f,
                currentMarkerCenterPx = 990f,
                downLocalX = 32f,
                currentLocalX = -308f,
            ),
            0f,
        )
    }

    @Test
    fun subMinimumTimelineNeverInventsAudioPastTheRealEnd() {
        assertEquals(0.02f, rangeTimelineDurationSeconds(0.02f), 0f)

        val actual = RangeEditValues(startSeconds = 0f, endSeconds = 0.02f)
        val moved = adjustRangeEditTarget(
            values = actual,
            target = RangeEditTarget.START,
            requestedSeconds = 0.01f,
            durationSeconds = 0.02f,
        )
        assertEquals(0f, moved.values.startSeconds, 0f)
        assertEquals(0.02f, moved.values.endSeconds, 0f)

        val resized = resizeRangeSelectionDuration(
            values = actual,
            target = RangeEditTarget.END,
            requestedDurationSeconds = 0.05f,
            durationSeconds = 0.02f,
        )
        assertEquals(0f, resized.values.startSeconds, 0f)
        assertEquals(0.02f, resized.values.endSeconds, 0f)
    }

    @Test
    fun rangeBoundariesPushEachOther_andPreserveMinimumRange() {
        val initial = RangeEditValues(startSeconds = 20f, endSeconds = 80f)
        val movedStart = adjustRangeEditTarget(
            values = initial,
            target = RangeEditTarget.START,
            requestedSeconds = 79.99f,
            durationSeconds = 100f,
        )
        assertEquals(79.99f, movedStart.values.startSeconds, 0.0001f)
        assertEquals(80.04f, movedStart.values.endSeconds, 0.0001f)

        val movedEnd = adjustRangeEditTarget(
            values = initial,
            target = RangeEditTarget.END,
            requestedSeconds = 20.01f,
            durationSeconds = 100f,
        )
        assertEquals(19.96f, movedEnd.values.startSeconds, 0.0001f)
        assertEquals(20.01f, movedEnd.values.endSeconds, 0.0001f)

        val rightCorner = RangeEditValues(startSeconds = 99.95f, endSeconds = 100f)
        val pushedAwayFromRight = adjustRangeEditTarget(rightCorner, RangeEditTarget.END, 90f, 100f)
        assertEquals(89.95f, pushedAwayFromRight.values.startSeconds, 0.0001f)
        assertEquals(90f, pushedAwayFromRight.values.endSeconds, 0f)

        val leftCorner = RangeEditValues(startSeconds = 0f, endSeconds = 0.05f)
        val pushedAwayFromLeft = adjustRangeEditTarget(leftCorner, RangeEditTarget.START, 10f, 100f)
        assertEquals(10f, pushedAwayFromLeft.values.startSeconds, 0f)
        assertEquals(10.05f, pushedAwayFromLeft.values.endSeconds, 0.0001f)

        val clampedStart = adjustRangeEditTarget(initial, RangeEditTarget.START, -50f, 100f)
        val clampedEnd = adjustRangeEditTarget(initial, RangeEditTarget.END, 150f, 100f)
        assertEquals(0f, clampedStart.values.startSeconds, 0f)
        assertEquals(100f, clampedEnd.values.endSeconds, 0f)
    }

    @Test
    fun selectionDurationMovesActiveBoundary_andDefaultsCleanlyToStartSemantics() {
        val initial = RangeEditValues(startSeconds = 20f, endSeconds = 80f)

        val moveStart = resizeRangeSelectionDuration(
            values = initial,
            target = RangeEditTarget.START,
            requestedDurationSeconds = 25f,
            durationSeconds = 100f,
        )
        assertEquals(55f, moveStart.values.startSeconds, 0f)
        assertEquals(80f, moveStart.values.endSeconds, 0f)

        val moveEnd = resizeRangeSelectionDuration(
            values = initial,
            target = RangeEditTarget.END,
            requestedDurationSeconds = 25f,
            durationSeconds = 100f,
        )
        assertEquals(20f, moveEnd.values.startSeconds, 0f)
        assertEquals(45f, moveEnd.values.endSeconds, 0f)

        val clampStart = resizeRangeSelectionDuration(initial, RangeEditTarget.START, 500f, 100f)
        val clampEnd = resizeRangeSelectionDuration(initial, RangeEditTarget.END, 500f, 100f)
        assertEquals(0f, clampStart.values.startSeconds, 0f)
        assertEquals(100f, clampEnd.values.endSeconds, 0f)
    }

    @Test
    fun selectionDurationWheelLimit_respectsFixedOppositeBoundary() {
        val values = RangeEditValues(startSeconds = 120f, endSeconds = 600f)

        assertEquals(
            600.0,
            rangeSelectionDurationReachableLimitSeconds(
                values = values,
                target = RangeEditTarget.START,
                timelineDurationSeconds = 900f,
            ),
            0.0,
        )
        assertEquals(
            780.0,
            rangeSelectionDurationReachableLimitSeconds(
                values = values,
                target = RangeEditTarget.END,
                timelineDurationSeconds = 900f,
            ),
            0.0,
        )
    }

    @Test
    fun selectionDurationWheelLimit_usesStricterReachabilityOrExportLimit() {
        val tenMinuteEnd = RangeEditValues(startSeconds = 0f, endSeconds = 600f)
        assertEquals(
            600.0,
            rangeSelectionDurationWheelLimitSeconds(
                values = tenMinuteEnd,
                target = RangeEditTarget.START,
                timelineDurationSeconds = 3_600f,
                exportLimitSeconds = 10_000.0,
            ),
            0.0,
        )
        assertEquals(
            500.5,
            rangeSelectionDurationWheelLimitSeconds(
                values = tenMinuteEnd,
                target = RangeEditTarget.START,
                timelineDurationSeconds = 3_600f,
                exportLimitSeconds = 500.5,
            ),
            0.0,
        )
    }

    @Test
    fun wheelReachabilityLimit_matchesDurationClampSoImpossibleElevenMinutesCannotBeShown() {
        val values = RangeEditValues(startSeconds = 0f, endSeconds = 600f)
        val wheelLimit = rangeSelectionDurationWheelLimitSeconds(
            values = values,
            target = RangeEditTarget.START,
            timelineDurationSeconds = 600f,
            exportLimitSeconds = 10_000.0,
        )
        val clamped = resizeRangeSelectionDuration(
            values = values,
            target = RangeEditTarget.START,
            requestedDurationSeconds = 660f,
            durationSeconds = 600f,
        )

        assertEquals(600.0, wheelLimit, 0.0)
        assertEquals(600.0, rangeSelectionDurationExactSeconds(
            clamped.values.startSeconds, clamped.values.endSeconds,
        ), 0.0)
    }

    @Test
    fun boundaryCursorPreview_playsForwardFromStart_andLeadInToEnd() {
        assertEquals(
            BoundaryCursorPreviewWindow(10f, 30f),
            boundaryCursorPreviewWindow(10f, 30f, endBoundaryActive = false),
        )
        assertEquals(
            BoundaryCursorPreviewWindow(27f, 30f),
            boundaryCursorPreviewWindow(10f, 30f, endBoundaryActive = true),
        )
        assertEquals(
            BoundaryCursorPreviewWindow(28.5f, 30f),
            boundaryCursorPreviewWindow(28.5f, 30f, endBoundaryActive = true),
        )
    }

    @Test
    fun fineTuneHasNearZeroDeadZoneAndAggressiveHorizontalEdges() {
        assertEquals(0f, rangeFineTuneTimelineRate(0f, 0f), 0f)
        assertTrue(rangeFineTuneTimelineRate(0.006f, 0f) > 0f)
        assertTrue(rangeFineTuneTimelineRate(-0.006f, 0f) < 0f)
        val near = rangeFineTuneTimelineRate(0.05f, 0f)
        val middle = rangeFineTuneTimelineRate(0.5f, 0f)
        val edge = rangeFineTuneTimelineRate(1f, 0f)
        assertTrue(middle > near * 2f)
        assertTrue(edge > middle * 8f)
    }

    @Test
    fun fineTuneVerticalAxisControlsPrecisionAndSpeed() {
        val center = rangeFineTuneTimelineRate(0.45f, 0f)
        val fastUp = rangeFineTuneTimelineRate(0.45f, -0.65f)
        val preciseDown = rangeFineTuneTimelineRate(0.45f, 0.65f)
        assertTrue(fastUp > center * 2f)
        assertTrue(preciseDown < center * 0.35f)
        assertTrue(rangeFineTuneTimelineRate(-0.45f, -0.65f) < -center * 2f)
    }

    @Test
    fun fineTuneMovementIsTimelineRelative() {
        val shortDelta = rangeFineTuneDeltaSeconds(
            horizontalPull = 0.6f,
            verticalPull = 0f,
            durationSeconds = 30f,
            dtSeconds = 0.25f,
        )
        val longDelta = rangeFineTuneDeltaSeconds(
            horizontalPull = 0.6f,
            verticalPull = 0f,
            durationSeconds = 300f,
            dtSeconds = 0.25f,
        )
        assertTrue(shortDelta > 0f)
        assertEquals(shortDelta * 10f, longDelta, 0.0001f)
        assertEquals(
            shortDelta / 30f,
            longDelta / 300f,
            0.000001f,
        )
    }


    @Test
    fun shuttlePositionSanitizesInvalidInputWithoutUsingCatalogDuration() {
        assertEquals(12.5, sanitizedShuttlePositionSeconds(12.5), 0.0)
        assertEquals(0.0, sanitizedShuttlePositionSeconds(-4.0), 0.0)
        assertEquals(0.0, sanitizedShuttlePositionSeconds(Double.NaN), 0.0)
        assertEquals(0.0, sanitizedShuttlePositionSeconds(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test
    fun fineTuneShuttleRateTracksDirectionAndAggression() {
        val near = rangeFineTuneShuttleRate(0.08f, 0f)
        val middle = rangeFineTuneShuttleRate(0.50f, 0f)
        val edge = rangeFineTuneShuttleRate(1f, 0f)
        assertTrue(near >= 1f)
        assertTrue(middle > near)
        assertTrue(edge > middle)
        assertTrue(edge <= 8f)
        assertEquals(-middle, rangeFineTuneShuttleRate(-0.50f, 0f), 0.0001f)
        assertTrue(rangeFineTuneShuttleRate(0.50f, -0.65f) > middle)
        assertTrue(rangeFineTuneShuttleRate(0.50f, 0.65f) <= middle)
        assertTrue(rangeFineTuneShuttleRate(0.08f, 0.90f) >= 1f)
    }

    @Test
    fun shuttleSourceHeadUsesOutputHopWithoutRepeatingSlowGrains() {
        val first = nextShuttleSourceAnchorSeconds(null, 10.0, 1f)
        assertEquals(10.0, first!!, 0.000001)

        // A 20 ms target move is below the 32 ms output hop, so replaying would mostly
        // duplicate the previous grain. The audio thread waits instead.
        assertEquals(null, nextShuttleSourceAnchorSeconds(first, 10.020, 1f))
        assertEquals(10.032, nextShuttleSourceAnchorSeconds(first, 10.032, 1f)!!, 0.000001)

        // Small catch-up errors use bounded hops, but large errors re-anchor instead of
        // allowing audible position to trail a fast long-timeline gesture indefinitely.
        assertEquals(10.064, nextShuttleSourceAnchorSeconds(first, 10.10, 2f)!!, 0.000001)
        assertFalse(shuttleSourceRequiresReanchor(first, 10.10, 2f))
        assertTrue(shuttleSourceRequiresReanchor(first, 11.0, 8f))
        assertTrue(shuttleSourceRequiresReanchor(first, 9.0, -1f))
        assertEquals(11.0, nextShuttleSourceAnchorSeconds(first, 11.0, 8f)!!, 0.000001)
        assertEquals(9.0, nextShuttleSourceAnchorSeconds(first, 9.0, -1f)!!, 0.000001)
        assertEquals(null, nextShuttleSourceAnchorSeconds(first, 10.0, 0f))
    }

    @Test
    fun cachedShuttleSlicesKeepOverlapSampleAligned() {
        fun pcm(samples: IntRange): ByteArray = ByteArray(samples.count() * 2).also { bytes ->
            samples.forEachIndexed { index, sample ->
                bytes[index * 2] = (sample and 0xff).toByte()
                bytes[index * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
            }
        }
        fun decode(bytes: ByteArray): List<Int> = (0 until bytes.size / 2).map { index ->
            (((bytes[index * 2 + 1].toInt() shl 8) or (bytes[index * 2].toInt() and 0xff))).toShort().toInt()
        }

        val source = pcm(0..199)
        val first = decode(sliceShuttlePcm16Mono(source, 10.0, 10.032, 10.072, 1_000))
        val second = decode(sliceShuttlePcm16Mono(source, 10.0, 10.064, 10.104, 1_000))
        assertEquals(40, first.size)
        assertEquals(40, second.size)
        assertEquals(first.takeLast(8), second.take(8))
        assertEquals((32..71).toList(), first)
        assertEquals((64..103).toList(), second)
    }

    @Test
    fun shortBoundaryGrainGetsSymmetricWindow() {
        fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
            samples.forEachIndexed { index, sample ->
                bytes[index * 2] = (sample and 0xff).toByte()
                bytes[index * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
            }
        }
        fun decode(bytes: ByteArray): List<Int> = (0 until bytes.size / 2).map { index ->
            (((bytes[index * 2 + 1].toInt() shl 8) or (bytes[index * 2].toInt() and 0xff))).toShort().toInt()
        }

        val windowed = decode(windowShuttlePcm16Mono(pcm(1000, 1000, 1000, 1000, 1000, 1000), 3))
        assertEquals(6, windowed.size)
        assertEquals(0, windowed[0])
        assertTrue(windowed[0] < windowed[1])
        assertEquals(1000, windowed[2])
        assertEquals(1000, windowed[3])
        assertTrue(windowed[4] > windowed[5])
        assertEquals(0, windowed[5])
        assertEquals(windowed[0], windowed[5])
        assertEquals(windowed[1], windowed[4])
    }

    @Test
    fun sparseShuttleTailFadesToZeroWithoutChangingFrameCount() {
        fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
            samples.forEachIndexed { index, sample ->
                bytes[index * 2] = (sample and 0xff).toByte()
                bytes[index * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
            }
        }
        fun decode(bytes: ByteArray): List<Int> = (0 until bytes.size / 2).map { index ->
            (((bytes[index * 2 + 1].toInt() shl 8) or (bytes[index * 2].toInt() and 0xff))).toShort().toInt()
        }

        val faded = decode(fadeOutShuttlePcm16Mono(pcm(1000, 1000, 1000, 1000, 1000)))
        assertEquals(5, faded.size)
        assertEquals(1000, faded.first())
        assertEquals(0, faded.last())
        assertTrue(faded.zipWithNext().all { (left, right) -> left >= right })
    }

    @Test
    fun fineAdjustAudioTargetTracksSelectedBoundaryAndHonorsBounds() {
        val values = RangeEditValues(startSeconds = 10f, endSeconds = 30f)
        assertEquals(10.25f, projectFineAdjustShuttleTarget(
            values = values,
            target = RangeEditTarget.START,
            pendingDeltaSeconds = 0.25f,
            durationSeconds = 40f,
        ), 0.0001f)
        assertEquals(39.95f, projectFineAdjustShuttleTarget(
            values = values,
            target = RangeEditTarget.START,
            pendingDeltaSeconds = 100f,
            durationSeconds = 40f,
        ), 0.0001f)
        assertEquals(0.05f, projectFineAdjustShuttleTarget(
            values = values,
            target = RangeEditTarget.END,
            pendingDeltaSeconds = -100f,
            durationSeconds = 40f,
        ), 0.0001f)
    }

    @Test
    fun shuttlePcmSpeedsAudibleGrainsAndPreservesDirection() {
        fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
            samples.forEachIndexed { index, sample ->
                bytes[index * 2] = (sample and 0xff).toByte()
                bytes[index * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
            }
        }
        fun decode(bytes: ByteArray): List<Int> = (0 until bytes.size / 2).map { index ->
            (((bytes[index * 2 + 1].toInt() shl 8) or (bytes[index * 2].toInt() and 0xff))).toShort().toInt()
        }

        val source = pcm(100, 200, 300, 400, 500, 600, 700, 800)
        assertEquals(
            listOf(100, 200, 300, 400, 500, 600, 700, 800),
            decode(transformShuttlePcm16Mono(source, 1f)),
        )
        assertEquals(
            listOf(800, 700, 600, 500, 400, 300, 200, 100),
            decode(transformShuttlePcm16Mono(source, -1f)),
        )

        val twice = transformShuttlePcm16Mono(source, 2f)
        assertEquals(4, twice.size / 2)
        assertEquals(100, decode(twice).first())
        assertEquals(800, decode(twice).last())
        val reverseTwice = transformShuttlePcm16Mono(source, -2f)
        assertEquals(800, decode(reverseTwice).first())
        assertEquals(100, decode(reverseTwice).last())
        val eightTimes = transformShuttlePcm16Mono(source, 8f)
        assertEquals(1, eightTimes.size / 2)
        assertEquals(8f, shuttleAudibleSpeed(8f), 0f)
        assertEquals(8f, shuttleAudibleSpeed(-8f), 0f)
        assertEquals(1f, shuttleAudibleSpeed(0.2f), 0f)
        assertEquals(0.04, shuttleSourceGrainSeconds(1f), 0.000001)
        assertEquals(0.32, shuttleSourceGrainSeconds(8f), 0.000001)
    }

    @Test
    fun shuttleCrossfadeBridgesDiscontinuousGrains() {
        fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
            samples.forEachIndexed { index, sample ->
                bytes[index * 2] = (sample and 0xff).toByte()
                bytes[index * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
            }
        }
        fun decode(bytes: ByteArray): List<Int> = (0 until bytes.size / 2).map { index ->
            (((bytes[index * 2 + 1].toInt() shl 8) or (bytes[index * 2].toInt() and 0xff))).toShort().toInt()
        }

        val first = crossfadeShuttlePcm16Mono(null, pcm(0, 0, 1000, 1000), overlapFrames = 2)
        assertEquals(listOf(0, 0), decode(first.output))
        assertEquals(listOf(1000, 1000), decode(first.tail))

        val second = crossfadeShuttlePcm16Mono(first.tail, pcm(-1000, -1000, 0, 0), overlapFrames = 2)
        val mixed = decode(second.output)
        assertEquals(2, mixed.size)
        assertTrue(mixed[0] in 450..500)
        assertTrue(mixed[1] in -500..-450)
        assertEquals(listOf(0, 0), decode(second.tail))
    }

    @Test
    fun fineTunePuckKeepsLargeHitTargetAndSeparatesTapFromDrag() {
        assertEquals(24f, RANGE_FINE_TUNE_PUCK_RADIUS_DP, 0f)
        assertEquals(32f, RANGE_FINE_TUNE_PUCK_HIT_RADIUS_DP, 0f)
        assertTrue(rangeFineTunePuckContains(100f, 100f, 100f, 100f, 32f))
        assertTrue(rangeFineTunePuckContains(124f, 100f, 100f, 100f, 32f))
        assertFalse(rangeFineTunePuckContains(133f, 100f, 100f, 100f, 32f))
        assertFalse(rangeFineTuneMovementExceedsSlop(3f, 4f, 8f))
        assertTrue(rangeFineTuneMovementExceedsSlop(7f, 7f, 8f))
    }

    @Test
    fun fineTunePuckTracksTouchXWithoutVisualGain() {
        val width = 200f
        val travel = 80f
        val pointerX = 142f
        val pull = rangeFineTuneHorizontalTouchPull(pointerX, width, travel)
        val puckX = width * 0.5f + pull * travel
        assertEquals(pointerX, puckX, 0.0001f)

        val beyondRight = rangeFineTuneHorizontalTouchPull(199f, width, travel)
        val clampedPuckX = width * 0.5f + beyondRight * travel
        assertTrue(clampedPuckX < 199f)
        assertEquals(180f, clampedPuckX, 0.0001f)
    }

    @Test
    fun fineTuneSeekGainIsSeparateFromPuckGeometry() {
        assertEquals(0.5f, rangeFineTuneSeekPull(0.31f), 0.0001f)
        assertEquals(1f, rangeFineTuneSeekPull(0.80f), 0f)
        val vertical = rangeFineTuneVerticalDragPull(
            startRawVertical = 0f,
            dragDeltaY = 30f,
            verticalTravel = 40f * 2.35f,
        )
        assertTrue(vertical < 0.35f)
    }

    @Test
    fun fineTuneVerticalForceFieldGetsStifferNearHorizontalEdges() {
        val rawY = 0.9f
        val centerY = rangeFineTuneConstrainedY(rawY, 0f)
        val edgeY = rangeFineTuneConstrainedY(rawY, 0.95f)
        assertTrue(centerY > 0f)
        assertTrue(edgeY > 0f)
        assertTrue(edgeY < centerY)
        assertTrue(rangeFineTuneConstrainedY(-rawY, 0.95f) < 0f)
    }

    @Test
    fun fineTuneVerticalDragRemainsRelativeAndLowSensitivity() {
        assertEquals(
            -0.22f,
            rangeFineTuneVerticalDragPull(-0.22f, 0f, 40f),
            0f,
        )
        assertEquals(
            -0.02f,
            rangeFineTuneVerticalDragPull(-0.22f, 8f, 40f),
            0.0001f,
        )
    }

    @Test
    fun progressiveWaveformBatchingPreservesExactFinalPrefixWithBoundedPublishes() {
        val accumulator = ProgressiveWaveformAccumulator(
            bucketCount = RANGE_WAVEFORM_DETAIL_BUCKETS,
            targetPublishCount = 32,
        )
        val updates = mutableListOf<ProgressiveWaveformSnapshot>()
        repeat(RANGE_WAVEFORM_DETAIL_BUCKETS) { index ->
            accumulator.record(
                index = index,
                magnitude = index.toFloat() / RANGE_WAVEFORM_DETAIL_BUCKETS.toFloat(),
            )?.let(updates::add)
        }
        accumulator.finish()?.let(updates::add)

        assertTrue(updates.size <= 32)
        val final = updates.last()
        assertEquals(RANGE_WAVEFORM_DETAIL_BUCKETS, final.builtCount)
        assertEquals(RANGE_WAVEFORM_DETAIL_BUCKETS, final.values.size)
        assertEquals(0f, final.values.first(), 0f)
        assertEquals(
            (RANGE_WAVEFORM_DETAIL_BUCKETS - 1).toFloat() / RANGE_WAVEFORM_DETAIL_BUCKETS.toFloat(),
            final.values.last(),
            0.000001f,
        )
    }

    @Test
    fun waveformSecondPassUsesMoreDetailWithStillFixedSampleBudget() {
        val coarse = RangeWaveformPass.COARSE
        val detail = RangeWaveformPass.DETAIL
        assertTrue(detail.bucketCount >= coarse.bucketCount * 3)
        val coarseFrames = coarse.bucketCount * coarse.probesPerBucket * coarse.framesPerProbe
        val detailFrames = detail.bucketCount * detail.probesPerBucket * detail.framesPerProbe
        assertTrue(coarseFrames > 0)
        assertTrue(detailFrames > coarseFrames)
        // These counts are constants of the UI pass, not functions of clip duration.
        assertEquals(3_840, coarseFrames)
        assertEquals(24_576, detailFrames)
    }

    @Test
    fun waveformNormalizationIsBoundedAndPreservesShape() {
        val normalized = floatArrayOf(0f, 0.25f, 1f, 0.25f, 0f)
            .map(::shapeWaveformMagnitude)
        assertEquals(5, normalized.size)
        assertTrue(normalized.all { it in 0f..1f })
        assertTrue(normalized[2] > normalized[1])
        assertTrue(normalized[1] > normalized[0])
    }
    @Test
    fun exportLimitComparison_usesExactFractionalBoundary() {
        val max = 6 * 3_600.0 + 24 * 60.0 + 16.75
        assertTrue(rangeExportSelectionWithinLimit(max, max))
        assertTrue(rangeExportSelectionWithinLimit(max - 0.001, max))
        assertFalse(rangeExportSelectionWithinLimit(max + 0.001, max))
        assertFalse(rangeExportSelectionWithinLimit(Double.NaN, max))
        assertFalse(rangeExportSelectionWithinLimit(max, Double.NaN))
    }

    @Test
    fun exactEndpointSubtraction_preventsFloatRoundedLimitDisagreement() {
        val start = 25_847.966796875f
        val end = 74_543.7421875f
        val maximum = 48_695.77382086168

        val floatRoundedDuration = (end - start).toDouble()
        val exactEndpointDuration = rangeSelectionDurationExactSeconds(start, end)

        assertEquals(48_695.7734375, floatRoundedDuration, 0.0)
        assertEquals(48_695.775390625, exactEndpointDuration, 0.0)
        assertTrue(rangeExportSelectionWithinLimit(floatRoundedDuration, maximum))
        assertFalse(rangeExportSelectionWithinLimit(exactEndpointDuration, maximum))
    }

}
