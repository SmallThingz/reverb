package app.smallthingz.reverb

import smallthingz.ui.geometry.Rect
import java.io.IOException
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeExportEditorMathTest {
    @Test
    fun shuttleSourceCloseFailure_isReportedWithoutEscapingCleanup() {
        val expected = IOException("close failed")
        var observed: Exception? = null

        runCleanupReportingException(
            cleanup = { throw expected },
            onFailure = { observed = it },
        )

        assertSame(expected, observed)
    }

    @Test
    fun shuttleSourceCloseReporterFailure_cannotEscapeCleanup() {
        runCleanupReportingException(
            cleanup = { throw IOException("close failed") },
            onFailure = { throw IllegalStateException("report failed") },
        )
    }

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
    fun selectionDurationWheel_pinsOwnerAndCanInvalidateDelayedCommit() {
        val values = RangeEditValues(startSeconds = 30f, endSeconds = 80f)
        var interaction = RangeDurationWheelInteraction().begin(RangeEditTarget.START)
        assertTrue(interaction.active)
        assertEquals(1L, interaction.generation)
        assertEquals(RangeEditTarget.START, interaction.target)
        assertEquals(RangeEditTarget.START, interaction.commitTarget())

        val committed = resizeRangeSelectionDuration(
            values = values,
            target = requireNotNull(interaction.commitTarget()),
            requestedDurationSeconds = 25f,
            durationSeconds = 100f,
        )
        assertEquals(55f, committed.values.startSeconds, 0f)
        assertEquals(80f, committed.values.endSeconds, 0f)

        interaction = interaction.invalidateCommit()
        assertTrue(interaction.active)
        assertEquals(RangeEditTarget.START, interaction.target)
        assertEquals(null, interaction.commitTarget())

        interaction = interaction.end()
        assertFalse(interaction.active)
        assertEquals(null, interaction.target)
        assertEquals(1L, interaction.generation)
        interaction = interaction.begin(RangeEditTarget.END)
        assertEquals(2L, interaction.generation)
    }

    @Test
    fun markerInputBeforeRedraw_preservesPendingWheelCommit() {
        var interaction = RangeDurationWheelInteraction().begin(RangeEditTarget.START)
        var values = RangeEditValues(30f, 80f)
        var lastTarget = RangeEditTarget.START
        var cancellations = 0
        var auditions = 0
        val gesture = RangeInputGesture(
            interactionAllowed = { true },
            durationInteraction = { interaction },
            onCancel = { cancellations++ },
        )

        assertFalse(gesture.perform {
            interaction = interaction.invalidateCommit()
            lastTarget = RangeEditTarget.END
            values = adjustRangeEditTarget(values, lastTarget, 90f, 100f).values
        })
        assertFalse(gesture.finish { auditions++ })
        assertEquals(RangeEditTarget.START, lastTarget)
        assertEquals(RangeEditValues(30f, 80f), values)
        assertEquals(1, cancellations)
        assertEquals(0, auditions)

        values = resizeRangeSelectionDuration(
            values, requireNotNull(interaction.commitTarget()), 25f, 100f,
        ).values
        interaction = interaction.end()
        assertEquals(RangeEditValues(55f, 80f), values)
        assertFalse(interaction.active)
    }

    @Test
    fun wheelTakeoverDuringMarkerDrag_revokesResumeAndCannotReviveOldGesture() {
        var interaction = RangeDurationWheelInteraction()
        var values = RangeEditValues(30f, 80f)
        var resumeAfterScrub = true
        var cancellations = 0
        var resumes = 0
        val gesture = RangeInputGesture(
            interactionAllowed = { true },
            durationInteraction = { interaction },
            onCancel = { cancellations++; resumeAfterScrub = false },
        )
        assertTrue(gesture.perform {
            values = adjustRangeEditTarget(values, RangeEditTarget.START, 35f, 100f).values
        })

        interaction = interaction.begin(RangeEditTarget.START)
        assertFalse(gesture.perform {
            interaction = interaction.invalidateCommit()
            values = adjustRangeEditTarget(values, RangeEditTarget.START, 40f, 100f).values
        })
        assertEquals(RangeEditTarget.START, interaction.commitTarget())
        assertEquals(RangeEditValues(35f, 80f), values)
        assertFalse(resumeAfterScrub)

        interaction = interaction.end()
        assertFalse(gesture.finish { resumes++ })
        assertFalse(gesture.perform { error("retired gesture must remain terminal") })
        gesture.cancel()
        assertEquals(1, cancellations)
        assertEquals(0, resumes)

        val nextGesture = RangeInputGesture({ true }, { interaction }, { error("new gesture is admitted") })
        assertTrue(nextGesture.perform {
            values = adjustRangeEditTarget(values, RangeEditTarget.START, 40f, 100f).values
        })
        assertTrue(nextGesture.finish { resumes++ })
        assertEquals(RangeEditValues(40f, 80f), values)
        assertEquals(1, resumes)
    }

    @Test
    fun markerTerminalRechecksWheelOwnerAndExplicitAdmission() {
        for (wheelTakeover in listOf(true, false)) {
            var interaction = RangeDurationWheelInteraction()
            var enabled = true
            var cancellations = 0
            var auditions = 0
            val gesture = RangeInputGesture(
                interactionAllowed = { enabled },
                durationInteraction = { interaction },
                onCancel = { cancellations++ },
            )
            assertTrue(gesture.perform {})
            if (wheelTakeover) interaction = interaction.begin(RangeEditTarget.END) else enabled = false

            assertFalse(gesture.finish { auditions++ })
            assertFalse(gesture.finish { auditions++ })
            assertEquals(1, cancellations)
            assertEquals(0, auditions)
            if (wheelTakeover) assertEquals(RangeEditTarget.END, interaction.commitTarget())
        }
    }

    @Test
    fun cancelledMarkerGesture_neverRunsNormalFinallyAction() {
        var resumeAfterScrub = true
        var cancellations = 0
        val gesture = RangeInputGesture(
            interactionAllowed = { true },
            durationInteraction = { RangeDurationWheelInteraction() },
            onCancel = { cancellations++; resumeAfterScrub = false },
        )
        assertTrue(gesture.perform {})
        gesture.cancel()
        assertFalse(gesture.finish { error("cancelled release must not resume or audition") })
        gesture.cancel()
        assertFalse(resumeAfterScrub)
        assertEquals(1, cancellations)
    }

    @Test
    fun uninterruptedMarkerGesture_keepsActionsAndOneNormalTerminal() {
        val events = mutableListOf<String>()
        val gesture = RangeInputGesture({ true }, { RangeDurationWheelInteraction() }, { events += "cancel" })
        assertTrue(gesture.perform { events += "select" })
        assertTrue(gesture.perform { events += "edit" })
        assertTrue(gesture.finish { events += "release" })
        assertFalse(gesture.finish { events += "duplicate release" })
        gesture.cancel()
        assertEquals(listOf("select", "edit", "release"), events)
    }

    @Test
    fun completedWheelInteractionBetweenMarkerCallbacks_retiresOldOriginAndAllowsFreshGestures() {
        for (commitDuration in listOf(false, true)) {
            for (releaseIsNextEvent in listOf(false, true)) {
                var interaction = RangeDurationWheelInteraction()
                var values = RangeEditValues(30f, 80f)
                var resumeAfterScrub = true
                var cancellations = 0
                val oldGesture = RangeInputGesture(
                    interactionAllowed = { true },
                    durationInteraction = { interaction },
                    onCancel = { cancellations++; resumeAfterScrub = false },
                )
                assertTrue(oldGesture.perform {
                    values = adjustRangeEditTarget(values, RangeEditTarget.START, 35f, 100f).values
                })

                // Integer-detent generic scroll completes without a suspended marker event.
                // Profile/unchanged interactions still take ownership even without a value commit.
                interaction = interaction.begin(RangeEditTarget.START)
                if (commitDuration) {
                    values = resizeRangeSelectionDuration(
                        values, requireNotNull(interaction.commitTarget()), 25f, 100f,
                    ).values
                }
                interaction = interaction.end()
                assertFalse(interaction.active)
                val wheelValues = values
                if (releaseIsNextEvent) {
                    assertFalse(oldGesture.finish { error("old marker must not resume or audition") })
                    assertFalse(oldGesture.perform { error("old marker must remain terminal") })
                } else {
                    assertFalse(oldGesture.perform {
                        interaction = interaction.invalidateCommit()
                        values = adjustRangeEditTarget(values, RangeEditTarget.START, 40f, 100f).values
                    })
                    assertFalse(oldGesture.finish { error("old marker must not resume or audition") })
                }
                assertEquals(wheelValues, values)
                assertFalse(resumeAfterScrub)
                assertEquals(1, cancellations)

                val retainedGeneration = interaction.generation
                repeat(2) {
                    val freshGesture = RangeInputGesture(
                        interactionAllowed = { true },
                        durationInteraction = { interaction },
                        onCancel = { error("fresh marker must be admitted") },
                    )
                    assertTrue(freshGesture.perform {
                        interaction = interaction.invalidateCommit()
                        values = adjustRangeEditTarget(
                            values, RangeEditTarget.START, values.startSeconds + 1f, 100f,
                        ).values
                    })
                    assertTrue(freshGesture.finish {})
                }
                assertEquals(wheelValues.startSeconds + 2f, values.startSeconds, 0f)
                assertEquals(retainedGeneration, interaction.generation)
            }
        }
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
    fun fineTuneShuttleRateUsesActualSourceTimeVelocity() {
        val short = rangeFineTuneShuttleRate(0.50f, 0f, 20f)
        val long = rangeFineTuneShuttleRate(0.50f, 0f, 200f)
        assertTrue(short > 0f)
        assertEquals(short * 10f, long, 0.0001f)
        assertEquals(-short, rangeFineTuneShuttleRate(-0.50f, 0f, 20f), 0.0001f)
        assertTrue(rangeFineTuneShuttleRate(0.50f, -0.65f, 20f) > short)
        assertTrue(rangeFineTuneShuttleRate(0.50f, 0.65f, 20f) < short)

        val shortClipEdge = rangeFineTuneShuttleRate(1f, 0f, 20f)
        val longClipEdge = rangeFineTuneShuttleRate(1f, 0f, 200f)
        assertTrue(shortClipEdge in 1f..1.3f)
        assertTrue(shuttleAudibleSpeed(shortClipEdge) in 1f..1.03f)
        assertTrue(longClipEdge > 3f)
        assertTrue(shuttleAudibleSpeed(longClipEdge) < longClipEdge)
    }

    @Test
    fun shuttleSourceHeadUsesOutputHopWithoutRepeatingSlowGrains() {
        val first = nextShuttleSourceAnchorSeconds(null, 10.0, 1f)
        assertEquals(10.0, first!!, 0.000001)

        // A 10 ms target move is below the 16 ms output hop, so replaying would mostly
        // duplicate the previous grain. The audio thread waits instead.
        assertEquals(null, nextShuttleSourceAnchorSeconds(first, 10.010, 1f))
        assertEquals(10.016, nextShuttleSourceAnchorSeconds(first, 10.016, 1f)!!, 0.000001)
        assertEquals(10.016, nextShuttleSourceAnchorSeconds(first, 10.016, 0.5f)!!, 0.000001)

        // Small catch-up errors use bounded hops, but large errors re-anchor instead of
        // allowing audible position to trail a fast long-timeline gesture indefinitely.
        assertEquals(10.032, nextShuttleSourceAnchorSeconds(first, 10.05, 2f)!!, 0.000001)
        assertFalse(shuttleSourceRequiresReanchor(first, 10.05, 2f))
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

        val source = pcm(0..199)
        val first = decode(sliceShuttlePcm16(source, 10.0, 10.032, 10.072, 1_000))
        val second = decode(sliceShuttlePcm16(source, 10.0, 10.064, 10.104, 1_000))
        assertEquals(40, first.size)
        assertEquals(40, second.size)
        assertEquals(first.takeLast(8), second.take(8))
        assertEquals((32..71).toList(), first)
        assertEquals((64..103).toList(), second)
    }

    @Test
    fun shortBoundaryGrainGetsSymmetricWindow() {
        val windowed = decode(windowShuttlePcm16(pcm(1000, 1000, 1000, 1000, 1000, 1000), 3))
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
        val faded = decode(fadeOutShuttlePcm16(pcm(1000, 1000, 1000, 1000, 1000)))
        assertEquals(5, faded.size)
        assertEquals(1000, faded.first())
        assertEquals(0, faded.last())
        assertTrue(faded.zipWithNext().all { (left, right) -> left >= right })
    }

    @Test
    fun fineAdjustAudioTargetTracksSelectedBoundaryAndHonorsBounds() {
        val values = RangeEditValues(startSeconds = 10f, endSeconds = 30f)
        assertEquals(10.25f, projectRangeFineSeekPosition(
            position = FineSeekPosition(),
            values = values,
            target = RangeEditTarget.START,
            pendingDeltaSeconds = 0.25f,
            durationSeconds = 40f,
        ).toFloat(), 0.0001f)
        assertEquals(39.95f, projectRangeFineSeekPosition(
            position = FineSeekPosition(),
            values = values,
            target = RangeEditTarget.START,
            pendingDeltaSeconds = 100f,
            durationSeconds = 40f,
        ).toFloat(), 0.0001f)
        assertEquals(0.05f, projectRangeFineSeekPosition(
            position = FineSeekPosition(),
            values = values,
            target = RangeEditTarget.END,
            pendingDeltaSeconds = -100f,
            durationSeconds = 40f,
        ).toFloat(), 0.0001f)
    }

    @Test
    fun shuttlePcmSpeedsAudibleGrainsAndPreservesDirection() {
        val source = pcm(100, 200, 300, 400, 500, 600, 700, 800)
        assertEquals(
            listOf(100, 200, 300, 400, 500, 600, 700, 800),
            decode(transformShuttlePcm16(source, 1f)),
        )
        assertEquals(
            listOf(800, 700, 600, 500, 400, 300, 200, 100),
            decode(transformShuttlePcm16(source, -1f)),
        )

        val twice = transformShuttlePcm16(source, 2f)
        assertEquals(7, twice.size / 2)
        val forward = decode(twice)
        // Decimation filters the ramp's extended edges; direction and alignment
        // survive, but accelerated endpoint samples need not equal the originals.
        assertTrue(forward.first() in 100 until 200)
        assertTrue(forward.last() in 701..800)
        assertTrue(forward.zipWithNext().all { (left, right) -> left < right })
        val reverseTwice = transformShuttlePcm16(source, -2f)
        assertEquals(7, reverseTwice.size / 2)
        assertEquals(forward.reversed(), decode(reverseTwice))
        val eightTimes = transformShuttlePcm16(source, 8f)
        assertEquals(5, eightTimes.size / 2)
        assertEquals(1.1f, shuttleAudibleSpeed(2f), 0.0001f)
        assertEquals(1.1f, shuttleAudibleSpeed(-2f), 0.0001f)
        assertEquals(1.7f, shuttleAudibleSpeed(8f), 0.0001f)
        assertEquals(1.7f, shuttleAudibleSpeed(-8f), 0.0001f)
        assertEquals(3f, shuttleAudibleSpeed(21f), 0f)
        assertEquals(3f, shuttleAudibleSpeed(200f), 0f)
        assertEquals(1f, shuttleAudibleSpeed(0.2f), 0f)
        assertEquals(0.024, shuttleSourceGrainSeconds(1f), 0.000001)
        assertEquals(0.0408, shuttleSourceGrainSeconds(8f), 0.000001)
    }

    @Test
    fun shuttleDecimationKeepsAudiblePassbandAndRejectsAliasingInBothDirections() {
        val amplitude = 16_000
        fun tone(cyclesPerFrame: Double): ByteArray = ByteArray(2_048 * 2).also { bytes ->
            repeat(bytes.size / 2) { frame ->
                val sample = (amplitude * sin(2 * PI * cyclesPerFrame * frame)).roundToInt()
                bytes[frame * 2] = (sample and 0xff).toByte()
                bytes[frame * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
            }
        }
        fun normalizedRms(bytes: ByteArray): Double {
            // Exclude finite-grain edge extension from the steady-state measurement.
            val frames = 128 until bytes.size / 2 - 128
            val energy = frames.sumOf { frame ->
                val sample = ((bytes[frame * 2 + 1].toInt() shl 8) or
                    (bytes[frame * 2].toInt() and 0xff)).toShort().toDouble() / amplitude
                sample * sample
            }
            return sqrt(energy / frames.count())
        }
        for (rate in listOf(8f, -8f)) {
            // Audible 1.7x pitch must retain this tone; filtering at the 8x
            // transport speed instead would incorrectly remove it.
            val passband = normalizedRms(transformShuttlePcm16(tone(0.2), rate))
            assertEquals("$rate shuttle passband", 1.0 / sqrt(2.0), passband, 0.01)
            val rejected = normalizedRms(transformShuttlePcm16(tone(0.4), rate))
            assertTrue("$rate shuttle aliases with normalized RMS $rejected", rejected < 0.003)
        }
    }

    @Test
    fun shuttleCrossfadeBridgesDiscontinuousGrains() {
        val first = crossfadeShuttlePcm16(null, pcm(0, 0, 1000, 1000), overlapFrames = 2)
        assertEquals(listOf(0, 0), decode(first.output))
        assertEquals(listOf(1000, 1000), decode(first.tail))

        val second = crossfadeShuttlePcm16(first.tail, pcm(-1000, -1000, 0, 0), overlapFrames = 2)
        val mixed = decode(second.output)
        assertEquals(2, mixed.size)
        assertTrue(mixed[0] in 450..500)
        assertTrue(mixed[1] in -500..-450)
        assertEquals(listOf(0, 0), decode(second.tail))
    }

    @Test
    fun stereoShuttleReverseAndSliceKeepLeftRightFrameOrder() {
        val left = intArrayOf(100, 200, 300, 400, 500)
        val right = intArrayOf(-1000, -2000, -3000, -4000, -5000)
        val source = stereoPcm(left, right)
        val reverse = transformShuttlePcm16(source, -1f, channelCount = 2)
        assertEquals(left.reversed(), stereoChannel(reverse, 0))
        assertEquals(right.reversed(), stereoChannel(reverse, 1))
        val slice = sliceShuttlePcm16(source, 10.0, 10.001, 10.004, 1000, channelCount = 2)
        assertEquals(3 * 4, slice.size)
        assertEquals(listOf(200, 300, 400), stereoChannel(slice, 0))
        assertEquals(listOf(-2000, -3000, -4000), stereoChannel(slice, 1))
    }

    @Test
    fun stereoShuttleTransformsMatchIndependentMonoChannelsForTinyAndAcceleratedGrains() {
        for (frames in listOf(1, 2, 3, 8, 97)) {
            val left = IntArray(frames) { 1000 + it * 137 }
            val right = IntArray(frames) { -9000 + it * 73 }
            val source = stereoPcm(left, right)
            for (rate in listOf(-21f, -8f, -2f, -1f, 0f, 1f, 2f, 8f, 21f)) {
                val actual = transformShuttlePcm16(source, rate, channelCount = 2)
                val expectedLeft = transformShuttlePcm16(pcm(*left), rate)
                val expectedRight = transformShuttlePcm16(pcm(*right), rate)
                assertEquals("$frames frames at $rate", expectedLeft.size * 2, actual.size)
                assertEquals("left $frames frames at $rate", decode(expectedLeft), stereoChannel(actual, 0))
                assertEquals("right $frames frames at $rate", decode(expectedRight), stereoChannel(actual, 1))
            }
        }
    }

    @Test
    fun stereoShuttleWindowsAndFadesMatchIndependentMonoChannels() {
        for (frames in listOf(1, 2, 5, 11)) {
            val left = IntArray(frames) { 12000 - it * 311 }
            val right = IntArray(frames) { -3000 - it * 137 }
            val source = stereoPcm(left, right)
            for (edge in listOf(0, 1, 3, 20)) {
                val actual = windowShuttlePcm16(source, edge, channelCount = 2)
                assertEquals(frames * 4, actual.size)
                assertEquals(decode(windowShuttlePcm16(pcm(*left), edge)), stereoChannel(actual, 0))
                assertEquals(decode(windowShuttlePcm16(pcm(*right), edge)), stereoChannel(actual, 1))
            }
            val faded = fadeOutShuttlePcm16(source, channelCount = 2)
            assertEquals(frames * 4, faded.size)
            assertEquals(decode(fadeOutShuttlePcm16(pcm(*left))), stereoChannel(faded, 0))
            assertEquals(decode(fadeOutShuttlePcm16(pcm(*right))), stereoChannel(faded, 1))
        }
    }

    @Test
    fun oppositePolarityStereoSurvivesShuttleTransformAndOverlap() {
        val left = IntArray(2048) { frame ->
            val phase = frame % 128
            (if (phase < 64) phase - 32 else 96 - phase) * 400
        }
        val right = IntArray(left.size) { -left[it] }
        for (rate in listOf(-21f, -8f, -1f, 1f, 8f, 21f)) {
            val transformed = transformShuttlePcm16(stereoPcm(left, right), rate, channelCount = 2)
            val windowed = windowShuttlePcm16(transformed, 16, channelCount = 2)
            val first = crossfadeShuttlePcm16(null, windowed, 16, channelCount = 2)
            val next = crossfadeShuttlePcm16(first.tail, windowed, 16, channelCount = 2)
            for (output in listOf(transformed, windowed, first.output, next.output)) {
                for (channel in 0..1) {
                    val samples = stereoChannel(output, channel)
                    val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
                    assertTrue("channel $channel at $rate cancelled, RMS=$rms", rms > 1000.0)
                }
            }
        }
    }

    @Test
    fun shuttleTruncatedGrainMixesPendingTailFromMatchingStart() {
        for (reverse in listOf(false, true)) for (channels in 1..2) {
            val signal = IntArray(12) { frame -> if (reverse) 1_900 - frame * 100 else 800 + frame * 100 }
            fun interleaved(frames: Int) = pcm(*IntArray(frames * channels) { index ->
                if (index % channels == 0) signal[index / channels] else -signal[index / channels]
            })
            val result = crossfadeShuttlePcm16(interleaved(8), interleaved(12),
                overlapFrames = 4, channelCount = channels)
            assertEquals(decode(interleaved(8)), decode(result.output))
            assertEquals(decode(interleaved(12)).drop(8 * channels), decode(result.tail))
        }
    }

    @Test
    fun shuttleCompletedBoundaryCannotReemitPartialGrain() {
        for (rate in listOf(-21f, -2f, -1f, 1f, 2f, 21f)) {
            val forward = rate > 0
            val anchor = if (forward) 0.048 else 0.018
            val boundary = if (forward) 0.066 else 0.0
            val completed = shuttleAnchorAfterGrain(anchor, if (forward) anchor else 0.0,
                if (forward) boundary else anchor, 0.066, rate)
            assertEquals(boundary, completed, 0.0)
            repeat(8) { assertEquals(null, nextShuttleSourceAnchorSeconds(completed, boundary, rate)) }
            val interior = shuttleAnchorAfterGrain(0.032, 0.008, 0.056, 0.1, rate)
            assertEquals(0.032, interior, 0.0)
        }
    }

    @Test
    fun shuttleFullGrainOverlapMatchesSourceHopAtAllAudibleRates() {
        for (sampleRate in listOf(48_000, 96_000)) for (channels in 1..2) {
            val fullFrames = (0.024 * sampleRate).roundToInt()
            val overlap = (0.008 * sampleRate).roundToInt()
            val hop = (0.016 * sampleRate).roundToInt()
            for (rate in listOf(-21f, -8f, -2f, -1f, 1f, 2f, 8f, 21f)) {
                val sourceFrames = (shuttleSourceGrainSeconds(rate) * sampleRate).roundToInt()
                val transformed = transformShuttlePcm16(ByteArray(sourceFrames * channels * 2), rate, channels)
                val frames = transformed.size / (channels * 2)
                assertEquals("full grain at $sampleRate/$channels/$rate", fullFrames, frames)
                assertEquals(overlap, shuttleOverlapFrames(frames, overlap))
                assertEquals(hop, frames - shuttleOverlapFrames(frames, overlap))
            }
            for (frames in listOf(0, 1, 2, 3, 4, 7, hop, fullFrames - 2, fullFrames - 1, fullFrames)) {
                val tail = shuttleOverlapFrames(frames, overlap)
                assertTrue(tail in 0..overlap)
                if (frames > 0) assertTrue("boundary grain must retain a body", frames - tail > 0)
                if (frames > 2) assertTrue(tail > 0)
            }
        }
    }

    @Test
    fun shuttleContinuousOneTimesOutputPreservesAlignedSourceThroughBoundaries() {
        verifyContinuousShuttleSource(interleaveWaitingCommands = false)
    }

    @Test
    fun shuttleRepeatedAndSubHopCommandsPreserveContinuousSource() {
        verifyContinuousShuttleSource(interleaveWaitingCommands = true)
    }

    @Test
    fun shuttleWaitingRetainsOrdinaryOverlapButFinishesSparseAuditions() {
        val pending = pcm(100, 200, 300, 400, 500, 600, 700, 800)
        for (rate in listOf(-21f, -2f, -1f, 1f, 2f, 21f)) {
            var tail = pending
            val emitted = java.io.ByteArrayOutputStream()
            repeat(8) {
                if (shuttleWaitFinishesPendingTail(rate) && tail.isNotEmpty()) {
                    emitted.write(fadeOutShuttlePcm16(tail))
                    tail = ByteArray(0)
                }
            }
            assertSame("Ordinary waiting must preserve overlap at $rate", pending, tail)
            assertEquals(0, emitted.size())
        }
        for (rate in listOf(-0.99f, -0.25f, -0.02f, 0f, 0.02f, 0.25f, 0.99f)) {
            var tail = pending
            val emitted = java.io.ByteArrayOutputStream()
            repeat(8) {
                if (shuttleWaitFinishesPendingTail(rate) && tail.isNotEmpty()) {
                    emitted.write(fadeOutShuttlePcm16(tail))
                    tail = ByteArray(0)
                }
            }
            assertTrue("Sparse waiting must finish its tail at $rate", tail.isEmpty())
            assertEquals(pending.size, emitted.size())
            assertEquals(decode(fadeOutShuttlePcm16(pending)), decode(emitted.toByteArray()))
        }
    }

    private fun verifyContinuousShuttleSource(interleaveWaitingCommands: Boolean) {
        for (sampleRate in listOf(48_000, 96_000)) {
            val fullFrames = (0.024 * sampleRate).roundToInt()
            val hop = (0.016 * sampleRate).roundToInt()
            val overlap = (0.008 * sampleRate).roundToInt()
            val lengths = listOf(1, 2, 3, 7, fullFrames, hop * 3 + fullFrames,
                hop * 3 + fullFrames - 1, hop * 3 + fullFrames - 2,
                hop * 3 + (0.012 * sampleRate).roundToInt(),
                hop * 3 + (0.018 * sampleRate).roundToInt())
            for (channels in 1..2) for (reverse in listOf(false, true)) {
                for (tone in listOf(false, true)) for (frames in lengths) {
                    val label = "$sampleRate Hz/$channels channels/reverse=$reverse/tone=$tone/$frames frames/wait=$interleaveWaitingCommands"
                    val samples = IntArray(frames * channels) { index ->
                        val frame = index / channels
                        val signal = if (tone) (12_000 * sin(2 * PI * 250 * frame / sampleRate)).roundToInt()
                            else (frame * 37 % 24_001) - 12_000
                        if (index % channels == 0) signal else -signal
                    }
                    val source = pcm(*samples)
                    val duration = frames.toDouble() / sampleRate
                    val rate = if (reverse) -1f else 1f
                    val output = java.io.ByteArrayOutputStream()
                    var tail = ByteArray(0)
                    var previousAnchor: Double? = null
                    var firstOverlap = 0
                    var finalTailFrames = 0
                    var grains = 0
                    fun finishTail() {
                        if (tail.isNotEmpty()) {
                            finalTailFrames = tail.size / (channels * 2)
                            output.write(fadeOutShuttlePcm16(tail, channels))
                            tail = ByteArray(0)
                        }
                    }
                    // Drive the actual source-anchor, overlap and DSP helpers through a
                    // complete traversal, then continue sending the same endpoint.
                    val commandsPerHop = if (interleaveWaitingCommands) 8 else 1
                    for (command in 0..((frames / hop + 8) * commandsPerHop)) {
                        val step = command / commandsPerHop
                        // Same-target polls alternate with fresh quarter-hop positions,
                        // matching worker polls between faster UI updates.
                        val subHop = if (interleaveWaitingCommands) (command % commandsPerHop) / 2 * 0.25 else 0.0
                        val distance = (step + subHop) * hop / sampleRate
                        val target = if (reverse) (duration - distance).coerceAtLeast(0.0)
                            else distance.coerceAtMost(duration)
                        val anchor = nextShuttleSourceAnchorSeconds(previousAnchor, target, rate)
                        if (anchor == null) {
                            if (shuttleWaitFinishesPendingTail(rate)) finishTail()
                            continue
                        }
                        val span = shuttleSourceGrainSeconds(rate)
                        val start = if (reverse) (anchor - span).coerceAtLeast(0.0) else anchor
                        val end = if (reverse) anchor else (anchor + span).coerceAtMost(duration)
                        assertTrue("$label must not replay an exhausted endpoint", end > start)
                        val grain = transformShuttlePcm16(
                            sliceShuttlePcm16(source, 0.0, start, end, sampleRate, channels), rate, channels,
                        )
                        val grainFrames = grain.size / (channels * 2)
                        val completed = shuttleAnchorAfterGrain(anchor, start, end, duration, rate)
                        if (grainFrames <= 2) {
                            finishTail()
                            output.write(windowShuttlePcm16(grain, grainFrames / 2, channels))
                        } else {
                            val grainOverlap = shuttleOverlapFrames(grainFrames, overlap)
                            if (grains == 0) firstOverlap = grainOverlap
                            val blended = crossfadeShuttlePcm16(tail, grain, grainOverlap, channels)
                            output.write(blended.output)
                            tail = blended.tail
                            if (completed != anchor) finishTail()
                        }
                        previousAnchor = completed
                        grains++
                    }
                    finishTail()
                    val actual = decode(output.toByteArray())
                    assertEquals("$label complete sample count", samples.size, actual.size)
                    // The oracle is the original continuous signal, with only the
                    // intentional first/last envelopes. No grain crossfade is modeled.
                    for (frame in 0 until frames) for (channel in 0 until channels) {
                        val sourceFrame = if (reverse) frames - 1 - frame else frame
                        var expected = samples[sourceFrame * channels + channel]
                        if (frames == 2) expected = 0
                        if (frame < firstOverlap) {
                            val t = (frame + 1).toFloat() / (firstOverlap + 1).toFloat()
                            expected = (expected * t * t * (3f - 2f * t)).roundToInt()
                        }
                        if (finalTailFrames > 0 && frame >= frames - finalTailFrames) {
                            val t = if (finalTailFrames == 1) 1f
                                else (frame - (frames - finalTailFrames)).toFloat() / (finalTailFrames - 1)
                            expected = (expected * (1f - t * t * (3f - 2f * t))).roundToInt()
                        }
                        val actualSample = actual[frame * channels + channel]
                        if (kotlin.math.abs(expected - actualSample) > 1) {
                            assertEquals("$label frame=$frame channel=$channel", expected, actualSample)
                        }
                    }
                }
            }
        }
    }

    private fun stereoPcm(left: IntArray, right: IntArray): ByteArray {
        require(left.size == right.size)
        return pcm(*IntArray(left.size * 2) { index ->
            if (index % 2 == 0) left[index / 2] else right[index / 2]
        })
    }

    private fun stereoChannel(bytes: ByteArray, channel: Int): List<Int> =
        decode(bytes).filterIndexed { index, _ -> index % 2 == channel }

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

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample ushr 8) and 0xff).toByte()
        }
    }

    private fun decode(bytes: ByteArray): List<Int> = (0 until bytes.size / 2).map { index ->
        (((bytes[index * 2 + 1].toInt() shl 8) or (bytes[index * 2].toInt() and 0xff))).toShort().toInt()
    }
}
