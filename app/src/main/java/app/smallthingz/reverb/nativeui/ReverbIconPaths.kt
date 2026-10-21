// AndroidX-derived path portions retain the notice below.
// Reverb's OneShot/ExportRange paths retain the project's GPL-3.0 license.

/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.smallthingz.reverb.nativeui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

// GENERATED from AndroidX Material Rounded 1.7.8 and released Reverb AppIcons.
// Reverb baseline: 3e16f8ddb78dca15df4b436d34d330801ce83ffd; AppIcons SHA-256: baeb2191e2c0e3c98de368c2ab112044abc719f2888fe5527c23d3fab4643273.
// Core source jar SHA-256: 14db888d2f698178799a4c18a58bb134b3e98063c6748c443b339044e0145432.
// Extended source jar SHA-256: 77b7e782a519a3e074fc91e09d59334ac1b815edd87d2e89c0c9ca7c95678e93.
// Per-glyph provenance and license details: nativeui/icons/SOURCE.json and SOURCE.md.
// Float32 H/V/reflection math matches Compose UI PathParser 1.11.2.
// All 32 glyphs use a 24 x 24 viewport. No runtime vector parser/tree.

internal enum class ReverbIcon(val autoMirrored: Boolean = false) {
    Settings,
    Incidents,
    Share,
    Copy,
    Delete,
    Edit,
    Info,
    Close,
    Trim,
    Back(true),
    Check,
    Checked,
    Unchecked,
    MultiSelect,
    Play,
    Pause,
    Save,
    ExportRange,
    Library(true),
    AudioFile,
    Capture,
    OneShot,
    Looping,
    Reset,
    Folder,
    Undo(true),
    BackgroundWarning,
    ThemeSystem,
    ThemeLight,
    ThemeDark,
    ArrowDropDown,
    Add,
    GitHub,
}

/** Private, immutable paths are shared; callers own paints, masks, and tint state. */
internal object ReverbIconPaths {
    fun drawMask(icon: ReverbIcon, canvas: Canvas, paint: Paint) {
        paint.color = Color.BLACK
        paint.shader = null
        paint.colorFilter = null
        paint.pathEffect = null
        paint.strokeMiter = 4f
        when (icon) {
            ReverbIcon.OneShot, ReverbIcon.ExportRange -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.2f
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
            }
            else -> paint.style = Paint.Style.FILL
        }
        when (icon) {
            ReverbIcon.GitHub -> canvas.drawPath(githubPath, paint)
            ReverbIcon.Settings -> {
                canvas.drawPath(settingsPath0, paint)
            }
            ReverbIcon.Incidents -> {
                canvas.drawPath(incidentsPath0, paint)
            }
            ReverbIcon.Share -> {
                canvas.drawPath(sharePath0, paint)
            }
            ReverbIcon.Copy -> {
                canvas.drawPath(copyPath0, paint)
            }
            ReverbIcon.Delete -> {
                canvas.drawPath(deletePath0, paint)
            }
            ReverbIcon.Edit -> {
                canvas.drawPath(editPath0, paint)
            }
            ReverbIcon.Info -> {
                canvas.drawPath(infoPath0, paint)
            }
            ReverbIcon.Close -> {
                canvas.drawPath(closePath0, paint)
            }
            ReverbIcon.Trim -> {
                canvas.drawPath(trimPath0, paint)
            }
            ReverbIcon.Back -> {
                canvas.drawPath(backPath0, paint)
            }
            ReverbIcon.Check -> {
                canvas.drawPath(checkPath0, paint)
            }
            ReverbIcon.Checked -> {
                canvas.drawPath(checkedPath0, paint)
            }
            ReverbIcon.Unchecked -> {
                canvas.drawPath(uncheckedPath0, paint)
            }
            ReverbIcon.MultiSelect -> {
                canvas.drawPath(multiSelectPath0, paint)
            }
            ReverbIcon.Play -> {
                canvas.drawPath(playPath0, paint)
            }
            ReverbIcon.Pause -> {
                canvas.drawPath(pausePath0, paint)
            }
            ReverbIcon.Save -> {
                canvas.drawPath(savePath0, paint)
            }
            ReverbIcon.ExportRange -> {
                canvas.drawPath(exportRangePath0, paint)
            }
            ReverbIcon.Library -> {
                canvas.drawPath(libraryPath0, paint)
            }
            ReverbIcon.AudioFile -> {
                canvas.drawPath(audioFilePath0, paint)
            }
            ReverbIcon.Capture -> {
                canvas.drawPath(capturePath0, paint)
            }
            ReverbIcon.OneShot -> {
                canvas.drawPath(oneShotPath0, paint)
            }
            ReverbIcon.Looping -> {
                canvas.drawPath(loopingPath0, paint)
            }
            ReverbIcon.Reset -> {
                canvas.drawPath(resetPath0, paint)
                canvas.drawPath(resetPath1, paint)
            }
            ReverbIcon.Folder -> {
                canvas.drawPath(folderPath0, paint)
            }
            ReverbIcon.Undo -> {
                canvas.drawPath(undoPath0, paint)
            }
            ReverbIcon.BackgroundWarning -> {
                canvas.drawPath(backgroundWarningPath0, paint)
            }
            ReverbIcon.ThemeSystem -> {
                canvas.drawPath(themeSystemPath0, paint)
            }
            ReverbIcon.ThemeLight -> {
                canvas.drawPath(themeLightPath0, paint)
            }
            ReverbIcon.ThemeDark -> {
                canvas.drawPath(themeDarkPath0, paint)
            }
            ReverbIcon.ArrowDropDown -> {
                canvas.drawPath(arrowDropDownPath0, paint)
            }
            ReverbIcon.Add -> {
                canvas.drawPath(addPath0, paint)
            }
        }
    }

    private val settingsPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(19.5f, 12.0f)
            rCubicTo(0.0f, -0.230000004f, -0.00999999978f, -0.449999988f, -0.0299999993f, -0.680000007f)
            rLineTo(1.86000001f, -1.40999997f)
            rCubicTo(0.400000006f, -0.300000012f, 0.50999999f, -0.860000014f, 0.25999999f, -1.29999995f)
            rLineTo(-1.87f, -3.23000002f)
            rCubicTo(-0.25f, -0.439999998f, -0.790000021f, -0.620000005f, -1.25f, -0.419999987f)
            rLineTo(-2.1500001f, 0.910000026f)
            rCubicTo(-0.370000005f, -0.25999999f, -0.75999999f, -0.49000001f, -1.16999996f, -0.680000007f)
            rLineTo(-0.289999992f, -2.30999994f)
            cubicTo(14.8000002f, 2.38000011f, 14.3699999f, 2.0f, 13.8699999f, 2.0f)
            rLineTo(-3.73000002f, 0.0f)
            cubicTo(9.63000011f, 2.0f, 9.19999981f, 2.38000011f, 9.14000034f, 2.88000011f)
            lineTo(8.85000038f, 5.19000006f)
            rCubicTo(-0.409999996f, 0.189999998f, -0.800000012f, 0.419999987f, -1.16999996f, 0.680000007f)
            lineTo(5.53000021f, 4.96000004f)
            rCubicTo(-0.460000008f, -0.200000003f, -1.0f, -0.0199999996f, -1.25f, 0.419999987f)
            lineTo(2.41000009f, 8.61999989f)
            rCubicTo(-0.25f, 0.439999998f, -0.140000001f, 0.99000001f, 0.25999999f, 1.29999995f)
            rLineTo(1.86000001f, 1.40999997f)
            cubicTo(4.51000023f, 11.5500002f, 4.5f, 11.7700005f, 4.5f, 12.0f)
            rCubicTo(0.0f, 0.229999542f, 0.00999999978f, 0.449999988f, 0.0299999993f, 0.680000007f)
            rLineTo(-1.86000001f, 1.40999997f)
            rCubicTo(-0.400000006f, 0.300000012f, -0.50999999f, 0.860000014f, -0.25999999f, 1.29999995f)
            rLineTo(1.87f, 3.23000002f)
            rCubicTo(0.25f, 0.439999998f, 0.790000021f, 0.620000005f, 1.25f, 0.419999987f)
            rLineTo(2.1500001f, -0.910000026f)
            rCubicTo(0.370000005f, 0.25999999f, 0.75999999f, 0.49000001f, 1.16999996f, 0.680000007f)
            rLineTo(0.289999992f, 2.30999994f)
            cubicTo(9.19999981f, 21.6200008f, 9.63000011f, 22.0f, 10.1300001f, 22.0f)
            rLineTo(3.73000002f, 0.0f)
            rCubicTo(0.5f, 0.0f, 0.930000007f, -0.379999995f, 0.99000001f, -0.879999995f)
            rLineTo(0.289999992f, -2.30999994f)
            rCubicTo(0.409999996f, -0.189999998f, 0.800000012f, -0.419999987f, 1.16999996f, -0.680000007f)
            rLineTo(2.1500001f, 0.910000026f)
            rCubicTo(0.460000008f, 0.200000003f, 1.0f, 0.0199999996f, 1.25f, -0.419999987f)
            rLineTo(1.87f, -3.23000002f)
            rCubicTo(0.25f, -0.439999998f, 0.140000001f, -0.99000001f, -0.25999999f, -1.29999995f)
            rLineTo(-1.86000001f, -1.40999997f)
            cubicTo(19.4899998f, 12.4499998f, 19.5f, 12.2299995f, 19.5f, 12.0f)
            close()
            moveTo(12.04f, 15.5f)
            rCubicTo(-1.92999995f, 0.0f, -3.5f, -1.57000005f, -3.5f, -3.5f)
            rCubicTo(0.0f, -1.93000031f, 1.57000005f, -3.5f, 3.5f, -3.5f)
            rCubicTo(1.93000031f, 0.0f, 3.5f, 1.57000005f, 3.5f, 3.5f)
            cubicTo(15.54f, 13.9300003f, 13.9700003f, 15.5f, 12.04f, 15.5f)
            close()
        }
    }

    private val incidentsPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(4.46999979f, 21.0f)
            rLineTo(15.0600004f, 0.0f)
            rCubicTo(1.53999996f, 0.0f, 2.5f, -1.66999996f, 1.73000002f, -3.0f)
            lineTo(13.7299995f, 4.98999977f)
            rCubicTo(-0.769999981f, -1.33000004f, -2.69000006f, -1.33000004f, -3.46000004f, 0.0f)
            lineTo(2.74000001f, 18.0f)
            rCubicTo(-0.769999981f, 1.33000004f, 0.189999998f, 3.0f, 1.73000002f, 3.0f)
            close()
            moveTo(12.0f, 14.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, -0.449999988f, -1.0f, -1.0f)
            rLineTo(0.0f, -2.0f)
            rCubicTo(0.0f, -0.550000012f, 0.449999988f, -1.0f, 1.0f, -1.0f)
            rCubicTo(0.550000191f, 0.0f, 1.0f, 0.449999988f, 1.0f, 1.0f)
            rLineTo(0.0f, 2.0f)
            rCubicTo(0.0f, 0.550000012f, -0.449999988f, 1.0f, -1.0f, 1.0f)
            close()
            moveTo(13.0f, 18.0f)
            rLineTo(-2.0f, 0.0f)
            rLineTo(0.0f, -2.0f)
            rLineTo(2.0f, 0.0f)
            rLineTo(0.0f, 2.0f)
            close()
        }
    }

    private val sharePath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(18.0f, 16.0799999f)
            rCubicTo(-0.75999999f, 0.0f, -1.44000006f, 0.300000012f, -1.96000004f, 0.769999981f)
            lineTo(8.90999985f, 12.6999998f)
            rCubicTo(0.0500000007f, -0.230000004f, 0.0900000036f, -0.460000008f, 0.0900000036f, -0.699999988f)
            rCubicTo(0.0f, -0.239999771f, -0.0399999991f, -0.469999999f, -0.0900000036f, -0.699999988f)
            rLineTo(7.05000019f, -4.11000013f)
            rCubicTo(0.540000021f, 0.5f, 1.25f, 0.810000002f, 2.03999996f, 0.810000002f)
            rCubicTo(1.65999997f, 0.0f, 3.0f, -1.34000003f, 3.0f, -3.0f)
            rCubicTo(0.0f, -1.65999985f, -1.34000003f, -3.0f, -3.0f, -3.0f)
            rCubicTo(-1.65999985f, 0.0f, -3.0f, 1.34000003f, -3.0f, 3.0f)
            rCubicTo(0.0f, 0.239999995f, 0.0399999991f, 0.469999999f, 0.0900000036f, 0.699999988f)
            lineTo(8.03999996f, 9.81000042f)
            cubicTo(7.5f, 9.31000042f, 6.78999996f, 9.0f, 6.0f, 9.0f)
            rCubicTo(-1.65999997f, 0.0f, -3.0f, 1.34000003f, -3.0f, 3.0f)
            rCubicTo(0.0f, 1.65999985f, 1.34000003f, 3.0f, 3.0f, 3.0f)
            rCubicTo(0.790000021f, 0.0f, 1.5f, -0.310000002f, 2.03999996f, -0.810000002f)
            rLineTo(7.11999989f, 4.15999985f)
            rCubicTo(-0.0500000007f, 0.209999993f, -0.0799999982f, 0.430000007f, -0.0799999982f, 0.649999976f)
            rCubicTo(0.0f, 1.61000001f, 1.30999994f, 2.92000008f, 2.92000008f, 2.92000008f)
            rCubicTo(1.61000061f, 0.0f, 2.92000008f, -1.30999994f, 2.92000008f, -2.92000008f)
            rCubicTo(0.0f, -1.61000061f, -1.30999994f, -2.92000008f, -2.92000008f, -2.92000008f)
            close()
        }
    }

    private val copyPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(15.0f, 20.0f)
            lineTo(5.0f, 20.0f)
            lineTo(5.0f, 7.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            rLineTo(0.0f, 0.0f)
            cubicTo(3.45000005f, 6.0f, 3.0f, 6.44999981f, 3.0f, 7.0f)
            rLineTo(0.0f, 13.0f)
            rCubicTo(0.0f, 1.10000002f, 0.899999976f, 2.0f, 2.0f, 2.0f)
            rLineTo(10.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rLineTo(0.0f, 0.0f)
            cubicTo(16.0f, 20.4500008f, 15.5500002f, 20.0f, 15.0f, 20.0f)
            close()
            moveTo(20.0f, 16.0f)
            lineTo(20.0f, 4.0f)
            rCubicTo(0.0f, -1.10000002f, -0.899999976f, -2.0f, -2.0f, -2.0f)
            lineTo(9.0f, 2.0f)
            cubicTo(7.9000001f, 2.0f, 7.0f, 2.9000001f, 7.0f, 4.0f)
            rLineTo(0.0f, 12.0f)
            rCubicTo(0.0f, 1.10000002f, 0.899999976f, 2.0f, 2.0f, 2.0f)
            rLineTo(9.0f, 0.0f)
            cubicTo(19.1000004f, 18.0f, 20.0f, 17.1000004f, 20.0f, 16.0f)
            close()
            moveTo(18.0f, 16.0f)
            lineTo(9.0f, 16.0f)
            lineTo(9.0f, 4.0f)
            rLineTo(9.0f, 0.0f)
            lineTo(18.0f, 16.0f)
            close()
        }
    }

    private val deletePath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(6.0f, 19.0f)
            rCubicTo(0.0f, 1.10000002f, 0.899999976f, 2.0f, 2.0f, 2.0f)
            rLineTo(8.0f, 0.0f)
            rCubicTo(1.10000002f, 0.0f, 2.0f, -0.899999976f, 2.0f, -2.0f)
            lineTo(18.0f, 9.0f)
            rCubicTo(0.0f, -1.10000002f, -0.899999976f, -2.0f, -2.0f, -2.0f)
            lineTo(8.0f, 7.0f)
            rCubicTo(-1.10000002f, 0.0f, -2.0f, 0.899999976f, -2.0f, 2.0f)
            rLineTo(0.0f, 10.0f)
            close()
            moveTo(18.0f, 4.0f)
            rLineTo(-2.5f, 0.0f)
            rLineTo(-0.709999979f, -0.709999979f)
            rCubicTo(-0.180000007f, -0.180000007f, -0.439999998f, -0.289999992f, -0.699999988f, -0.289999992f)
            lineTo(9.90999985f, 3.0f)
            rCubicTo(-0.25999999f, 0.0f, -0.519999981f, 0.109999999f, -0.699999988f, 0.289999992f)
            lineTo(8.5f, 4.0f)
            lineTo(6.0f, 4.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            rCubicTo(0.0f, 0.550000191f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            rLineTo(12.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rCubicTo(0.0f, -0.550000191f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            close()
        }
    }

    private val editPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(3.0f, 17.4599991f)
            rLineTo(0.0f, 3.03999996f)
            rCubicTo(0.0f, 0.280000001f, 0.219999999f, 0.5f, 0.5f, 0.5f)
            rLineTo(3.03999996f, 0.0f)
            rCubicTo(0.129999995f, 0.0f, 0.25999999f, -0.0500000007f, 0.349999994f, -0.150000006f)
            lineTo(17.8099995f, 9.93999958f)
            rLineTo(-3.75f, -3.75f)
            lineTo(3.1500001f, 17.1000004f)
            rCubicTo(-0.100000001f, 0.100000001f, -0.150000006f, 0.219999999f, -0.150000006f, 0.360000014f)
            close()
            moveTo(20.7099991f, 7.03999996f)
            rCubicTo(0.389999986f, -0.389999986f, 0.389999986f, -1.01999998f, 0.0f, -1.40999997f)
            rLineTo(-2.33999991f, -2.33999991f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.01999998f, -0.389999986f, -1.40999997f, 0.0f)
            rLineTo(-1.83000004f, 1.83000004f)
            rLineTo(3.75f, 3.75f)
            rLineTo(1.83000004f, -1.83000004f)
            close()
        }
    }

    private val infoPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(12.0f, 2.0f)
            cubicTo(6.48000002f, 2.0f, 2.0f, 6.48000002f, 2.0f, 12.0f)
            rCubicTo(0.0f, 5.51999998f, 4.48000002f, 10.0f, 10.0f, 10.0f)
            rCubicTo(5.51999998f, 0.0f, 10.0f, -4.48000002f, 10.0f, -10.0f)
            cubicTo(22.0f, 6.47999954f, 17.5200005f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(12.0f, 17.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, -0.449999988f, -1.0f, -1.0f)
            rLineTo(0.0f, -4.0f)
            rCubicTo(0.0f, -0.550000012f, 0.449999988f, -1.0f, 1.0f, -1.0f)
            rCubicTo(0.550000191f, 0.0f, 1.0f, 0.449999988f, 1.0f, 1.0f)
            rLineTo(0.0f, 4.0f)
            rCubicTo(0.0f, 0.550000012f, -0.449999988f, 1.0f, -1.0f, 1.0f)
            close()
            moveTo(13.0f, 9.0f)
            rLineTo(-2.0f, 0.0f)
            lineTo(11.0f, 7.0f)
            rLineTo(2.0f, 0.0f)
            rLineTo(0.0f, 2.0f)
            close()
        }
    }

    private val closePath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(18.2999992f, 5.71000004f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.01999998f, -0.389999986f, -1.40999997f, 0.0f)
            lineTo(12.0f, 10.5900002f)
            lineTo(7.11000013f, 5.69999981f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.01999998f, -0.389999986f, -1.40999997f, 0.0f)
            rCubicTo(-0.389999986f, 0.389999986f, -0.389999986f, 1.01999998f, 0.0f, 1.40999997f)
            lineTo(10.5900002f, 12.0f)
            lineTo(5.69999981f, 16.8899994f)
            rCubicTo(-0.389999986f, 0.389999986f, -0.389999986f, 1.01999998f, 0.0f, 1.40999997f)
            rCubicTo(0.389999986f, 0.389999986f, 1.01999998f, 0.389999986f, 1.40999997f, 0.0f)
            lineTo(12.0f, 13.4099998f)
            rLineTo(4.88999987f, 4.88999987f)
            rCubicTo(0.389999986f, 0.389999986f, 1.01999998f, 0.389999986f, 1.40999997f, 0.0f)
            rCubicTo(0.389999986f, -0.389999986f, 0.389999986f, -1.01999998f, 0.0f, -1.40999997f)
            lineTo(13.4099998f, 12.0f)
            rLineTo(4.88999987f, -4.88999987f)
            rCubicTo(0.379999995f, -0.379999995f, 0.379999995f, -1.01999998f, 0.0f, -1.39999998f)
            close()
        }
    }

    private val trimPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(9.64000034f, 7.63999987f)
            rCubicTo(0.289999992f, -0.620000005f, 0.419999987f, -1.33000004f, 0.340000004f, -2.08999991f)
            rCubicTo(-0.189999998f, -1.73000002f, -1.53999996f, -3.20000005f, -3.25999999f, -3.49000001f)
            rCubicTo(-2.76999998f, -0.479999989f, -5.13999987f, 1.88999999f, -4.65999985f, 4.6500001f)
            rCubicTo(0.300000012f, 1.72000003f, 1.75999999f, 3.06999993f, 3.49000001f, 3.25999999f)
            rCubicTo(0.75999999f, 0.0799999982f, 1.46000004f, -0.0500000007f, 2.08999991f, -0.340000004f)
            lineTo(10.0f, 12.0f)
            rLineTo(-2.3599999f, 2.3599999f)
            rCubicTo(-0.620000005f, -0.289999992f, -1.33000004f, -0.419999987f, -2.08999991f, -0.340000004f)
            rCubicTo(-1.73000002f, 0.189999998f, -3.20000005f, 1.53999996f, -3.49000001f, 3.25999999f)
            rCubicTo(-0.479999989f, 2.76999998f, 1.88999999f, 5.13000011f, 4.6500001f, 4.6500001f)
            rCubicTo(1.72000003f, -0.300000012f, 3.06999993f, -1.75999999f, 3.25999999f, -3.49000001f)
            rCubicTo(0.0799999982f, -0.75999999f, -0.0500000007f, -1.46000004f, -0.340000004f, -2.08999991f)
            lineTo(12.0f, 14.0f)
            rLineTo(7.59000015f, 7.59000015f)
            rCubicTo(0.889999986f, 0.889999986f, 2.41000009f, 0.25999999f, 2.41000009f, -1.0f)
            rLineTo(0.0f, -0.00999999978f)
            rCubicTo(0.0f, -0.370000005f, -0.150000006f, -0.730000019f, -0.409999996f, -1.0f)
            lineTo(9.64000034f, 7.63999987f)
            close()
            moveTo(6.0f, 8.0f)
            rCubicTo(-1.10000002f, 0.0f, -2.0f, -0.889999986f, -2.0f, -2.0f)
            rCubicTo(0.0f, -1.11000013f, 0.899999976f, -2.0f, 2.0f, -2.0f)
            rCubicTo(1.0999999f, 0.0f, 2.0f, 0.889999986f, 2.0f, 2.0f)
            rCubicTo(0.0f, 1.11000013f, -0.899999976f, 2.0f, -2.0f, 2.0f)
            close()
            moveTo(6.0f, 20.0f)
            rCubicTo(-1.10000002f, 0.0f, -2.0f, -0.889999986f, -2.0f, -2.0f)
            rCubicTo(0.0f, -1.11000061f, 0.899999976f, -2.0f, 2.0f, -2.0f)
            rCubicTo(1.0999999f, 0.0f, 2.0f, 0.889999986f, 2.0f, 2.0f)
            rCubicTo(0.0f, 1.11000061f, -0.899999976f, 2.0f, -2.0f, 2.0f)
            close()
            moveTo(12.0f, 12.5f)
            rCubicTo(-0.280000001f, 0.0f, -0.5f, -0.219999999f, -0.5f, -0.5f)
            rCubicTo(0.0f, -0.279999733f, 0.219999999f, -0.5f, 0.5f, -0.5f)
            rCubicTo(0.279999733f, 0.0f, 0.5f, 0.219999999f, 0.5f, 0.5f)
            rCubicTo(0.0f, 0.279999733f, -0.219999999f, 0.5f, -0.5f, 0.5f)
            close()
            moveTo(19.5900002f, 2.41000009f)
            lineTo(13.0f, 9.0f)
            rLineTo(2.0f, 2.0f)
            rLineTo(6.59000015f, -6.59000015f)
            rCubicTo(0.25999999f, -0.25999999f, 0.409999996f, -0.620000005f, 0.409999996f, -1.0f)
            lineTo(22.0f, 3.4000001f)
            rCubicTo(0.0f, -1.25f, -1.51999998f, -1.88f, -2.41000009f, -0.99000001f)
            close()
        }
    }

    private val backPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(19.0f, 11.0f)
            lineTo(7.82999992f, 11.0f)
            rLineTo(4.88000011f, -4.88000011f)
            rCubicTo(0.389999986f, -0.389999986f, 0.389999986f, -1.02999997f, 0.0f, -1.41999996f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.01999998f, -0.389999986f, -1.40999997f, 0.0f)
            rLineTo(-6.59000015f, 6.59000015f)
            rCubicTo(-0.389999986f, 0.389999986f, -0.389999986f, 1.01999998f, 0.0f, 1.40999997f)
            rLineTo(6.59000015f, 6.59000015f)
            rCubicTo(0.389999986f, 0.389999986f, 1.01999998f, 0.389999986f, 1.40999997f, 0.0f)
            rCubicTo(0.389999986f, -0.389999986f, 0.389999986f, -1.01999998f, 0.0f, -1.40999997f)
            lineTo(7.82999992f, 13.0f)
            lineTo(19.0f, 13.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rCubicTo(0.0f, -0.550000191f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            close()
        }
    }

    private val checkPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(9.0f, 16.1700001f)
            lineTo(5.53000021f, 12.6999998f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.01999998f, -0.389999986f, -1.40999997f, 0.0f)
            rCubicTo(-0.389999986f, 0.389999986f, -0.389999986f, 1.01999998f, 0.0f, 1.40999997f)
            rLineTo(4.17999983f, 4.17999983f)
            rCubicTo(0.389999986f, 0.389999986f, 1.01999998f, 0.389999986f, 1.40999997f, 0.0f)
            lineTo(20.2900009f, 7.71000004f)
            rCubicTo(0.389999986f, -0.389999986f, 0.389999986f, -1.01999998f, 0.0f, -1.40999997f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.01999998f, -0.389999986f, -1.40999997f, 0.0f)
            lineTo(9.0f, 16.1700001f)
            close()
        }
    }

    private val checkedPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(12.0f, 2.0f)
            cubicTo(6.48000002f, 2.0f, 2.0f, 6.48000002f, 2.0f, 12.0f)
            rCubicTo(0.0f, 5.51999998f, 4.48000002f, 10.0f, 10.0f, 10.0f)
            rCubicTo(5.51999998f, 0.0f, 10.0f, -4.48000002f, 10.0f, -10.0f)
            cubicTo(22.0f, 6.47999954f, 17.5200005f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(9.28999996f, 16.2900009f)
            lineTo(5.69999981f, 12.6999998f)
            rCubicTo(-0.389999986f, -0.389999986f, -0.389999986f, -1.01999998f, 0.0f, -1.40999997f)
            rCubicTo(0.389999986f, -0.389999986f, 1.01999998f, -0.389999986f, 1.40999997f, 0.0f)
            lineTo(10.0f, 14.1700001f)
            rLineTo(6.88000011f, -6.88000011f)
            rCubicTo(0.389999986f, -0.389999986f, 1.01999998f, -0.389999986f, 1.40999997f, 0.0f)
            rCubicTo(0.389999986f, 0.389999986f, 0.389999986f, 1.01999998f, 0.0f, 1.40999997f)
            rLineTo(-7.59000015f, 7.59000015f)
            rCubicTo(-0.379999995f, 0.389999986f, -1.01999998f, 0.389999986f, -1.40999997f, 0.0f)
            close()
        }
    }

    private val uncheckedPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(12.0f, 2.0f)
            cubicTo(6.48000002f, 2.0f, 2.0f, 6.48000002f, 2.0f, 12.0f)
            rCubicTo(0.0f, 5.51999998f, 4.48000002f, 10.0f, 10.0f, 10.0f)
            rCubicTo(5.51999998f, 0.0f, 10.0f, -4.48000002f, 10.0f, -10.0f)
            cubicTo(22.0f, 6.47999954f, 17.5200005f, 2.0f, 12.0f, 2.0f)
            close()
            moveTo(12.0f, 20.0f)
            rCubicTo(-4.42000008f, 0.0f, -8.0f, -3.57999992f, -8.0f, -8.0f)
            rCubicTo(0.0f, -4.42000008f, 3.57999992f, -8.0f, 8.0f, -8.0f)
            rCubicTo(4.42000008f, 0.0f, 8.0f, 3.57999992f, 8.0f, 8.0f)
            rCubicTo(0.0f, 4.42000008f, -3.57999992f, 8.0f, -8.0f, 8.0f)
            close()
        }
    }

    private val multiSelectPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(3.0f, 5.0f)
            rLineTo(2.0f, 0.0f)
            lineTo(5.0f, 3.0f)
            rCubicTo(-1.10000002f, 0.0f, -2.0f, 0.899999976f, -2.0f, 2.0f)
            close()
            moveTo(3.0f, 13.0f)
            rLineTo(2.0f, 0.0f)
            rLineTo(0.0f, -2.0f)
            lineTo(3.0f, 11.0f)
            rLineTo(0.0f, 2.0f)
            close()
            moveTo(7.0f, 21.0f)
            rLineTo(2.0f, 0.0f)
            rLineTo(0.0f, -2.0f)
            lineTo(7.0f, 19.0f)
            rLineTo(0.0f, 2.0f)
            close()
            moveTo(3.0f, 9.0f)
            rLineTo(2.0f, 0.0f)
            lineTo(5.0f, 7.0f)
            lineTo(3.0f, 7.0f)
            rLineTo(0.0f, 2.0f)
            close()
            moveTo(13.0f, 3.0f)
            rLineTo(-2.0f, 0.0f)
            rLineTo(0.0f, 2.0f)
            rLineTo(2.0f, 0.0f)
            lineTo(13.0f, 3.0f)
            close()
            moveTo(19.0f, 3.0f)
            rLineTo(0.0f, 2.0f)
            rLineTo(2.0f, 0.0f)
            rCubicTo(0.0f, -1.10000002f, -0.899999976f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(5.0f, 21.0f)
            rLineTo(0.0f, -2.0f)
            lineTo(3.0f, 19.0f)
            rCubicTo(0.0f, 1.10000002f, 0.899999976f, 2.0f, 2.0f, 2.0f)
            close()
            moveTo(3.0f, 17.0f)
            rLineTo(2.0f, 0.0f)
            rLineTo(0.0f, -2.0f)
            lineTo(3.0f, 15.0f)
            rLineTo(0.0f, 2.0f)
            close()
            moveTo(9.0f, 3.0f)
            lineTo(7.0f, 3.0f)
            rLineTo(0.0f, 2.0f)
            rLineTo(2.0f, 0.0f)
            lineTo(9.0f, 3.0f)
            close()
            moveTo(11.0f, 21.0f)
            rLineTo(2.0f, 0.0f)
            rLineTo(0.0f, -2.0f)
            rLineTo(-2.0f, 0.0f)
            rLineTo(0.0f, 2.0f)
            close()
            moveTo(19.0f, 13.0f)
            rLineTo(2.0f, 0.0f)
            rLineTo(0.0f, -2.0f)
            rLineTo(-2.0f, 0.0f)
            rLineTo(0.0f, 2.0f)
            close()
            moveTo(19.0f, 21.0f)
            rCubicTo(1.10000002f, 0.0f, 2.0f, -0.899999976f, 2.0f, -2.0f)
            rLineTo(-2.0f, 0.0f)
            rLineTo(0.0f, 2.0f)
            close()
            moveTo(19.0f, 9.0f)
            rLineTo(2.0f, 0.0f)
            lineTo(21.0f, 7.0f)
            rLineTo(-2.0f, 0.0f)
            rLineTo(0.0f, 2.0f)
            close()
            moveTo(19.0f, 17.0f)
            rLineTo(2.0f, 0.0f)
            rLineTo(0.0f, -2.0f)
            rLineTo(-2.0f, 0.0f)
            rLineTo(0.0f, 2.0f)
            close()
            moveTo(15.0f, 21.0f)
            rLineTo(2.0f, 0.0f)
            rLineTo(0.0f, -2.0f)
            rLineTo(-2.0f, 0.0f)
            rLineTo(0.0f, 2.0f)
            close()
            moveTo(15.0f, 5.0f)
            rLineTo(2.0f, 0.0f)
            lineTo(17.0f, 3.0f)
            rLineTo(-2.0f, 0.0f)
            rLineTo(0.0f, 2.0f)
            close()
            moveTo(8.0f, 17.0f)
            rLineTo(8.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            lineTo(17.0f, 8.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            lineTo(8.0f, 7.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            rLineTo(0.0f, 8.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            close()
            moveTo(9.0f, 9.0f)
            rLineTo(6.0f, 0.0f)
            rLineTo(0.0f, 6.0f)
            lineTo(9.0f, 15.0f)
            lineTo(9.0f, 9.0f)
            close()
        }
    }

    private val playPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(8.0f, 6.82000017f)
            rLineTo(0.0f, 10.3599997f)
            rCubicTo(0.0f, 0.790000021f, 0.870000005f, 1.26999998f, 1.53999996f, 0.839999974f)
            rLineTo(8.14000034f, -5.17999983f)
            rCubicTo(0.620000005f, -0.389999986f, 0.620000005f, -1.28999996f, 0.0f, -1.69000006f)
            lineTo(9.53999996f, 5.98000002f)
            cubicTo(8.86999989f, 5.55000019f, 8.0f, 6.03000021f, 8.0f, 6.82000017f)
            close()
        }
    }

    private val pausePath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(8.0f, 19.0f)
            rCubicTo(1.10000002f, 0.0f, 2.0f, -0.899999976f, 2.0f, -2.0f)
            lineTo(10.0f, 7.0f)
            rCubicTo(0.0f, -1.10000002f, -0.899999976f, -2.0f, -2.0f, -2.0f)
            rCubicTo(-1.10000038f, 0.0f, -2.0f, 0.899999976f, -2.0f, 2.0f)
            rLineTo(0.0f, 10.0f)
            rCubicTo(0.0f, 1.10000002f, 0.899999976f, 2.0f, 2.0f, 2.0f)
            close()
            moveTo(14.0f, 7.0f)
            rLineTo(0.0f, 10.0f)
            rCubicTo(0.0f, 1.10000002f, 0.899999976f, 2.0f, 2.0f, 2.0f)
            rCubicTo(1.10000038f, 0.0f, 2.0f, -0.899999976f, 2.0f, -2.0f)
            lineTo(18.0f, 7.0f)
            rCubicTo(0.0f, -1.10000002f, -0.899999976f, -2.0f, -2.0f, -2.0f)
            rCubicTo(-1.10000038f, 0.0f, -2.0f, 0.899999976f, -2.0f, 2.0f)
            close()
        }
    }

    private val savePath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(17.5900002f, 3.58999991f)
            rCubicTo(-0.379999995f, -0.379999995f, -0.889999986f, -0.589999974f, -1.41999996f, -0.589999974f)
            lineTo(5.0f, 3.0f)
            rCubicTo(-1.11000001f, 0.0f, -2.0f, 0.899999976f, -2.0f, 2.0f)
            rLineTo(0.0f, 14.0f)
            rCubicTo(0.0f, 1.10000002f, 0.899999976f, 2.0f, 2.0f, 2.0f)
            rLineTo(14.0f, 0.0f)
            rCubicTo(1.10000002f, 0.0f, 2.0f, -0.899999976f, 2.0f, -2.0f)
            lineTo(21.0f, 7.82999992f)
            rCubicTo(0.0f, -0.529999971f, -0.209999993f, -1.03999996f, -0.589999974f, -1.40999997f)
            rLineTo(-2.81999993f, -2.82999992f)
            close()
            moveTo(12.0f, 19.0f)
            rCubicTo(-1.65999997f, 0.0f, -3.0f, -1.34000003f, -3.0f, -3.0f)
            rCubicTo(0.0f, -1.65999985f, 1.34000003f, -3.0f, 3.0f, -3.0f)
            rCubicTo(1.65999985f, 0.0f, 3.0f, 1.34000003f, 3.0f, 3.0f)
            rCubicTo(0.0f, 1.65999985f, -1.34000003f, 3.0f, -3.0f, 3.0f)
            close()
            moveTo(13.0f, 9.0f)
            lineTo(7.0f, 9.0f)
            rCubicTo(-1.10000002f, 0.0f, -2.0f, -0.899999976f, -2.0f, -2.0f)
            rCubicTo(0.0f, -1.10000038f, 0.899999976f, -2.0f, 2.0f, -2.0f)
            rLineTo(6.0f, 0.0f)
            rCubicTo(1.10000002f, 0.0f, 2.0f, 0.899999976f, 2.0f, 2.0f)
            rCubicTo(0.0f, 1.0999999f, -0.899999976f, 2.0f, -2.0f, 2.0f)
            close()
        }
    }

    private val exportRangePath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(5.0f, 5.0f)
            lineTo(5.0f, 19.0f)
            moveTo(19.0f, 5.0f)
            lineTo(19.0f, 19.0f)
            moveTo(8.5f, 8.0f)
            lineTo(15.5f, 8.0f)
            moveTo(12.0f, 11.0f)
            lineTo(12.0f, 17.0f)
            moveTo(9.25f, 14.5f)
            lineTo(12.0f, 17.25f)
            lineTo(14.75f, 14.5f)
        }
    }

    private val libraryPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(4.0f, 10.5f)
            rCubicTo(-0.829999983f, 0.0f, -1.5f, 0.670000017f, -1.5f, 1.5f)
            rCubicTo(0.0f, 0.829999924f, 0.670000017f, 1.5f, 1.5f, 1.5f)
            rCubicTo(0.829999924f, 0.0f, 1.5f, -0.670000017f, 1.5f, -1.5f)
            rCubicTo(0.0f, -0.829999924f, -0.670000017f, -1.5f, -1.5f, -1.5f)
            close()
            moveTo(4.0f, 4.5f)
            rCubicTo(-0.829999983f, 0.0f, -1.5f, 0.670000017f, -1.5f, 1.5f)
            cubicTo(2.5f, 6.82999992f, 3.17000008f, 7.5f, 4.0f, 7.5f)
            cubicTo(4.82999992f, 7.5f, 5.5f, 6.82999992f, 5.5f, 6.0f)
            cubicTo(5.5f, 5.17000008f, 4.82999992f, 4.5f, 4.0f, 4.5f)
            close()
            moveTo(4.0f, 16.5f)
            rCubicTo(-0.829999983f, 0.0f, -1.5f, 0.680000007f, -1.5f, 1.5f)
            rCubicTo(0.0f, 0.819999695f, 0.680000007f, 1.5f, 1.5f, 1.5f)
            rCubicTo(0.819999933f, 0.0f, 1.5f, -0.680000007f, 1.5f, -1.5f)
            rCubicTo(0.0f, -0.819999695f, -0.670000017f, -1.5f, -1.5f, -1.5f)
            close()
            moveTo(8.0f, 19.0f)
            rLineTo(12.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rCubicTo(0.0f, -0.549999237f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            lineTo(8.0f, 17.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            rCubicTo(0.0f, 0.549999237f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            close()
            moveTo(8.0f, 13.0f)
            rLineTo(12.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rCubicTo(0.0f, -0.550000191f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            lineTo(8.0f, 11.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            rCubicTo(0.0f, 0.550000191f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            close()
            moveTo(7.0f, 6.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            rLineTo(12.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rCubicTo(0.0f, -0.550000191f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            lineTo(8.0f, 5.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            close()
        }
    }

    private val audioFilePath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(19.4099998f, 7.40999985f)
            rLineTo(-4.82999992f, -4.82999992f)
            cubicTo(14.21f, 2.21000004f, 13.6999998f, 2.0f, 13.1700001f, 2.0f)
            lineTo(6.0f, 2.0f)
            cubicTo(4.9000001f, 2.0f, 4.01000023f, 2.9000001f, 4.01000023f, 4.0f)
            lineTo(4.0f, 20.0f)
            rCubicTo(0.0f, 1.10000002f, 0.889999986f, 2.0f, 1.99000001f, 2.0f)
            lineTo(18.0f, 22.0f)
            rCubicTo(1.10000002f, 0.0f, 2.0f, -0.899999976f, 2.0f, -2.0f)
            lineTo(20.0f, 8.82999992f)
            cubicTo(20.0f, 8.30000019f, 19.7900009f, 7.78999996f, 19.4099998f, 7.40999985f)
            close()
            moveTo(15.0f, 13.0f)
            rLineTo(-2.0f, 0.0f)
            rLineTo(0.0f, 3.6099999f)
            rCubicTo(0.0f, 1.27999997f, -1.0f, 2.41000009f, -2.27999997f, 2.3900001f)
            rCubicTo(-1.44000006f, -0.0199999996f, -2.55999994f, -1.38999999f, -2.13000011f, -2.91000009f)
            rCubicTo(0.209999993f, -0.720000029f, 0.800000012f, -1.30999994f, 1.52999997f, -1.50999999f)
            rCubicTo(0.699999988f, -0.189999998f, 1.36000001f, -0.0500000007f, 1.88f, 0.289999992f)
            lineTo(12.0f, 12.0f)
            rCubicTo(0.0f, -0.550000012f, 0.449999988f, -1.0f, 1.0f, -1.0f)
            rLineTo(2.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, 0.449999988f, 1.0f, 1.0f)
            cubicTo(16.0f, 12.5500002f, 15.5500002f, 13.0f, 15.0f, 13.0f)
            close()
            moveTo(14.0f, 9.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, -0.449999988f, -1.0f, -1.0f)
            lineTo(13.0f, 3.5f)
            lineTo(18.5f, 9.0f)
            lineTo(14.0f, 9.0f)
            close()
        }
    }

    private val capturePath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(8.0f, 18.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            lineTo(9.0f, 7.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            rCubicTo(-0.550000191f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            rLineTo(0.0f, 10.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            close()
            moveTo(12.0f, 22.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            lineTo(13.0f, 3.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            rCubicTo(-0.550000191f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            rLineTo(0.0f, 18.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            close()
            moveTo(4.0f, 14.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rLineTo(0.0f, -2.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            rCubicTo(-0.550000191f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            rLineTo(0.0f, 2.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            close()
            moveTo(16.0f, 18.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            lineTo(17.0f, 7.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            rCubicTo(-0.549999237f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            rLineTo(0.0f, 10.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            close()
            moveTo(19.0f, 11.0f)
            rLineTo(0.0f, 2.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            rCubicTo(0.549999237f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rLineTo(0.0f, -2.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            rCubicTo(-0.549999237f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            close()
        }
    }

    private val oneShotPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(5.0f, 5.0f)
            lineTo(5.0f, 19.0f)
            moveTo(8.5f, 12.0f)
            lineTo(19.0f, 12.0f)
            moveTo(15.0f, 8.0f)
            lineTo(19.0f, 12.0f)
            lineTo(15.0f, 16.0f)
        }
    }

    private val loopingPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(12.0f, 4.0f)
            lineTo(12.0f, 2.21000004f)
            rCubicTo(0.0f, -0.449999988f, -0.540000021f, -0.670000017f, -0.850000024f, -0.349999994f)
            rLineTo(-2.79999995f, 2.78999996f)
            rCubicTo(-0.200000003f, 0.200000003f, -0.200000003f, 0.50999999f, 0.0f, 0.709999979f)
            rLineTo(2.78999996f, 2.78999996f)
            rCubicTo(0.319999993f, 0.310000002f, 0.860000014f, 0.0900000036f, 0.860000014f, -0.360000014f)
            lineTo(12.0f, 6.0f)
            rCubicTo(3.30999994f, 0.0f, 6.0f, 2.69000006f, 6.0f, 6.0f)
            rCubicTo(0.0f, 0.790000021f, -0.150000006f, 1.55999994f, -0.439999998f, 2.25f)
            rCubicTo(-0.150000006f, 0.360000014f, -0.0399999991f, 0.769999981f, 0.230000004f, 1.03999996f)
            rCubicTo(0.50999999f, 0.50999999f, 1.37f, 0.330000013f, 1.63999999f, -0.340000004f)
            rCubicTo(0.370000005f, -0.910000026f, 0.569999993f, -1.90999997f, 0.569999993f, -2.95000005f)
            rCubicTo(0.0f, -4.42000008f, -3.57999992f, -8.0f, -8.0f, -8.0f)
            close()
            moveTo(12.0f, 18.0f)
            rCubicTo(-3.30999994f, 0.0f, -6.0f, -2.69000006f, -6.0f, -6.0f)
            rCubicTo(0.0f, -0.790000021f, 0.150000006f, -1.55999994f, 0.439999998f, -2.25f)
            rCubicTo(0.150000006f, -0.360000014f, 0.0399999991f, -0.769999981f, -0.230000004f, -1.03999996f)
            rCubicTo(-0.50999999f, -0.50999999f, -1.37f, -0.330000013f, -1.63999999f, 0.340000004f)
            cubicTo(4.19999981f, 9.96000004f, 4.0f, 10.96f, 4.0f, 12.0f)
            rCubicTo(0.0f, 4.42000008f, 3.57999992f, 8.0f, 8.0f, 8.0f)
            rLineTo(0.0f, 1.78999996f)
            rCubicTo(0.0f, 0.449999988f, 0.540000021f, 0.670000017f, 0.850000024f, 0.349999994f)
            rLineTo(2.78999996f, -2.78999996f)
            rCubicTo(0.200000003f, -0.200000003f, 0.200000003f, -0.50999999f, 0.0f, -0.709999979f)
            rLineTo(-2.78999996f, -2.78999996f)
            rCubicTo(-0.310000002f, -0.310000002f, -0.850000024f, -0.0900000036f, -0.850000024f, 0.360000014f)
            lineTo(12.0f, 18.0f)
            close()
        }
    }

    private val resetPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(12.0f, 5.0f)
            lineTo(12.0f, 3.21000004f)
            rCubicTo(0.0f, -0.449999988f, -0.540000021f, -0.670000017f, -0.850000024f, -0.349999994f)
            lineTo(8.35000038f, 5.6500001f)
            rCubicTo(-0.200000003f, 0.200000003f, -0.200000003f, 0.50999999f, 0.0f, 0.709999979f)
            rLineTo(2.78999996f, 2.78999996f)
            cubicTo(11.46f, 9.46000004f, 12.0f, 9.23999977f, 12.0f, 8.78999996f)
            lineTo(12.0f, 7.0f)
            rCubicTo(3.30999994f, 0.0f, 6.0f, 2.69000006f, 6.0f, 6.0f)
            rCubicTo(0.0f, 2.72000003f, -1.83000004f, 5.01999998f, -4.30999994f, 5.75f)
            cubicTo(13.2700005f, 18.8700008f, 13.0f, 19.2700005f, 13.0f, 19.7000008f)
            rLineTo(0.0f, 0.0f)
            rCubicTo(0.0f, 0.649999976f, 0.620000005f, 1.15999997f, 1.25f, 0.970000029f)
            cubicTo(17.5699997f, 19.7000008f, 20.0f, 16.6399994f, 20.0f, 13.0f)
            cubicTo(20.0f, 8.57999992f, 16.4200001f, 5.0f, 12.0f, 5.0f)
            close()
        }
    }

    private val resetPath1: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(6.0f, 13.0f)
            rCubicTo(0.0f, -1.34000003f, 0.439999998f, -2.57999992f, 1.19000006f, -3.58999991f)
            rCubicTo(0.300000012f, -0.400000006f, 0.25999999f, -0.949999988f, -0.0900000036f, -1.30999994f)
            rLineTo(0.0f, 0.0f)
            cubicTo(6.67999983f, 7.67999983f, 5.96000004f, 7.71999979f, 5.5999999f, 8.19999981f)
            cubicTo(4.5999999f, 9.53999996f, 4.0f, 11.1999998f, 4.0f, 13.0f)
            rCubicTo(0.0f, 3.6400001f, 2.43000007f, 6.69999981f, 5.75f, 7.67000008f)
            cubicTo(10.3800001f, 20.8600006f, 11.0f, 20.3500004f, 11.0f, 19.7000008f)
            rLineTo(0.0f, 0.0f)
            rCubicTo(0.0f, -0.430000007f, -0.270000011f, -0.829999983f, -0.689999998f, -0.949999988f)
            cubicTo(7.82999992f, 18.0200005f, 6.0f, 15.7200003f, 6.0f, 13.0f)
            close()
        }
    }

    private val folderPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(10.5900002f, 4.59000015f)
            cubicTo(10.21f, 4.21000004f, 9.69999981f, 4.0f, 9.17000008f, 4.0f)
            lineTo(4.0f, 4.0f)
            rCubicTo(-1.10000002f, 0.0f, -1.99000001f, 0.899999976f, -1.99000001f, 2.0f)
            lineTo(2.0f, 18.0f)
            rCubicTo(0.0f, 1.10000002f, 0.899999976f, 2.0f, 2.0f, 2.0f)
            rLineTo(16.0f, 0.0f)
            rCubicTo(1.10000002f, 0.0f, 2.0f, -0.899999976f, 2.0f, -2.0f)
            lineTo(22.0f, 8.0f)
            rCubicTo(0.0f, -1.10000002f, -0.899999976f, -2.0f, -2.0f, -2.0f)
            rLineTo(-8.0f, 0.0f)
            rLineTo(-1.40999997f, -1.40999997f)
            close()
        }
    }

    private val undoPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(12.5f, 8.0f)
            rCubicTo(-2.6500001f, 0.0f, -5.05000019f, 0.99000001f, -6.9000001f, 2.5999999f)
            lineTo(3.71000004f, 8.71000004f)
            cubicTo(3.07999992f, 8.07999992f, 2.0f, 8.52000046f, 2.0f, 9.40999985f)
            lineTo(2.0f, 15.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            rLineTo(5.59000015f, 0.0f)
            rCubicTo(0.889999986f, 0.0f, 1.34000003f, -1.08000004f, 0.709999979f, -1.71000004f)
            rLineTo(-1.90999997f, -1.90999997f)
            rCubicTo(1.38999999f, -1.15999997f, 3.16000009f, -1.88f, 5.11999989f, -1.88f)
            rCubicTo(3.16000009f, 0.0f, 5.88999987f, 1.84000003f, 7.19000006f, 4.5f)
            rCubicTo(0.270000011f, 0.560000002f, 0.910000026f, 0.839999974f, 1.5f, 0.639999986f)
            rCubicTo(0.709999979f, -0.230000004f, 1.07000005f, -1.03999996f, 0.75f, -1.72000003f)
            cubicTo(20.2299995f, 10.4200001f, 16.6499996f, 8.0f, 12.5f, 8.0f)
            close()
        }
    }

    private val backgroundWarningPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(11.3000002f, 2.25999999f)
            rLineTo(-6.0f, 2.25f)
            cubicTo(4.51999998f, 4.80999994f, 4.0f, 5.55000019f, 4.0f, 6.38999987f)
            rLineTo(0.0f, 4.69999981f)
            rCubicTo(0.0f, 5.05000019f, 3.41000009f, 9.76000023f, 8.0f, 10.9099998f)
            rCubicTo(4.59000015f, -1.14999998f, 8.0f, -5.86000013f, 8.0f, -10.9099998f)
            rLineTo(0.0f, -4.69999981f)
            rCubicTo(0.0f, -0.829999983f, -0.519999981f, -1.58000004f, -1.29999995f, -1.87f)
            rLineTo(-6.0f, -2.25f)
            cubicTo(12.25f, 2.08999991f, 11.75f, 2.08999991f, 11.3000002f, 2.25999999f)
            close()
            moveTo(12.0f, 16.0f)
            lineTo(12.0f, 16.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, -0.449999988f, -1.0f, -1.0f)
            rLineTo(0.0f, 0.0f)
            rCubicTo(0.0f, -0.550000012f, 0.449999988f, -1.0f, 1.0f, -1.0f)
            rLineTo(0.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, 0.449999988f, 1.0f, 1.0f)
            rLineTo(0.0f, 0.0f)
            cubicTo(13.0f, 15.5500002f, 12.5500002f, 16.0f, 12.0f, 16.0f)
            close()
            moveTo(12.0f, 12.0f)
            lineTo(12.0f, 12.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, -0.449999988f, -1.0f, -1.0f)
            lineTo(11.0f, 8.0f)
            rCubicTo(0.0f, -0.550000012f, 0.449999988f, -1.0f, 1.0f, -1.0f)
            rLineTo(0.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, 0.449999988f, 1.0f, 1.0f)
            rLineTo(0.0f, 3.0f)
            cubicTo(13.0f, 11.5500002f, 12.5500002f, 12.0f, 12.0f, 12.0f)
            close()
        }
    }

    private val themeSystemPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(4.0f, 7.0f)
            rCubicTo(0.0f, -0.550000012f, 0.449999988f, -1.0f, 1.0f, -1.0f)
            rLineTo(16.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rCubicTo(0.0f, -0.550000191f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            lineTo(4.0f, 4.0f)
            rCubicTo(-1.10000002f, 0.0f, -2.0f, 0.899999976f, -2.0f, 2.0f)
            rLineTo(0.0f, 11.0f)
            rLineTo(-0.5f, 0.0f)
            rCubicTo(-0.829999983f, 0.0f, -1.5f, 0.670000017f, -1.5f, 1.5f)
            cubicTo(0.0f, 19.3299999f, 0.670000017f, 20.0f, 1.5f, 20.0f)
            lineTo(14.0f, 20.0f)
            rLineTo(0.0f, -3.0f)
            lineTo(4.0f, 17.0f)
            lineTo(4.0f, 7.0f)
            close()
            moveTo(23.0f, 8.0f)
            rLineTo(-6.0f, 0.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            rLineTo(0.0f, 10.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            rLineTo(6.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            lineTo(24.0f, 9.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            close()
            moveTo(22.0f, 17.0f)
            rLineTo(-4.0f, 0.0f)
            rLineTo(0.0f, -7.0f)
            rLineTo(4.0f, 0.0f)
            rLineTo(0.0f, 7.0f)
            close()
        }
    }

    private val themeLightPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(12.0f, 7.0f)
            rCubicTo(-2.75999999f, 0.0f, -5.0f, 2.24000001f, -5.0f, 5.0f)
            rCubicTo(0.0f, 2.76000023f, 2.24000001f, 5.0f, 5.0f, 5.0f)
            rCubicTo(2.76000023f, 0.0f, 5.0f, -2.24000001f, 5.0f, -5.0f)
            cubicTo(17.0f, 9.23999977f, 14.7600002f, 7.0f, 12.0f, 7.0f)
            lineTo(12.0f, 7.0f)
            close()
            moveTo(2.0f, 13.0f)
            rLineTo(2.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rCubicTo(0.0f, -0.550000191f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            rLineTo(-2.0f, 0.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            cubicTo(1.0f, 12.5500002f, 1.45000005f, 13.0f, 2.0f, 13.0f)
            close()
            moveTo(20.0f, 13.0f)
            rLineTo(2.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rCubicTo(0.0f, -0.550000191f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            rLineTo(-2.0f, 0.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, 0.449999988f, -1.0f, 1.0f)
            cubicTo(19.0f, 12.5500002f, 19.4500008f, 13.0f, 20.0f, 13.0f)
            close()
            moveTo(11.0f, 2.0f)
            rLineTo(0.0f, 2.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            rCubicTo(0.550000191f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            lineTo(13.0f, 2.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            cubicTo(11.4499998f, 1.0f, 11.0f, 1.45000005f, 11.0f, 2.0f)
            close()
            moveTo(11.0f, 20.0f)
            rLineTo(0.0f, 2.0f)
            rCubicTo(0.0f, 0.550000012f, 0.449999988f, 1.0f, 1.0f, 1.0f)
            rCubicTo(0.550000191f, 0.0f, 1.0f, -0.449999988f, 1.0f, -1.0f)
            rLineTo(0.0f, -2.0f)
            rCubicTo(0.0f, -0.550000012f, -0.449999988f, -1.0f, -1.0f, -1.0f)
            cubicTo(11.4499998f, 19.0f, 11.0f, 19.4500008f, 11.0f, 20.0f)
            close()
            moveTo(5.98999977f, 4.57999992f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.02999997f, -0.389999986f, -1.40999997f, 0.0f)
            rCubicTo(-0.389999986f, 0.389999986f, -0.389999986f, 1.02999997f, 0.0f, 1.40999997f)
            rLineTo(1.05999994f, 1.05999994f)
            rCubicTo(0.389999986f, 0.389999986f, 1.02999997f, 0.389999986f, 1.40999997f, 0.0f)
            rCubicTo(0.379999638f, -0.389999866f, 0.389999986f, -1.02999997f, 0.0f, -1.40999997f)
            lineTo(5.98999977f, 4.57999992f)
            close()
            moveTo(18.3600006f, 16.9500008f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.02999997f, -0.389999986f, -1.40999997f, 0.0f)
            rCubicTo(-0.389999986f, 0.389999986f, -0.389999986f, 1.02999997f, 0.0f, 1.40999997f)
            rLineTo(1.05999994f, 1.05999994f)
            rCubicTo(0.389999986f, 0.389999986f, 1.02999997f, 0.389999986f, 1.40999997f, 0.0f)
            rCubicTo(0.389999986f, -0.389999986f, 0.389999986f, -1.02999997f, 0.0f, -1.40999997f)
            lineTo(18.3600006f, 16.9500008f)
            close()
            moveTo(19.4200001f, 5.98999977f)
            rCubicTo(0.389999986f, -0.389999986f, 0.389999986f, -1.02999997f, 0.0f, -1.40999997f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.02999997f, -0.389999986f, -1.40999997f, 0.0f)
            rLineTo(-1.05999994f, 1.05999994f)
            rCubicTo(-0.389999986f, 0.389999986f, -0.389999986f, 1.02999997f, 0.0f, 1.40999997f)
            rCubicTo(0.38999939f, 0.379999638f, 1.02999997f, 0.389999986f, 1.40999997f, 0.0f)
            lineTo(19.4200001f, 5.98999977f)
            close()
            moveTo(7.05000019f, 18.3600006f)
            rCubicTo(0.389999986f, -0.389999986f, 0.389999986f, -1.02999997f, 0.0f, -1.40999997f)
            rCubicTo(-0.389999986f, -0.389999986f, -1.02999997f, -0.389999986f, -1.40999997f, 0.0f)
            rLineTo(-1.05999994f, 1.05999994f)
            rCubicTo(-0.389999986f, 0.389999986f, -0.389999986f, 1.02999997f, 0.0f, 1.40999997f)
            rCubicTo(0.389999866f, 0.379999161f, 1.02999997f, 0.389999986f, 1.40999997f, 0.0f)
            lineTo(7.05000019f, 18.3600006f)
            close()
        }
    }

    private val themeDarkPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(11.0100002f, 3.04999995f)
            cubicTo(6.51000023f, 3.53999996f, 3.0f, 7.36000013f, 3.0f, 12.0f)
            rCubicTo(0.0f, 4.96999979f, 4.03000021f, 9.0f, 9.0f, 9.0f)
            rCubicTo(4.63000011f, 0.0f, 8.44999981f, -3.5f, 8.94999981f, -8.0f)
            rCubicTo(0.0900000036f, -0.790000021f, -0.779999971f, -1.41999996f, -1.53999996f, -0.949999988f)
            rCubicTo(-0.839999974f, 0.540000021f, -1.84000003f, 0.850000024f, -2.91000009f, 0.850000024f)
            rCubicTo(-2.98000002f, 0.0f, -5.4000001f, -2.42000008f, -5.4000001f, -5.4000001f)
            rCubicTo(0.0f, -1.05999994f, 0.310000002f, -2.05999994f, 0.839999974f, -2.8900001f)
            cubicTo(12.3900003f, 3.94000006f, 11.8999996f, 2.98000002f, 11.0100002f, 3.04999995f)
            close()
        }
    }

    private val arrowDropDownPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(8.71000004f, 11.71f)
            rLineTo(2.58999991f, 2.58999991f)
            rCubicTo(0.389999986f, 0.389999986f, 1.01999998f, 0.389999986f, 1.40999997f, 0.0f)
            rLineTo(2.58999991f, -2.58999991f)
            rCubicTo(0.629999995f, -0.629999995f, 0.180000007f, -1.71000004f, -0.709999979f, -1.71000004f)
            lineTo(9.40999985f, 10.0f)
            rCubicTo(-0.889999986f, 0.0f, -1.33000004f, 1.08000004f, -0.699999988f, 1.71000004f)
            close()
        }
    }

    private val addPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(18.0f, 13.0f)
            rLineTo(-5.0f, 0.0f)
            rLineTo(0.0f, 5.0f)
            rCubicTo(0.0f, 0.550000012f, -0.449999988f, 1.0f, -1.0f, 1.0f)
            rCubicTo(-0.550000191f, 0.0f, -1.0f, -0.449999988f, -1.0f, -1.0f)
            rLineTo(0.0f, -5.0f)
            lineTo(6.0f, 13.0f)
            rCubicTo(-0.550000012f, 0.0f, -1.0f, -0.449999988f, -1.0f, -1.0f)
            rCubicTo(0.0f, -0.550000191f, 0.449999988f, -1.0f, 1.0f, -1.0f)
            rLineTo(5.0f, 0.0f)
            lineTo(11.0f, 6.0f)
            rCubicTo(0.0f, -0.550000012f, 0.449999988f, -1.0f, 1.0f, -1.0f)
            rCubicTo(0.550000191f, 0.0f, 1.0f, 0.449999988f, 1.0f, 1.0f)
            rLineTo(0.0f, 5.0f)
            rLineTo(5.0f, 0.0f)
            rCubicTo(0.550000012f, 0.0f, 1.0f, 0.449999988f, 1.0f, 1.0f)
            rCubicTo(0.0f, 0.550000191f, -0.449999988f, 1.0f, -1.0f, 1.0f)
            close()
        }
    }


    private val githubPath = Path().apply {
        moveTo(12f, 2f)
        cubicTo(6.48f, 2f, 2f, 6.58f, 2f, 12.22f)
        rCubicTo(0f, 4.5f, 2.87f, 8.31f, 6.84f, 9.65f)
        rCubicTo(0.5f, 0.1f, 0.68f, -0.22f, 0.68f, -0.49f)
        rCubicTo(0f, -0.24f, -0.01f, -1.04f, -0.01f, -1.89f)
        rCubicTo(-2.78f, 0.62f, -3.37f, -1.21f, -3.37f, -1.21f)
        rCubicTo(-0.45f, -1.18f, -1.11f, -1.49f, -1.11f, -1.49f)
        rCubicTo(-0.91f, -0.64f, 0.07f, -0.63f, 0.07f, -0.63f)
        rCubicTo(1f, 0.07f, 1.53f, 1.05f, 1.53f, 1.05f)
        rCubicTo(0.9f, 1.57f, 2.36f, 1.12f, 2.94f, 0.85f)
        rCubicTo(0.09f, -0.67f, 0.35f, -1.12f, 0.63f, -1.37f)
        rCubicTo(-2.22f, -0.26f, -4.55f, -1.14f, -4.55f, -5.06f)
        rCubicTo(0f, -1.12f, 0.39f, -2.03f, 1.03f, -2.75f)
        rCubicTo(-0.1f, -0.26f, -0.45f, -1.31f, 0.1f, -2.73f)
        rCubicTo(0f, 0f, 0.84f, -0.28f, 2.75f, 1.05f)
        rCubicTo(0.8f, -0.23f, 1.65f, -0.35f, 2.5f, -0.35f)
        rCubicTo(0.85f, 0f, 1.7f, 0.12f, 2.5f, 0.35f)
        rCubicTo(1.91f, -1.33f, 2.75f, -1.05f, 2.75f, -1.05f)
        rCubicTo(0.55f, 1.42f, 0.2f, 2.47f, 0.1f, 2.73f)
        rCubicTo(0.64f, 0.72f, 1.03f, 1.63f, 1.03f, 2.75f)
        rCubicTo(0f, 3.93f, -2.33f, 4.8f, -4.56f, 5.05f)
        rCubicTo(0.36f, 0.32f, 0.68f, 0.93f, 0.68f, 1.88f)
        rCubicTo(0f, 1.36f, -0.01f, 2.45f, -0.01f, 2.78f)
        rCubicTo(0f, 0.27f, 0.18f, 0.6f, 0.69f, 0.49f)
        cubicTo(19.13f, 20.52f, 22f, 16.72f, 22f, 12.22f)
        cubicTo(22f, 6.58f, 17.52f, 2f, 12f, 2f)
        close()
    }
}
