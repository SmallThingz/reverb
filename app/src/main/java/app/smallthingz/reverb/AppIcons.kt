package app.smallthingz.reverb

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object AppIcons {
    val settings: ImageVector = Icons.Rounded.Settings
    val incidents: ImageVector = Icons.Rounded.Warning
    val share: ImageVector = Icons.Rounded.Share
    val delete: ImageVector = Icons.Rounded.Delete
    val edit: ImageVector = Icons.Rounded.Edit
    val info: ImageVector = Icons.Rounded.Info
    val close: ImageVector = Icons.Rounded.Close
    val trim: ImageVector = Icons.Rounded.ContentCut
    val back: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack
    val check: ImageVector = Icons.Rounded.Check
    val checked: ImageVector = Icons.Rounded.CheckCircle
    val unchecked: ImageVector = Icons.Rounded.RadioButtonUnchecked
    val multiSelect: ImageVector = Icons.Rounded.SelectAll
    val play: ImageVector = Icons.Rounded.PlayArrow
    val pause: ImageVector = Icons.Rounded.Pause
    val seekBack: ImageVector = Icons.Rounded.Replay10
    val seekForward: ImageVector = Icons.Rounded.Forward10
    val save: ImageVector = Icons.Rounded.Save
    val exportRange: ImageVector = exportRangeIcon
    val library: ImageVector = Icons.AutoMirrored.Rounded.FormatListBulleted
    val audioFile: ImageVector = Icons.Rounded.AudioFile
    val capture: ImageVector = Icons.Rounded.GraphicEq
    val oneShot: ImageVector = oneShotIcon
    val looping: ImageVector = Icons.Rounded.Loop
    val reset: ImageVector = Icons.Rounded.RestartAlt
    val folder: ImageVector = Icons.Rounded.Folder
    val undo: ImageVector = Icons.AutoMirrored.Rounded.Undo
    val backgroundWarning: ImageVector = Icons.Rounded.GppMaybe
    val themeSystem: ImageVector = Icons.Rounded.Devices
    val themeLight: ImageVector = Icons.Rounded.LightMode
    val themeDark: ImageVector = Icons.Rounded.DarkMode
    val arrowDropDown: ImageVector = Icons.Rounded.ArrowDropDown
}


private val oneShotIcon: ImageVector = ImageVector.Builder(
    name = "OneShot",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(5f, 5f)
        verticalLineTo(19f)
        moveTo(8.5f, 12f)
        horizontalLineTo(19f)
        moveTo(15f, 8f)
        lineTo(19f, 12f)
        lineTo(15f, 16f)
    }
}.build()

private val exportRangeIcon: ImageVector = ImageVector.Builder(
    name = "ExportRange",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(5f, 5f)
        verticalLineTo(19f)
        moveTo(19f, 5f)
        verticalLineTo(19f)
        moveTo(8.5f, 8f)
        horizontalLineTo(15.5f)
        moveTo(12f, 11f)
        verticalLineTo(17f)
        moveTo(9.25f, 14.5f)
        lineTo(12f, 17.25f)
        lineTo(14.75f, 14.5f)
    }
}.build()
